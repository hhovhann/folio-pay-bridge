"""Browser check of the operator dashboard against the real application (demo profile, fakes): every page renders from real API data,
the money stays per currency, Replay works, a payment request can be created, and the browser reports no error at all, which includes
content security policy violations. Screenshots go to var/ (git-ignored) for a human to look at.

    ./mvnw -q package -DskipTests && uv run --project demo python demo/check_dashboard.py
"""
import json
import os
import pathlib
import socket
import subprocess
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
PORT = 18083
BASE = f"http://127.0.0.1:{PORT}"
SHOTS = ROOT / "var"


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        text = r.read().decode()
        return json.loads(text) if text else None


def wait_up():
    for _ in range(120):
        try:
            if urllib.request.urlopen(BASE + "/actuator/health", timeout=1).status == 200:
                return
        except Exception:
            time.sleep(0.5)
    raise SystemExit("the application did not start")


def seed():
    """Synthetic data through the public API only: two currencies, a link, and one payment left waiting for OPERA."""
    token = call("POST", "/api/v1/cards", {"pan": "4111111111111111", "expiry": "12/29"})["token"]
    for key, ref, amount, cur in [("k-1", "BK-1001", 80000, "USD"), ("k-2", "BK-1002", 45000, "AED"), ("k-3", "BK-1003", 125000, "USD")]:
        call("POST", "/api/v1/payments", {"idempotencyKey": key, "bookingRef": ref, "folioId": "RES-" + ref[3:], "token": token, "amountMinor": amount, "currency": cur})
    call("POST", "/api/v1/sim", {"psp": "NONE", "opera": "DOWN"})
    call("POST", "/api/v1/payments", {"idempotencyKey": "k-4", "bookingRef": "BK-1004", "folioId": "RES-1004", "token": token, "amountMinor": 60000, "currency": "USD"})
    call("POST", "/api/v1/sim", {"psp": "NONE", "opera": "NONE"})
    call("POST", "/api/v1/booking-events", {"type": "NEW", "bookingRef": "BK-2001", "folioId": "RES-2001", "ratePlan": "FLEXIBLE", "arrival": "2030-06-01", "totalMinor": 300000, "currency": "USD"})


def main():
    jars = sorted((ROOT / "target").glob("folio-pay-bridge-*.jar"))
    if not jars:
        raise SystemExit("Build first: ./mvnw -q package -DskipTests")
    jar = jars[-1]
    with socket.socket() as s:
        if s.connect_ex(("127.0.0.1", PORT)) == 0:
            raise SystemExit(f"port {PORT} is in use")
    env = dict(os.environ)
    app = subprocess.Popen([env.get("JAVA_HOME", "") + "/bin/java" if env.get("JAVA_HOME") else "java", "-jar", str(jar), f"--server.port={PORT}",
                            "--spring.profiles.active=demo"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    problems = []
    try:
        wait_up()
        seed()
        with sync_playwright() as p:
            browser = p.chromium.launch()
            page = browser.new_page(viewport={"width": 1440, "height": 900})
            page.on("console", lambda m: m.type == "error" and problems.append("console: " + m.text))
            page.on("pageerror", lambda e: problems.append("page error: " + str(e)))

            page.goto(BASE + "/dashboard")
            page.wait_for_selector("[data-testid=kpi-captured]:has-text('USD')")
            captured = page.inner_text("[data-testid=kpi-captured]")
            assert "USD 2,650.00" in captured and "AED 450.00" in captured, captured
            assert "1" in page.inner_text("[data-testid=kpi-dead]")
            page.screenshot(path=str(SHOTS / "dashboard-overview.png"), full_page=True)

            page.click("text=Payments")
            page.wait_for_selector("[data-testid=payments-table]")
            row = page.locator("tr", has_text="BK-1004")
            row.get_by_role("button", name="Replay").click()
            page.wait_for_selector("text=Posted to folio RES-1004")
            page.locator("tr", has_text="BK-1001").click()
            page.wait_for_selector("role=dialog")
            page.wait_for_timeout(400)  # let the slide-in finish before the picture
            page.screenshot(path=str(SHOTS / "dashboard-payment-detail.png"))
            page.keyboard.press("Escape")
            page.wait_for_selector("role=dialog", state="detached")

            page.click("text=Payment links")
            page.fill("label:has-text('Reference') input", "FNB-2026-1201")
            page.fill("label:has-text('Folio') input", "RES-1001")
            page.fill("label:has-text('Amount') input", "450")
            page.select_option("label:has-text('Currency') select", "AED")
            page.fill("label:has-text('Description') input", "Friday brunch, table for four")
            page.click("text=Create request")
            page.wait_for_selector("[data-testid=links-table] >> text=FNB-2026-1201")
            page.screenshot(path=str(SHOTS / "dashboard-links.png"), full_page=True)

            page.click("text=Reconciliation")
            page.select_option("label:has-text('Acquirer statement') select", "messy")
            page.click("text=Reconcile the acquirer's statement")
            page.wait_for_selector("[data-testid=recon-table]")
            assert "missing in settlement" in page.inner_text("[data-testid=recon-table]").lower()
            page.screenshot(path=str(SHOTS / "dashboard-reconciliation.png"), full_page=True)

            page.click("text=Webhooks")
            page.wait_for_selector("text=webhooks are not configured")

            page.emulate_media(color_scheme="dark")
            page.click("text=Overview")
            page.wait_for_selector("[data-testid=flow]")
            page.screenshot(path=str(SHOTS / "dashboard-overview-dark.png"), full_page=True)

            phone = browser.new_page(viewport={"width": 390, "height": 844})
            phone.on("pageerror", lambda e: problems.append("page error (phone): " + str(e)))
            phone.goto(BASE + "/dashboard/#/payments")
            phone.wait_for_selector("[data-testid=payments-table]")
            width = phone.evaluate("document.documentElement.scrollWidth")
            assert width <= 390 + 1, f"the page scrolls sideways on a phone: {width}px"
            phone.screenshot(path=str(SHOTS / "dashboard-phone.png"), full_page=True)
            browser.close()
    finally:
        app.terminate()
        app.wait(timeout=30)
    if problems:
        print("\n".join(problems))
        raise SystemExit(f"{len(problems)} browser error(s)")
    print("dashboard OK: overview, payments + replay, links + create, reconciliation, webhooks, dark theme, phone width; no browser errors")


if __name__ == "__main__":
    main()
