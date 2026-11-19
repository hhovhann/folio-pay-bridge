#!/usr/bin/env python3
"""Record the FULL-FLOW demo: the bridge, an OPERA-style PMS simulator and a card-processor simulator as separate containers over real HTTP.

Run: JAVA_HOME=<jdk17> KOKORO_MODELS=<dir> uv run --project demo python demo/make_fullflow_demo.py
Needs Docker running, ports 8081, 8090 and 8091 free, and everything recorder.py needs.
DEMO_FAST=1 does a dry run (no voice, short waits, no output): every narrated claim is still asserted against the screen.
"""
import csv
import glob
import os
import pathlib
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import recorder as md  # noqa: E402  (the recorder, captions, voice and the screen assertions)

from playwright.sync_api import sync_playwright  # noqa: E402

md.NAME = "folio-pay-bridge-fullflow-demo"
BRIDGE, OPERA, PSP = "http://127.0.0.1:8081/", "http://127.0.0.1:8090/sim/opera.html", "http://127.0.0.1:8091/sim/psp.html"
tid = md.tid


CHAPTERS = ["intro", "problem", "how", "pms", "link", "pay", "folio", "webhook", "repeat", "lost", "outage", "decline", "audit", "recon", "logs", "coverage",
            "production", "close"]
TITLES = {"intro": "Introduction", "problem": "The problem it solves", "how": "How it works", "pms": "Happy path · a booking event", "link": "Happy path · the guest pays",
          "pay": "Happy path · authorise and capture", "folio": "Happy path · posted to the folio", "webhook": "Happy path · signed webhooks",
          "repeat": "Edge case 1 · the same booking again", "lost": "Edge case 2 · a lost reply", "outage": "Edge case 3 · the PMS goes down",
          "decline": "Edge case 4 · a declined capture", "audit": "Edge case 5 · night audit and an expired token", "recon": "Reconciliation",
          "logs": "Card data stays sealed", "coverage": "The proof", "production": "Production on AWS", "close": "Summary"}
CASES = ["idempotent charges", "a repeated event", "a lost reply", "an outage and a replay", "a released hold", "a token refresh", "reconciliation"]
REPO = "github.com/hhovhann/folio-pay-bridge"
# (problem, why it happens, what the bridge does, where the video shows it); each problem is named by the voice, in this order
PROBLEMS = [("Charged twice", "a retry after a timeout", "Idempotency keys", "the same key is the same payment"),
            ("Posted twice", "the PMS answered too late", "Look before posting again", "edge case two"),
            ("Charged, never posted", "the PMS was down", "Dead-letter queue and replay", "edge case three"),
            ("Card numbers leak", "into logs, rows and replies", "Tokenised on arrival", "encrypted, never logged"),
            ("Books do not match", "captures vs settlement", "Reconciliation", "every exception has a name")]


def lines():
    return {
        "intro": "This is the full flow, not a mock-up, and it is explained step by step. Four containers talk over real network calls: the payment bridge I built, a database, a simulator of a hotel PMS in the style of the Oracle Hospitality Integration Platform, and a simulator of a card processor. Everything is synthetic. First, the problem it solves.",
        "problem": "Why it exists. One hotel payment has to agree across three systems: the PMS, the card processor and the guest's card. Things go wrong in five ways. A retry can charge a card twice. A reply that arrives too late can post a payment twice. An outage can leave money charged but never on the folio. Card numbers can leak into logs. And the books may not match what the processor settles. The bridge answers each one, and the video shows every answer working.",
        "how": "Here is the picture. A booking reaches the bridge as an event. The bridge applies a payment rule and creates a payment link. The guest pays, and the card is tokenised the moment it arrives: only a token comes back, and only ciphertext is stored. The bridge asks the processor to authorise and capture, then posts the payment to the guest's folio in the PMS, and tells the hotel's own systems with a signed webhook. Every step carries an idempotency key, and every failure ends in one of two places: posted, or kept in a dead letter queue with the reason. Now let me show it, and then break it on purpose.",
        "pms": "First, the happy path. The PMS front desk takes a new reservation and announces it to the bridge. Here the simulator posts the event over HTTP; real OPERA Cloud would deliver it on OHIP's streaming API. The bridge answers with the payment rule: advance purchase, the full amount within twenty four hours. Note the business date at the top. In a real property it moves only at night audit, not at midnight.",
        "link": "In the bridge, a payment request now exists. The guest tokenises a test card and pays. The card number is encrypted on arrival, and the guest sees only a token.",
        "pay": "On the processor side there is an authorisation and a capture, and only the last four digits of the card. The processor deduplicates on an idempotency key, so a repeat can never charge twice.",
        "folio": "And at the front desk, the payment is on the guest's folio. I open the reservation: folio window one, a payment code, a cashier, eight hundred dollars, our reference and the processor's reference. It is a credit, posted as a payment, not as a charge, so the folio shows the stay paid in advance.",
        "webhook": "The bridge also tells the hotel's own systems what happened. Payment captured, and payment posted, each arrive as a signed webhook. The receiving side checks the signature before it trusts the message, and every event has an id, so a repeat is ignored. The card number is never in it.",
        "repeat": "Edge case one: the same booking event arrives again after the guest has paid. The bridge does not create a new request, and does not ask for money twice. It flags the booking for a refund or balance review, because a changed total after payment needs a human.",
        "lost": "Edge case two: a lost reply. This PMS does not deduplicate, like the real world. I make it record the next payment and then answer too late. The bridge times out, but before it retries it looks for its own reference, finds the payment, and does not post twice. One new line on the folio, not two.",
        "outage": "Edge case three: the PMS goes down after the card is charged. The bridge says so plainly and keeps the payment in a dead letter queue with the reason. The money is not lost, and it is not hidden. When the PMS is back, one click replays it, and it is posted exactly once.",
        "decline": "Edge case four: the processor approves the authorisation, then declines the capture. The bridge does not leave the hold on the card. It releases it, and you can see the void in the processor ledger. The payment link goes back to open, so the guest can try again, and this time it works.",
        "audit": "Edge case five: night audit runs and the business date moves, and I also expire the access token. The next payment makes the processor capture the money and then go quiet. The bridge fetches a new token, reads the new business date from the PMS, retries with the same key, and the processor charges once.",
        "recon": "At the end of the day, finance reconciles. The bridge downloads the processor's own settlement file and matches every capture against what was posted. Five captures, five matches. Now a messy statement: one capture missing, one settled short, one we never made. Each exception has a name finance can act on.",
        "logs": "The operator log shows all of this with the card number masked. A mask catches mistakes, but the code never logs the number in the first place, and the vault view shows ciphertext, never the number.",
        "coverage": "How do I know the other cases hold? Over four hundred and fifty automated tests, and a coverage report above ninety nine percent of lines. The important tests were checked the hard way: I put each bug back on purpose, over sixty of them, and every one made its test fail. A page in the repository lists every edge case and the test that fails if it breaks, and also what is not covered.",
        "production": "How it should work in production. On AWS, the bridge runs as two or more tasks in private subnets, behind a load balancer and a web application firewall, with PostgreSQL on RDS, keys in KMS, and fixed outbound addresses for OPERA Cloud and the acquirer. The runbook is written, but not yet run in an account. Booking events would come from OHIP's streaming API, which delivers at least once, with no acknowledgement and no dead letter queue, so the bridge would store its offset and skip an event it has already seen. The processor becomes the acquirer's checkout, with 3-D Secure and hosted card entry, so a guest's number never reaches this service. Authentication is already in: OAuth2 tokens or hashed API keys, each limited by scope. Still to add: tenants for many operators and properties, a sweeper for crashes between steps, and chargeback cases with scheme deadlines.",
        "close": "That is the full flow: " + ", ".join(CASES[:-1]) + ", and " + CASES[-1] + ", against separate systems. The PMS here is a simulator I wrote in the shape of the Oracle Hospitality Integration Platform. It is not Oracle software, and the real thing needs Oracle's own sandbox, which is the next step. The code, the tests and the log of what the agents got wrong are in the public repository.",
    }


def proof():
    """The numbers the coverage scene shows, read from the build's own reports (and the README's mutation tally), never typed in."""
    reports = [ET.parse(f).getroot() for f in glob.glob(str(md.ROOT / "target/surefire-reports/TEST-*.xml"))]
    tests = sum(int(r.get("tests", 0)) for r in reports)
    failed = sum(int(r.get("failures", 0)) + int(r.get("errors", 0)) for r in reports)
    skipped = sum(int(r.get("skipped", 0)) for r in reports)
    with open(md.ROOT / "target/site/jacoco/jacoco.csv", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    covered = {k: sum(int(r[k + "_COVERED"]) for r in rows) / sum(int(r[k + "_COVERED"]) + int(r[k + "_MISSED"]) for r in rows) * 100 for k in ("LINE", "BRANCH")}
    tally = next(x for x in (md.ROOT / "README.md").read_text(encoding="utf-8").splitlines() if "mutation-checked" in x)
    pairs = [(int(a), int(b)) for a, b in re.findall(r"(\d+) of (\d+) in", tally)]
    caught, tried = sum(a for a, _ in pairs), sum(b for _, b in pairs)
    # what the voice says must be what the build measured
    assert failed == 0 and tests >= 450, f"the narration says over 450 tests, all passing: {tests} ran, {failed} failed"
    assert covered["LINE"] > 99.0, f"the narration says above 99% of lines: {covered['LINE']:.2f}"
    assert caught == tried and caught > 60, f"the narration says over sixty mutations, every one caught: {caught} of {tried}"
    return {"tests": tests, "testsNote": f"{tests - skipped} passed · {failed} failed", "lines": round(covered["LINE"], 1),
            "branches": round(covered["BRANCH"], 1), "mutations": caught}


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
    def S(cid):
        md.chapter(CHAPTERS.index(cid) + 1, len(CHAPTERS), TITLES[cid])
        return md.say(clips, texts, cid)

    def when(cid, phrase):
        md.at(md.cue(clips, texts, cid, phrase))

    post("http://127.0.0.1:8090/sim/reset")
    post("http://127.0.0.1:8091/sim/reset")

    visit(pg, BRIDGE, tid("ev-flex"))
    md.expect(pg, tid("actors"), "How it works")
    md.overlay(pg, "card", "title")
    e = S("intro")
    for n, phrase in enumerate(["the payment bridge I built", "a database", "a simulator of a hotel PMS", "a simulator of a card processor"], 1):
        when("intro", phrase)
        md.overlay(pg, "reveal", n)
    md.until(e)

    e = S("problem")
    md.overlay(pg, "card", "problems", {"rows": PROBLEMS})
    for n, phrase in enumerate(["charge a card twice", "post a payment twice", "charged but never on the folio", "Card numbers can leak", "the books may not match"], 1):
        when("problem", phrase)
        md.overlay(pg, "reveal", n)
    md.until(e)
    md.overlay(pg, "hideCard")
    md.nap(0.6)
    visit(pg, BRIDGE + "architecture.html", tid("flow"))

    e = S("how")
    for phrase, step in [("A booking reaches", "1"), ("The guest pays", "2"), ("only ciphertext is stored", "4"), ("The bridge asks the processor", "3"),
                         ("then posts the payment", "5"), ("and tells the hotel's own systems", "6"), ("Every step carries", "safety")]:
        when("how", phrase)
        md.overlay(pg, "step", step)
    when("how", "Now let me show it")
    md.overlay(pg, "step", "")
    md.until(e)

    visit(pg, OPERA, tid("send-adv"))
    e = S("pms")
    md.tap(pg, tid("send-adv"), 0.8); md.expect(pg, tid("booking-note"), "Advance purchase: full amount within 24 hours")
    when("pms", "Note the business date")
    md.glide(pg, pg.locator(tid("business-date")))
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
    md.expect(pg, tid("payments"), "RES-ADV"); md.expect(pg, tid("payments"), "800.00"); md.expect(pg, tid("payments"), "FPBRIDGE")
    when("folio", "I open the reservation")
    md.tap(pg, "[data-reservation=RES-ADV]", 0.8); md.expect(pg, tid("folio-title"), "RES-ADV")
    md.reveal(pg, tid("folio"))
    md.expect(pg, tid("folio-lines"), "800.00"); md.expect(pg, tid("folio-balance"), "-800.00"); md.nap(2.0)
    md.until(e)

    e = S("webhook")
    md.reveal(pg, tid("webhooks"))
    md.expect(pg, tid("webhooks"), "payment.captured", timeout=20000); md.expect(pg, tid("webhooks"), "folio.posted", timeout=20000); md.nap(2.5)
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
    md.reveal(pg, tid("payments"))
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
    md.reveal(pg, tid("payments"))
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

    numbers = proof()
    visit(pg, (md.ROOT / "target/site/jacoco/index.html").as_uri(), "table")
    e = S("coverage")
    md.expect(pg, "body", "Total")
    md.overlay(pg, "card", "proof", numbers, True)
    for phrase, cards in [("Over four hundred", [1]), ("above ninety nine percent", [2, 3]), ("I put each bug back", [4])]:
        when("coverage", phrase)
        for n in cards:
            md.overlay(pg, "reveal", n)
    md.until(e)

    visit(pg, BRIDGE + "architecture.html", tid("production"))
    e = S("production")
    md.reveal(pg, tid("production"))
    md.expect(pg, tid("production"), "NOT YET RUN IN AN ACCOUNT")
    when("production", "Booking events would come")
    md.reveal(pg, tid("streaming"))
    md.expect(pg, tid("streaming"), "at least once"); md.expect(pg, tid("streaming"), "No ACK, no dead-letter queue")
    md.expect(pg, tid("streaming"), "THE SIMULATOR POSTS EVENTS OVER HTTP")  # the pms scene says so; agent log 57
    when("production", "The processor becomes")
    md.reveal(pg, "table")
    md.until(e)

    md.overlay(pg, "card", "end", {"cases": CASES, "repo": REPO})
    e = S("close")
    for n, case in enumerate(CASES, 1):
        when("close", case)
        md.overlay(pg, "reveal", n)
    md.until(e)


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
    md.check_test_count(450)  # the narration says "over four hundred and fifty"
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
