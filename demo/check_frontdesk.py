"""Browser check of the OPERA simulator's front desk: a guest's stay worked the way a front-desk agent would, with the payment arriving
over Oracle's OHIP operations exactly as the bridge sends it. Reservations and folios render, check-in works, a room night is charged,
check-out is refused while a balance is owed and allowed once it is settled, night audit charges the guests in house and moves the
business date, the page fits a phone, and the browser reports no error. Screenshots go to var/ (git-ignored) for a human to look at.

    ./mvnw -q package -DskipTests && uv run --project demo python demo/check_frontdesk.py
"""
import base64
import json
import os
import pathlib
import socket
import subprocess
import time
import urllib.request

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
PORT = 18090
BASE = f"http://127.0.0.1:{PORT}"
SHOTS = ROOT / "var"


def http(method, path, body=None, headers=None, form=None):
    data = form.encode() if form else (json.dumps(body).encode() if body is not None else None)
    kind = "application/x-www-form-urlencoded" if form else "application/json"
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": kind, **(headers or {})})
    with urllib.request.urlopen(req, timeout=10) as r:
        text = r.read().decode()
        return json.loads(text) if text else None


def pay_through_ohip(reservation, amount):
    """What the bridge does: an OHIP token by client credentials, then postBillingPayment to the reservation's folio window."""
    basic = base64.b64encode(b"fpbridge:fpbridge-secret").decode()
    token = http("POST", "/oauth/v1/tokens", headers={"Authorization": "Basic " + basic, "x-app-key": "demo-app-key", "enterpriseId": "DEMOENT"},
                 form="grant_type=client_credentials&scope=demo-scope")["access_token"]
    criteria = {"hotelId": "DEMO1", "reservationId": {"id": reservation, "type": "Reservation"}, "folioWindowNo": 1, "cashierId": 9001,
                "paymentMethod": {"paymentMethod": "CC"}, "postingAmount": {"amount": amount, "currencyCode": "USD"},
                "postingReference": "post:check-frontdesk", "postingRemark": "Processor reference cap_check", "action": "Billing"}
    reply = http("POST", f"/csh/v1/hotels/DEMO1/reservations/{reservation}/payments", {"criteria": criteria},
                 headers={"Authorization": "Bearer " + token, "x-app-key": "demo-app-key", "x-hotelid": "DEMO1"})
    return reply["postings"][0]["transactionNo"]


def wait_up():
    for _ in range(120):
        try:
            if urllib.request.urlopen(BASE + "/sim/state", timeout=1).status == 200:
                return
        except Exception:
            time.sleep(0.5)
    raise SystemExit("the simulator did not start")


def main():
    jars = sorted((ROOT / "target").glob("folio-pay-bridge-*.jar"))
    if not jars:
        raise SystemExit("Build first: ./mvnw -q package -DskipTests")
    with socket.socket() as s:
        if s.connect_ex(("127.0.0.1", PORT)) == 0:
            raise SystemExit(f"port {PORT} is in use")
    java = os.environ["JAVA_HOME"] + "/bin/java" if os.environ.get("JAVA_HOME") else "java"
    sim = subprocess.Popen([java, "-cp", str(jars[-1]), "-Dloader.main=dev.foliopaysim.SimulatorApplication", "org.springframework.boot.loader.launch.PropertiesLauncher",
                            f"--server.port={PORT}", "--spring.profiles.active=opera"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    problems = []
    SHOTS.mkdir(exist_ok=True)
    try:
        wait_up()
        with sync_playwright() as p:
            browser = p.chromium.launch()
            page = browser.new_page(viewport={"width": 1440, "height": 1000})
            # The one refusal the check provokes (check-out with a balance owed answers 400) is logged by the browser; nothing else may be.
            expected = lambda m: "400" in m.text and m.location.get("url", "").endswith("/RES-1002/check-out")
            page.on("console", lambda m: m.type == "error" and not expected(m) and problems.append("console: " + m.text + " " + m.location.get("url", "")))
            page.on("pageerror", lambda e: problems.append("page error: " + str(e)))
            page.goto(BASE + "/")
            page.wait_for_selector("[data-testid=reservations] >> text=RES-1001")
            day = page.inner_text("[data-testid=business-date]")
            assert "IN_HOUSE" in page.locator("tr", has_text="RES-1001").inner_text()

            page.locator("tr", has_text="RES-1002").click()
            page.wait_for_selector("[data-testid=folio-title] >> text=DUE_IN")
            page.click("[data-testid=check-in]")
            page.wait_for_selector("[data-testid=folio-title] >> text=IN_HOUSE")
            page.click("[data-testid=room-charge]")
            page.wait_for_selector("[data-testid=folio-balance] >> text=USD 320.00")
            page.click("[data-testid=check-out]")
            page.wait_for_selector("[data-testid=folio-note] >> text=settle it before check-out")
            page.screenshot(path=str(SHOTS / "frontdesk-balance-owed.png"), full_page=True)

            transaction = pay_through_ohip("RES-1002", 320.00)
            page.wait_for_selector("[data-testid=folio-balance] >> text=USD 0.00")
            lines = page.inner_text("[data-testid=folio-lines]")
            assert str(transaction) in lines and "9001 FPBRIDGE" in lines and "post:check-frontdesk" in lines, lines
            assert "RES-1002" in page.inner_text("[data-testid=payments]")
            page.click("[data-testid=check-out]")
            page.wait_for_selector("[data-testid=folio-title] >> text=CHECKED_OUT")
            page.screenshot(path=str(SHOTS / "frontdesk-checked-out.png"), full_page=True)

            page.locator("tr", has_text="RES-1001").click()
            page.wait_for_selector("[data-testid=folio-title] >> text=RES-1001")
            before = page.locator("[data-testid=folio-lines] tbody tr").count()
            page.click("[data-testid=night-audit]")
            page.wait_for_function(f"document.querySelector('[data-testid=business-date]').textContent !== '{day}'")
            page.wait_for_function(f"document.querySelectorAll('[data-testid=folio-lines] tbody tr').length === {before + 1}")
            page.screenshot(path=str(SHOTS / "frontdesk-after-audit.png"), full_page=True)

            phone = browser.new_page(viewport={"width": 390, "height": 844})
            phone.on("pageerror", lambda e: problems.append("page error (phone): " + str(e)))
            phone.goto(BASE + "/sim/opera.html")
            phone.wait_for_selector("[data-testid=reservations] >> text=RES-1001")
            width = phone.evaluate("document.documentElement.scrollWidth")
            assert width <= 390 + 1, f"the page scrolls sideways on a phone: {width}px"
            phone.screenshot(path=str(SHOTS / "frontdesk-phone.png"), full_page=True)
            browser.close()
    finally:
        sim.terminate()
        sim.wait(timeout=30)
    if problems:
        print("\n".join(problems))
        raise SystemExit(f"{len(problems)} browser error(s)")
    print("front desk OK: reservations, folio, check-in, room night, check-out refused then allowed after an OHIP payment, night audit, phone width; no browser errors")


if __name__ == "__main__":
    main()
