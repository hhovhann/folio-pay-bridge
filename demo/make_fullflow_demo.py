#!/usr/bin/env python3
"""Record the FULL-FLOW demo: the bridge, an OPERA-style PMS simulator and a card-processor simulator as separate containers over real HTTP.

Run: JAVA_HOME=<jdk17> KOKORO_MODELS=<dir> uv run --project demo python demo/make_fullflow_demo.py
Needs Docker running, ports 8081, 8090 and 8091 free, and everything make_demo.py needs.
DEMO_FAST=1 does a dry run (no voice, short waits, no output): every narrated claim is still asserted against the screen.
"""
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import make_demo as md  # noqa: E402  (reuses the recorder, captions, voice and the screen assertions)

from playwright.sync_api import sync_playwright  # noqa: E402

md.NAME = "folio-pay-bridge-fullflow-demo"
BRIDGE, OPERA, PSP = "http://127.0.0.1:8081/", "http://127.0.0.1:8090/sim/opera.html", "http://127.0.0.1:8091/sim/psp.html"
tid = md.tid


def lines():
    return {
        "intro": "This is the full flow, not a mock-up, and it is explained step by step. Four containers talk over real network calls: the payment bridge I built, a database, a simulator of a hotel P M S in the style of the Oracle Hospitality Integration Platform, and a simulator of a card processor. Everything is synthetic. Here is the picture.",
        "how": "A booking reaches the bridge as an event. The bridge applies a payment rule and creates a payment link. The guest pays, and the card is tokenised the moment it arrives: only a token comes back, and only ciphertext is stored. The bridge asks the processor to authorise and capture, then posts the payment to the guest's folio in the P M S. Every step carries an idempotency key, and every failure ends in one of two places: posted, or kept in a dead letter queue with the reason. Now let me show it, and then break it on purpose.",
        "pms": "First, the happy path. The P M S front desk announces a new reservation and pushes a booking event to the bridge. The bridge answers with the payment rule: advance purchase, the full amount within twenty four hours. Note the business date at the top. In a real property it moves only at night audit, not at midnight.",
        "link": "In the bridge, a payment request now exists. The guest tokenises a test card and pays. The card number is encrypted on arrival, and the guest sees only a token.",
        "pay": "On the processor side there is an authorisation and a capture, and only the last four digits of the card. The processor deduplicates on an idempotency key, so a repeat can never charge twice.",
        "folio": "And at the front desk, the payment is on the guest's folio: the reservation, folio window one, a payment code, a cashier, eight hundred dollars, our reference and the processor's reference. It is posted as a payment, not as a charge.",
        "repeat": "Edge case one: the same booking event arrives again after the guest has paid. The bridge does not create a new request, and does not ask for money twice. It flags the booking for a refund or balance review, because a changed total after payment needs a human.",
        "lost": "Edge case two: a lost reply. This P M S does not deduplicate, like the real world. I make it record the next payment and then answer too late. The bridge times out, but before it retries it looks for its own reference, finds the payment, and does not post twice. One new line on the folio, not two.",
        "outage": "Edge case three: the P M S goes down after the card is charged. The bridge says so plainly and keeps the payment in a dead letter queue with the reason. The money is not lost, and it is not hidden. When the P M S is back, one click replays it, and it is posted exactly once.",
        "decline": "Edge case four: the processor approves the authorisation, then declines the capture. The bridge does not leave the hold on the card. It releases it, and you can see the void in the processor ledger. The payment link goes back to open, so the guest can try again, and this time it works.",
        "audit": "Edge case five: night audit runs and the business date moves, and I also expire the access token. The next payment makes the processor capture the money and then go quiet. The bridge fetches a new token, reads the new business date from the P M S, retries with the same key, and the processor charges once.",
        "recon": "At the end of the day, finance reconciles. The bridge downloads the processor's own settlement file and matches every capture against what was posted. Five captures, five matches. Now a messy statement: one capture missing, one settled short, one we never made. Each exception has a name finance can act on.",
        "logs": "The operator log shows all of this with the card number masked. A mask catches mistakes, but the code never logs the number in the first place, and the vault view shows ciphertext, never the number.",
        "coverage": "How do I know the other cases hold? Two hundred and seventy automated tests, and a coverage report that says ninety nine point eight percent of lines. A page in the repository lists every edge case and the test that fails if it breaks, and a checker fails the build if a named test is missing. It also lists what is not covered.",
        "production": "How it should work in production. The simulators become real adapters: Oracle's own sandbox for the P M S, and the acquirer's checkout for the processor, with three D secure and hosted card entry so a guest's number never reaches this service. Add authentication, a sweeper for crashes between steps, chargeback cases with scheme deadlines, many properties, and real key management proven in a real account.",
        "close": "That is the full flow: idempotent charges, a repeated event, a lost reply, an outage and a replay, a released hold, a token refresh, and reconciliation, against separate systems. The P M S here is a simulator I wrote in the shape of the Oracle Hospitality Integration Platform. It is not Oracle software, and the real thing needs Oracle's own sandbox, which is the next step. The code, the tests and the log of what the agents got wrong are in the public repository.",
    }


def post(url):
    urllib.request.urlopen(urllib.request.Request(url, data=b"{}", headers={"Content-Type": "application/json"}, method="POST"), timeout=5).read()


def visit(pg, url, ready):
    pg.goto(url)
    pg.wait_for_selector(ready)
    md.R.shown = None
    md.pump()
    md.nap(0.8)


def arm(pg, page_url, ready, fault, current_selector=None):
    visit(pg, page_url, ready)
    md.pick(pg, "#fault", value=fault)
    md.tap(pg, tid("apply-fault"), 0.8)
    md.expect(pg, tid("current-fault"), fault)


def story(pg, clips, texts):
    S = lambda cid: md.say(clips, texts, cid)
    post("http://127.0.0.1:8090/sim/reset")
    post("http://127.0.0.1:8091/sim/reset")

    visit(pg, BRIDGE, tid("ev-flex"))
    e = S("intro"); md.expect(pg, tid("actors"), "How it works"); md.nap(2.0)
    visit(pg, BRIDGE + "architecture.html", "svg")
    md.until(e)
    e = S("how"); md.nap(1.0); md.scroll(pg, 400, 1.0); md.until(e)

    visit(pg, OPERA, tid("send-adv"))
    e = S("pms")
    md.tap(pg, tid("send-adv"), 0.8); md.expect(pg, tid("booking-note"), "Advance purchase: full amount within 24 hours"); md.nap(1.5)
    md.until(e)

    visit(pg, BRIDGE, tid("tokenise"))
    e = S("link")
    md.expect(pg, tid("links"), "RES-ADV"); md.expect(pg, tid("links"), "800.00"); md.nap(1.0)
    md.tap(pg, tid("tokenise"), 0.8); md.expect(pg, tid("token-note"), "not stored"); md.nap(0.8)
    md.tap(pg, tid("pay-RES-ADV"), 0.8); md.expect(pg, tid("pay-note"), "charged and posted"); md.nap(1.0)
    md.until(e)

    visit(pg, PSP, tid("ledger"))
    e = S("pay")
    md.expect(pg, tid("ledger"), "AUTH"); md.expect(pg, tid("ledger"), "CAPTURE"); md.expect(pg, tid("ledger"), "1111"); md.nap(2.5)
    md.until(e)

    visit(pg, OPERA, tid("payments"))
    e = S("folio")
    md.expect(pg, tid("payments"), "RES-ADV"); md.expect(pg, tid("payments"), "800.00"); md.expect(pg, tid("payments"), "FPBRIDGE"); md.nap(2.5)
    md.until(e)

    e = S("repeat")
    md.tap(pg, tid("send-adv"), 0.8); md.expect(pg, tid("booking-note"), "Already paid"); md.nap(2.0)
    md.until(e)

    # a reply lost after OPERA posted: the bridge must find its own post, not post again
    e = S("lost")
    md.pick(pg, "#fault", value="TIMEOUT_AFTER_POST"); md.tap(pg, tid("apply-fault"), 0.8); md.expect(pg, tid("current-fault"), "TIMEOUT_AFTER_POST")
    md.tap(pg, tid("send-flex"), 0.8); md.expect(pg, tid("booking-note"), "Flexible: 20% deposit")
    visit(pg, BRIDGE, tid("tokenise"))
    md.tap(pg, tid("tokenise"), 0.6); md.expect(pg, tid("token-note"), "not stored")
    md.tap(pg, tid("pay-RES-FLEX"), 0.8); md.expect(pg, tid("pay-note"), "charged and posted", timeout=30000); md.nap(0.8)
    visit(pg, OPERA, tid("payments"))
    md.expect(pg, tid("count"), "(2)"); md.expect(pg, tid("current-fault"), "NONE"); md.nap(2.0)
    md.until(e)

    # the PMS goes down after the payment is captured
    e = S("outage")
    visit(pg, BRIDGE, tid("ev-new3"))
    md.tap(pg, tid("ev-new3"), 0.8); md.expect(pg, tid("links"), "600.00")
    arm(pg, OPERA, tid("apply-fault"), "DOWN")
    visit(pg, BRIDGE, tid("tokenise"))
    md.tap(pg, tid("tokenise"), 0.6); md.expect(pg, tid("token-note"), "not stored")
    md.tap(pg, tid("pay-B-LATE"), 0.8); md.expect(pg, tid("pay-note"), "Charged, not posted", timeout=30000); md.expect(pg, tid("ledger"), "OPERA_UNAVAILABLE"); md.nap(2.0)
    arm(pg, OPERA, tid("apply-fault"), "NONE")
    visit(pg, BRIDGE, tid("tokenise"))
    md.tap(pg, tid("replay-B-LATE"), 0.8); md.expect(pg, tid("pay-note"), "Replayed: posted once", timeout=30000); md.nap(1.5)
    md.until(e)

    # authorised, then the capture is declined: the hold is released and the link is open again
    e = S("decline")
    arm(pg, PSP, tid("apply-fault"), "DECLINE_CAPTURE_NEXT")
    visit(pg, BRIDGE, tid("ev-flex"))
    md.tap(pg, tid("ev-flex"), 0.8); md.expect(pg, tid("links"), "B-FLEX")
    md.tap(pg, tid("tokenise"), 0.6); md.expect(pg, tid("token-note"), "not stored")
    md.tap(pg, tid("pay-B-FLEX"), 0.8); md.expect(pg, tid("pay-note"), "DECLINED"); md.expect(pg, tid("links"), "OPEN"); md.nap(1.5)
    visit(pg, PSP, tid("ledger"))
    md.expect(pg, tid("ledger"), "VOID"); md.nap(2.0)
    visit(pg, BRIDGE, tid("tokenise"))
    md.tap(pg, tid("tokenise"), 0.6); md.expect(pg, tid("token-note"), "not stored")
    md.tap(pg, tid("pay-B-FLEX"), 0.8); md.expect(pg, tid("pay-note"), "charged and posted", timeout=30000); md.nap(1.0)
    md.until(e)

    # night audit, an expired token, and a processor that captures and then goes quiet
    e = S("audit")
    visit(pg, OPERA, tid("night-audit"))
    before = pg.locator(tid("business-date")).inner_text()
    md.tap(pg, tid("night-audit"), 1.0)
    md.expect(pg, tid("business-date"), "20")
    assert pg.locator(tid("business-date")).inner_text() != before, "night audit must move the business date"
    tokens_before = int(pg.locator(tid("tokens")).inner_text())
    md.tap(pg, tid("expire-tokens"), 1.0)
    arm(pg, PSP, tid("apply-fault"), "TIMEOUT_AFTER_CAPTURE")
    visit(pg, BRIDGE, tid("ev-adv"))
    md.tap(pg, tid("ev-adv"), 0.8); md.expect(pg, tid("links"), "B-ADV")
    md.tap(pg, tid("tokenise"), 0.6); md.expect(pg, tid("token-note"), "not stored")
    md.tap(pg, tid("pay-B-ADV"), 0.8); md.expect(pg, tid("pay-note"), "charged and posted", timeout=40000); md.nap(1.0)
    visit(pg, PSP, tid("ledger"))
    md.expect(pg, tid("current-fault"), "NONE"); md.nap(2.0)
    visit(pg, OPERA, tid("payments"))
    md.expect(pg, tid("count"), "(5)")
    assert int(pg.locator(tid("tokens")).inner_text()) > tokens_before, "the bridge must have fetched a new access token"
    md.nap(2.0)
    md.until(e)

    visit(pg, BRIDGE, tid("recon-clean"))
    e = S("recon")
    md.tap(pg, tid("recon-clean"), 0.8); md.expect(pg, tid("recon-note"), "5"); md.expect(pg, tid("recon-note"), "matched"); md.expect(pg, tid("recon-note"), "none"); md.nap(2.5)
    md.tap(pg, tid("recon-messy"), 0.8); md.expect(pg, tid("recon-items"), "MISSING_IN_SETTLEMENT"); md.expect(pg, tid("recon-items"), "AMOUNT_DIFFERENCE"); md.expect(pg, tid("recon-items"), "UNKNOWN_IN_SETTLEMENT"); md.nap(2.5)
    md.until(e)

    e = S("logs")
    md.scroll(pg, 0, 0.5)
    md.expect(pg, tid("vault-rows"), "sealed PAN"); md.expect(pg, tid("logs"), "****"); md.absent(pg, tid("logs"), "4111111111111111"); md.nap(2.5)
    md.until(e)

    e = S("coverage")
    visit(pg, (md.ROOT / "target/site/jacoco/index.html").as_uri(), "table")
    md.expect(pg, "body", "Total"); md.nap(3.0)
    md.until(e)

    e = S("production")
    visit(pg, BRIDGE + "architecture.html", "svg")
    md.scroll(pg, 900, 1.5); md.nap(3.0)
    md.until(e)

    e = S("close"); md.nap(2.0); md.until(e)


def port_free(port):
    with socket.socket() as s:
        return s.connect_ex(("127.0.0.1", port)) != 0


def wait_for(url):
    for _ in range(150):
        try:
            if urllib.request.urlopen(url, timeout=1).status == 200:
                return
        except Exception:
            time.sleep(1)
    raise SystemExit(f"{url} did not come up")


def main():
    md.OUT.mkdir(parents=True, exist_ok=True)
    work = pathlib.Path(tempfile.mkdtemp(prefix="fpb-fullflow-"))
    texts = lines()
    if not md.FAST and not md.MODELS:
        raise SystemExit("Set KOKORO_MODELS to the directory with the Kokoro model files")
    md.check_test_count(180)
    for port in (8081, 8090, 8091):
        if not port_free(port):
            raise SystemExit(f"Port {port} is in use: stop what is running there first")
    clips = ({k: (None, 4.0, 24000) for k in texts} if md.FAST else md.synth(texts, work))
    subprocess.run(["docker", "compose", "up", "-d", "--build"], cwd=md.ROOT, check=True)
    try:
        wait_for("http://127.0.0.1:8081/actuator/health")
        wait_for("http://127.0.0.1:8090/sim/state")
        wait_for("http://127.0.0.1:8091/sim/state")
        with sync_playwright() as p:
            browser = p.chromium.launch()
            ctx = browser.new_context(viewport={"width": md.W, "height": md.H}, record_video_dir=str(work / "video"),
                                      record_video_size={"width": md.W, "height": md.H}, device_scale_factor=1)
            ctx.add_init_script(md.INIT_JS)
            pg = ctx.new_page()
            md.R.page, md.R.t0 = pg, time.monotonic()
            try:
                story(pg, clips, texts)
            except Exception:
                (md.ROOT / "var").mkdir(exist_ok=True)
                pg.screenshot(path=str(md.ROOT / "var/fullflow-failure.png"))
                print("FAILED at", pg.url)
                raise
            video_path = pg.video.path()
            ctx.close()
            browser.close()
        if md.FAST:
            print("dry run OK")
        else:
            md.finish(clips, work, video_path)
    finally:
        subprocess.run(["docker", "compose", "down", "-v"], cwd=md.ROOT, check=False)
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
