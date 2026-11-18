"""The recorder behind the narrated video (demo/make_fullflow_demo.py): local Kokoro or macOS voice, a visible cursor, captions drawn into the page
(so they are burned into the video), assertions that each narrated claim is really on the screen, and the final mix to MP4 with .vtt and
.srt captions and a chapter list. Needs ffmpeg and a Playwright Chromium; voice comes from Kokoro models or macOS `say`.
DEMO_FAST=1 does a dry run: no voice, short waits, no output, every assertion still checked."""
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
NAME = "folio-pay-bridge-fullflow-demo"
VOICE, SPEED = os.environ.get("KOKORO_VOICE", "af_heart"), float(os.environ.get("KOKORO_SPEED", "1.0"))
MODELS = os.path.expanduser(os.environ.get("KOKORO_MODELS", ""))  # directory with kokoro-v1.0.onnx and voices-v1.0.bin
W, H = 1280, 720
FAST = bool(int(os.environ.get("DEMO_FAST", "0")))

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
    k = None
    if MODELS:
        from kokoro_onnx import Kokoro
        k = Kokoro(os.path.join(MODELS, "kokoro-v1.0.onnx"), os.path.join(MODELS, "voices-v1.0.bin"))
    elif sys.platform != "darwin" or shutil.which("say") is None:
        raise SystemExit("Set KOKORO_MODELS, or run on macOS with the 'say' command available")
    clips = {}
    for cid, text in texts.items():
        spoken = text
        for a, b in SPOKEN.items():
            spoken = spoken.replace(a, b)
        if k is not None:
            audio, sr = k.create(spoken, voice=VOICE, speed=SPEED, lang="en-us")
        else:
            source = folder / f"{cid}.txt"
            output = folder / f"{cid}.aiff"
            source.write_text(spoken, encoding="utf-8")
            subprocess.run(["say", "-v", os.environ.get("DEMO_MACOS_VOICE", "Samantha"), "-r",
                            os.environ.get("DEMO_MACOS_RATE", "178"), "-f", str(source), "-o", str(output)], check=True)
            audio, sr = sf.read(str(output), dtype="float32")
        if audio.size == 0:
            raise RuntimeError(f"Voice synthesis produced no audio for scene {cid}")
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


def check_test_count(minimum=170):
    """The narration states a test count: refuse to record unless the last test run really had at least that many."""
    import glob
    import xml.etree.ElementTree as ET
    total = sum(int(ET.parse(f).getroot().get("tests", 0)) for f in glob.glob(str(ROOT / "target/surefire-reports/TEST-*.xml")))
    if total < minimum:
        raise SystemExit(f"Run ./mvnw test first: the narration claims over {minimum} tests and the last run had {total}")
