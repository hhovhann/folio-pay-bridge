"""Builds the narrated demo video of Folio Pay Bridge: real app, real clicks, fictional data, local Kokoro voice.
Steps: build and serve the app -> synthesise every narration line (Kokoro af_heart) -> record the browser while the script acts and waits for the voice
(captions are drawn into the page, so they are burned into the video) -> mix the audio at the recorded offsets -> MP4 -> poster, .vtt, .srt.
Run: uv run python demo/make_demo.py      Needs: node/npm, ffmpeg, a Playwright Chromium in the cache, the Kokoro model files (KOKORO_MODELS).
DEMO_FAST=1 does a dry run (no voice, short waits, no output) to find broken steps quickly."""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

import numpy as np
import soundfile as sf
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "demo/out"
PORT = 8082
BASE = f"http://127.0.0.1:{PORT}"
NAME = "folio-pay-bridge-demo"
VOICE, SPEED = os.environ.get("KOKORO_VOICE", "af_heart"), float(os.environ.get("KOKORO_SPEED", "1.0"))
MODELS = os.path.expanduser(os.environ.get("KOKORO_MODELS", ""))  # directory with kokoro-v1.0.onnx and voices-v1.0.bin
W, H = 1280, 720
FAST = bool(int(os.environ.get("DEMO_FAST", "0")))
URL = f"{BASE}/"

# The voice reads these spellings; captions keep the written form.
SPOKEN = {"PMS": "P M S", "KMS": "K M S", "AWS": "A W S", "PCI": "P C I", "UTC": "U T C", "OPERA": "Opera", "API": "A P I", "3DS": "three D S"}

INIT_JS = """
(() => { const add = () => {
  if (document.getElementById('demo-cursor')) return;
  const d = document.createElement('div'); d.id = 'demo-cursor';
  d.style.cssText = 'position:fixed;z-index:99999;width:30px;height:30px;border-radius:50%;border:3px solid #e11d48;background:rgba(225,29,72,.22);pointer-events:none;left:-60px;top:-60px;transition:left .4s,top .4s';
  document.documentElement.appendChild(d);
  const c = document.createElement('div'); c.id = 'demo-caption';
  c.style.cssText = 'position:fixed;z-index:99998;left:0;right:0;margin:0 auto;width:max-content;bottom:22px;max-width:1060px;background:rgba(17,24,39,.88);color:#fff;font:600 21px/1.35 -apple-system,Segoe UI,Helvetica,Arial,sans-serif;padding:9px 18px;border-radius:10px;text-align:center;display:none;white-space:pre-line';
  document.documentElement.appendChild(c); };
  document.addEventListener('DOMContentLoaded', add); add(); })();
"""


def lines():
    """(id -> caption text). Each line is spoken once, when the matching scene calls say(id)."""
    return {
        "intro": "This is Folio Pay Bridge: a small proof of concept of a hotel payments slice, in Java seventeen with Spring Boot and Hibernate. It takes bookings from a property management system such as Oracle OPERA, sends payment requests, charges a virtual card, and posts the payment to the guest folio. The processor and the PMS are fakes, the data is synthetic, and this is not any company's software.",
        "booking": "Bookings arrive as events. An advance purchase booking asks for the full amount within twenty four hours. A flexible booking, thirty days out, asks for a twenty percent deposit, seven days before arrival. When the booking changes, the old request is superseded by a new one. The rules live in one small class that a product lead can read.",
        "vault": "Next, the card. The number is tokenised the moment it arrives. The database keeps only the ciphertext, a data key wrapped by a master key, and the first six and last four digits. In production the master key lives in AWS KMS, with the token as the encryption context. No card number is stored.",
        "pay": "Now the guest pays the advance purchase request, and double clicks. The card is charged once, the payment is posted to the folio once, and the counters, read from the fake processor and the fake PMS, say exactly that. The second click used the same idempotency key, so nothing happened twice.",
        "timeout": "Real networks fail in the worst way: the processor takes the money, and the response is lost. I arm that fault, and pay the flexible deposit. The service retries with the same key, the processor returns its original result, and the captured total rises by the deposit once, not twice.",
        "outage": "Now the PMS goes down. A late booking is paid. The card is charged, but the posting cannot be made. The tool does not hide it: the charge is captured, the posting waits in a dead letter state with a reason. When the PMS is back, one click replays it, and it is posted exactly once.",
        "logs": "The operator log shows all of this with the card number masked. A mask catches mistakes, but the code never logs the number in the first place, and the tests fail the build if a card number ever appears in a log line or in the database.",
        "recon": "At the end of the day, finance reconciles. A clean acquirer statement matches every capture. This messy one has a capture missing, an amount short by one dollar fifty, and a line we never captured. Each exception has a name that a finance team can act on.",
        "shadow": "Finally, shadow mode, which is how a rebuild is made safe. The same two hundred bookings run through the new service, and each result is compared with what the legacy system does. With the natural new rules, many bookings differ: tax rounded once on the total instead of every night, and the payment dated in UTC instead of the hotel's day. Those are decisions for the product team, not bugs by default. With legacy compatible rules, all two hundred match.",
        "close": "That is the slice: idempotent charges, lost responses, outages and replays, tokenisation with envelope encryption, masked logs, reconciliation, and shadow mode, with over one hundred and seventy automated tests behind it. The AWS key service is tested against a stub, not real AWS, and every external system here is a fake. I built it by directing Claude Code agents, reviewing and testing what they wrote, and having two other models review it independently for security and idempotency. The log of what the agents got wrong, and the review findings I fixed, is in the repository. I would be glad to talk about your first two services.",
    }


def nap(x):
    """Sleep in short slices so captions keep updating while the script waits."""
    end = time.monotonic() + x * (0.15 if FAST else 1)
    while time.monotonic() < end:
        pump()
        time.sleep(0.1)


class Rec:
    def __init__(self):
        self.timeline = []   # (id, start, end, text)
        self.chunks = []     # (start, end, text) caption chunks
        self.t0 = None
        self.page = None
        self.shown = None

    def now(self):
        return time.monotonic() - self.t0


R = Rec()


def pump():
    """Show the caption chunk that belongs to the current time."""
    if R.page is None or R.t0 is None:
        return
    t = R.now()
    cur = next((c for c in R.chunks if c[0] <= t < c[1]), None)
    text = cur[2] if cur else ""
    if text != R.shown:
        R.shown = text
        try:
            R.page.evaluate("(t) => { const c = document.getElementById('demo-caption'); if (c) { c.textContent = t; c.style.display = t ? 'block' : 'none'; } }", text)
        except Exception:
            pass


def chunk_text(text, width=62):
    words, rows, cur = text.split(), [], ""
    for w in words:
        if len(cur) + len(w) + 1 > width:
            rows.append(cur)
            cur = w
        else:
            cur = (cur + " " + w).strip()
    rows.append(cur)
    return ["\n".join(rows[i:i + 2]) for i in range(0, len(rows), 2)]


def say(clips, texts, cid):
    start = R.now()
    dur = clips[cid][1]
    R.timeline.append((cid, start, start + dur, texts[cid]))
    parts = chunk_text(texts[cid])
    total = sum(len(p) for p in parts)
    acc = start
    for p in parts:
        d = dur * len(p) / total
        R.chunks.append((acc, acc + d, p))
        acc += d
    pump()
    return start + dur


def until(t_end, pad=0.6):
    d = t_end + pad - R.now()
    if d > 0 and not FAST:
        nap(d)


def synth(texts, folder):
    from kokoro_onnx import Kokoro
    k = Kokoro(os.path.join(MODELS, "kokoro-v1.0.onnx"), os.path.join(MODELS, "voices-v1.0.bin"))
    clips = {}
    for cid, text in texts.items():
        spoken = text
        for a, b in SPOKEN.items():
            spoken = spoken.replace(a, b)
        audio, sr = k.create(spoken, voice=VOICE, speed=SPEED, lang="en-us")
        idx = np.where(np.abs(audio) > 0.01)[0]
        if len(idx):
            audio = audio[max(idx[0] - 800, 0): idx[-1] + 2400]
        p = str(folder / f"{cid}.wav")
        sf.write(p, audio, sr)
        clips[cid] = (p, len(audio) / sr, sr)
        print("voice", cid, round(len(audio) / sr, 1), "s", flush=True)
    return clips


# ---------------------------------------------------------------- browser helpers
def glide(pg, loc):
    loc.scroll_into_view_if_needed()
    box = loc.bounding_box()
    pg.evaluate("([x,y]) => { const c=document.getElementById('demo-cursor'); if(c){c.style.left=(x-15)+'px'; c.style.top=(y-15)+'px';} }", [box["x"] + box["width"] / 2, box["y"] + box["height"] / 2])
    nap(0.5)


def tap(pg, loc, pause=0.5):
    loc = pg.locator(loc).first if isinstance(loc, str) else loc
    glide(pg, loc)
    loc.click()
    nap(pause)


def scroll(pg, y, pause=0.8):
    pg.evaluate("(y) => window.scrollTo({top: y, behavior: 'smooth'})", y)
    nap(pause)


def pick(pg, selector, **kw):
    loc = pg.locator(selector).first
    glide(pg, loc)
    loc.select_option(**kw)
    nap(0.7)


def type_into(pg, loc, text, delay=45):
    loc = pg.locator(loc).first if isinstance(loc, str) else loc
    glide(pg, loc)
    loc.click()
    loc.press_sequentially(text, delay=0 if FAST else delay)
    nap(0.5)


# ---------------------------------------------------------------- the story
def expect(pg, sel, text, timeout=15000):
    """The narration must match the screen: fail loudly if it does not."""
    try:
        pg.wait_for_function("([s,t]) => (document.querySelector(s)||{textContent:''}).textContent.includes(t)", arg=[sel, text], timeout=timeout)
    except Exception:
        raise AssertionError(f"narration expects '{text}' in {sel}, screen has: {pg.locator(sel).inner_text()[:300]}")


def absent(pg, sel, text):
    got = pg.locator(sel).inner_text()
    assert text not in got, f"'{text}' must not appear in {sel}"


def tid(x): return f"[data-testid={x}]"


def api_reset():
    req = urllib.request.Request(BASE + "/api/sim", data=b'{"reset":true}', headers={"Content-Type": "application/json"}, method="POST")
    urllib.request.urlopen(req, timeout=5).read()


def run_story(pg, clips, texts):
    S = lambda cid: say(clips, texts, cid)

    api_reset()
    pg.goto(URL); pg.wait_for_selector(tid("ev-flex")); nap(0.8)
    e = S("intro"); nap(2.0)
    until(e)

    e = S("booking")
    tap(pg, tid("ev-adv"), 0.6); expect(pg, tid("links"), "800.00"); nap(1.0)
    tap(pg, tid("ev-flex"), 0.6); expect(pg, tid("links"), "200.00"); nap(1.2)
    tap(pg, tid("ev-mod"), 0.6); expect(pg, tid("links"), "SUPERSEDED"); expect(pg, tid("links"), "300.00"); nap(1.5)
    until(e)

    e = S("vault"); tap(pg, tid("tokenise"), 0.8); expect(pg, tid("token-note"), "not stored"); expect(pg, tid("vault-rows"), "sealed PAN"); nap(2.0)
    until(e)

    e = S("pay"); nap(1.0)
    loc = pg.locator(tid("pay-B-ADV")).first; glide(pg, loc); loc.dblclick(); nap(0.8)
    expect(pg, tid("pay-note"), "charged and posted"); expect(pg, "#c-psp", "1"); expect(pg, "#c-opera", "1"); expect(pg, "#c-amt", "800.00"); nap(1.5)
    until(e)

    e = S("timeout")
    pick(pg, "#psp", value="TIMEOUT_AFTER_CAPTURE"); tap(pg, tid("apply-faults"), 0.6); nap(0.8)
    tap(pg, tid("pay-B-FLEX"), 0.8); expect(pg, tid("pay-note"), "charged and posted")
    expect(pg, "#c-psp", "2"); expect(pg, "#c-amt", "1,100.00"); expect(pg, "#c-opera", "2"); nap(1.5)
    until(e)

    e = S("outage")
    pick(pg, "#psp", value="NONE"); pick(pg, "#opera", value="DOWN"); tap(pg, tid("apply-faults"), 0.6)
    tap(pg, tid("ev-new3"), 0.6); expect(pg, tid("links"), "600.00")
    tap(pg, tid("pay-B-LATE"), 0.8); expect(pg, tid("pay-note"), "Charged, not posted"); pg.wait_for_selector(tid("link-B-LATE-PAID")); expect(pg, tid("ledger-B-LATE"), "OPERA_UNAVAILABLE"); expect(pg, "#c-psp", "3"); nap(2.0)
    pick(pg, "#opera", value="NONE"); tap(pg, tid("apply-faults"), 0.6)
    tap(pg, tid("replay-B-LATE"), 0.8); expect(pg, tid("pay-note"), "posted once"); expect(pg, "#c-opera", "3"); nap(1.5)
    until(e)

    e = S("logs"); scroll(pg, 0, 0.3); nap(1.0)
    expect(pg, tid("logs"), "Pan[**** 1111]"); absent(pg, tid("logs"), "4111111111111111"); absent(pg, tid("logs"), "4111 1111 1111 1111")
    pg.evaluate("document.querySelector('[data-testid=logs]').scrollTo({top: 0})"); nap(2.0)
    pg.evaluate("document.querySelector('[data-testid=logs]').scrollTo({top: 99999, behavior: 'smooth'})"); nap(2.5)
    until(e)

    e = S("recon"); scroll(pg, 400, 0.8)
    tap(pg, tid("recon-clean"), 0.6); expect(pg, tid("recon-note"), "3 matched"); expect(pg, tid("recon-note"), "none"); nap(1.5)
    tap(pg, tid("recon-messy"), 0.6); expect(pg, tid("recon-items"), "MISSING_IN_SETTLEMENT"); expect(pg, tid("recon-items"), "AMOUNT_DIFFERENCE"); expect(pg, tid("recon-items"), "UNKNOWN_IN_SETTLEMENT"); nap(2.0)
    until(e)

    e = S("shadow")
    tap(pg, tid("shadow-strict"), 0.6); expect(pg, tid("shadow-note"), "differ"); expect(pg, tid("shadow-items"), "businessDate"); scroll(pg, 600, 3.0)
    tap(pg, tid("shadow-compat"), 0.6); expect(pg, tid("shadow-note"), "200 match"); expect(pg, tid("shadow-note"), "0 differ"); nap(2.5)
    until(e)

    scroll(pg, 0, 0.6)
    e = S("close"); nap(2.0)
    until(e, 1.5)


# ---------------------------------------------------------------- mux
def fmt_ts(t, sep):
    return f"{int(t // 3600):02d}:{int(t % 3600 // 60):02d}:{t % 60:06.3f}".replace(".", sep)


def finish(clips, work, video_path):
    total = max(c[1] for c in R.chunks) + 2.0
    sr = clips[next(iter(clips))][2]
    mix = np.zeros(int(total * sr) + sr, dtype="float32")
    for cid, start, _end, _text in R.timeline:
        a, s = sf.read(clips[cid][0], dtype="float32")
        i = int(start * sr)
        mix[i:i + len(a)] += a
    wav = work / "mix.wav"
    sf.write(str(wav), mix, sr)
    vtt, srt = ["WEBVTT", ""], []
    for n, (a, b, text) in enumerate(R.chunks, 1):
        vtt += [f"{fmt_ts(a, '.')} --> {fmt_ts(b, '.')}", text, ""]
        srt += [str(n), f"{fmt_ts(a, ',')} --> {fmt_ts(b, ',')}", text, ""]
    (OUT / f"{NAME}.vtt").write_text("\n".join(vtt), encoding="utf-8")
    (OUT / f"{NAME}.srt").write_text("\n".join(srt), encoding="utf-8")
    mp4 = OUT / f"{NAME}.mp4"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", video_path, "-i", str(wav), "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-crf", "23", "-preset", "slow", "-r", "30", "-c:a", "aac", "-b:a", "128k", "-t", f"{total:.2f}", "-movflags", "+faststart", str(mp4)], check=True)
    fit = next(s for cid, s, _e, _t in R.timeline if cid == "outage")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", f"{fit + 14:.1f}", "-i", str(mp4), "-frames:v", "1", str(OUT / f"{NAME}.png")], check=True)
    (OUT / f"{NAME}.chapters.json").write_text(json.dumps([{"id": c, "start": round(s, 1), "end": round(e, 1)} for c, s, e, _ in R.timeline], indent=1), encoding="utf-8")
    print("done", mp4, round(total), "s")


def wait_server():
    for _ in range(100):
        try:
            if urllib.request.urlopen(BASE + "/actuator/health", timeout=1).status == 200:
                return
        except Exception:
            time.sleep(0.2)
    raise SystemExit("server did not start")


def check_test_count(minimum=170):
    """The narration says "over one hundred and seventy tests": refuse to record unless the last test run really had that many."""
    import glob
    import xml.etree.ElementTree as ET
    total = sum(int(ET.parse(f).getroot().get("tests", 0)) for f in glob.glob(str(ROOT / "target/surefire-reports/TEST-*.xml")))
    if total < minimum:
        raise SystemExit(f"Run ./mvnw test first: the narration claims over {minimum} tests and the last run had {total}")


def check_port_free():
    import socket
    with socket.socket() as s:
        if s.connect_ex(("127.0.0.1", PORT)) == 0:
            raise SystemExit(f"Port {PORT} is already in use: stop what is running there, or the demo would drive someone else's app")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    work = pathlib.Path(tempfile.mkdtemp(prefix="fpb-demo-"))
    texts = lines()
    if not os.environ.get("JAVA_HOME"):
        raise SystemExit("Set JAVA_HOME to a JDK 17")
    if not FAST and not MODELS:
        raise SystemExit("Set KOKORO_MODELS to the directory with the Kokoro model files")
    env = dict(os.environ)
    check_test_count()
    check_port_free()
    subprocess.run(["./mvnw", "-q", "package", "-DskipTests"], cwd=ROOT, check=True, env=env)
    clips = ({k: (None, 4.0, 24000) for k in texts} if FAST else synth(texts, work))
    srv = subprocess.Popen([env["JAVA_HOME"] + "/bin/java", "-jar", str(ROOT / "target/folio-pay-bridge-0.1.0.jar"), f"--server.port={PORT}", "--spring.profiles.active=demo"], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
    try:
        wait_server()
        with sync_playwright() as p:
            browser = p.chromium.launch()
            ctx = browser.new_context(viewport={"width": W, "height": H}, record_video_dir=str(work / "video"), record_video_size={"width": W, "height": H},
                                      device_scale_factor=1)
            ctx.add_init_script(INIT_JS)
            pg = ctx.new_page()
            R.page, R.t0 = pg, time.monotonic()
            try:
                run_story(pg, clips, texts)
            except Exception:
                (ROOT / "var").mkdir(exist_ok=True)
                pg.screenshot(path=str(ROOT / "var/demo-failure.png"))
                print("FAILED at", pg.url)
                raise
            video_path = pg.video.path()
            ctx.close()
            browser.close()
        if FAST:
            print("dry run OK")
        else:
            finish(clips, work, video_path)
    finally:
        srv.terminate()
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
