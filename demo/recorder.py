"""The recorder behind the narrated video (demo/make_fullflow_demo.py): local Kokoro or macOS voice, the overlay drawn into every page
(demo/overlay.js: a top bar naming the system and the chapter, a caption band, a cursor with click ripples, title and proof cards), so all of
it is burned into the video; assertions that each narrated claim is really on the screen, in view and not covered, and that no text at
the end of a scene runs out of its box; and the final mix to MP4 with .vtt and .srt captions
and a chapter list. Needs ffmpeg and a Playwright Chromium; voice comes from Kokoro models or macOS `say`.
DEMO_FAST=1 does a dry run: no voice, short waits, no output, every assertion still checked. DEMO_SHOTS=<dir> saves a still per scene."""
import json
import os
import pathlib
import re
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
SHOTS = os.environ.get("DEMO_SHOTS", "")  # a folder: a screenshot at the end of every scene (works in a dry run)

# The voice reads these spellings; captions keep the written form.
SPOKEN = {"PMS": "P M S", "KMS": "K M S", "AWS": "A W S", "PCI": "P C I", "UTC": "U T C", "OPERA": "Opera", "API": "A P I", "3DS": "three D S",
          "OHIP": "O H I P", "HTTP": "H T T P", "RDS": "R D S", "3-D Secure": "three D secure", "OAuth2": "O auth two"}

INIT_JS = (pathlib.Path(__file__).resolve().parent / "overlay.js").read_text(encoding="utf-8")
CAPTION_WIDTH = 80  # characters on the caption band's one line; problems() fails a scene whose caption is wider than the screen


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
        self.shown = None    # what the overlay last showed; None after a navigation, so the next pump redraws it
        self.chapter = None  # (number, total, title) for the overlay's top bar

    def now(self):
        return time.monotonic() - self.t0


R = Rec()


def pump():
    """Show the caption chunk that belongs to the current time, and the chapter, on whatever page is open."""
    if R.page is None or R.t0 is None:
        return
    t = R.now()
    cur = next((c for c in R.chunks if c[0] <= t < c[1]), None)
    state = {"caption": cur[2] if cur else "", "chapter": list(R.chapter) if R.chapter else None}
    if state != R.shown:
        R.shown = state
        try:
            R.page.evaluate("(s) => window.__demo && window.__demo.set(s)", state)
        except Exception:
            pass


def chapter(number, total, title):
    """Name the scene in the top bar (the bar also names the system on screen, from the page's address)."""
    R.chapter = (number, total, title)
    pump()


def overlay(pg, method, *args):
    """Call the overlay (demo/overlay.js) on the open page: cards, step highlights, click ripples."""
    pg.evaluate("([m, a]) => window.__demo && window.__demo[m](...a)", [method, list(args)])


def cue(clips, texts, cid, phrase):
    """When the voice reaches a phrase of the scene being spoken: the proportional timing the captions use."""
    start = next(s for c, s, _e, _t in reversed(R.timeline) if c == cid)
    return start + clips[cid][1] * texts[cid].index(phrase) / len(texts[cid])


def at(t):
    """Wait until a cue."""
    d = t - R.now()
    if d > 0:
        nap(d)


def chunk_text(text, width=CAPTION_WIDTH):
    """One line per caption: the band below the page has room for one, so a caption never covers the page."""
    words, rows, cur = text.split(), [], ""
    for w in words:
        if len(cur) + len(w) + 1 > width:
            rows.append(cur)
            cur = w
        else:
            cur = (cur + " " + w).strip()
    rows.append(cur)
    return rows


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
    if R.page is not None:  # the end of a scene: nothing on screen may be cut off or spill out of its box
        problems = R.page.evaluate("() => window.__demo ? window.__demo.problems() : []")
        assert not problems, f"scene {R.timeline[-1][0]}: text a viewer cannot read whole: {problems[:6]}"
    if SHOTS and R.page is not None:  # one still per scene, to review the look without recording a video
        pathlib.Path(SHOTS).mkdir(parents=True, exist_ok=True)
        R.page.screenshot(path=str(pathlib.Path(SHOTS) / f"{len(R.timeline):02d}-{R.timeline[-1][0]}.png"))
    d = t_end + pad - R.now()
    if d > 0 and not FAST:
        nap(d)


def synth(texts, folder):
    # The video's voice is Kokoro (af_heart). The macOS voice is a stand-in for a machine without the model files, and only when asked for by
    # name (DEMO_MACOS_VOICE=Samantha): a silent fallback once replaced the published voice without anyone noticing.
    k = None
    macos_voice = os.environ.get("DEMO_MACOS_VOICE")
    if MODELS and os.path.isfile(os.path.join(MODELS, "kokoro-v1.0.onnx")):
        from kokoro_onnx import Kokoro
        k = Kokoro(os.path.join(MODELS, "kokoro-v1.0.onnx"), os.path.join(MODELS, "voices-v1.0.bin"))
        print(f"voice: Kokoro {VOICE} (24 kHz)", flush=True)
    elif macos_voice and sys.platform == "darwin" and shutil.which("say"):
        print(f"voice: macOS {macos_voice} (22.05 kHz), not the published voice", flush=True)
    else:
        raise SystemExit("Set KOKORO_MODELS to the directory with kokoro-v1.0.onnx and voices-v1.0.bin (or DEMO_MACOS_VOICE=Samantha for a stand-in)")
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
            subprocess.run(["say", "-v", macos_voice, "-r",
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
    overlay(pg, "cursor", box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    nap(0.5)


def tap(pg, loc, pause=0.5):
    loc = pg.locator(loc).first if isinstance(loc, str) else loc
    glide(pg, loc)
    overlay(pg, "ripple")
    loc.click()
    nap(pause)


def settle(pg):
    """Wait until the page has stopped scrolling. A dry run's short pauses once checked the screen in the middle of a smooth scroll, and
    passed what the finished scroll then hid under the top bar (agent log 69)."""
    pg.evaluate("""() => new Promise(done => {
      let y = scrollY, still = 0;
      const look = () => { if (scrollY === y) { still += 1; } else { still = 0; y = scrollY; } if (still > 6) { done(); } else { requestAnimationFrame(look); } };
      requestAnimationFrame(look);
    })""")


def reveal(pg, selector, pause=1.0):
    """Scroll a part of the page to just under the overlay's top bar."""
    pg.evaluate("(s) => document.querySelector(s).scrollIntoView({behavior: 'smooth', block: 'start'})", selector)
    settle(pg)
    nap(pause)


def scroll(pg, y, pause=0.8):
    pg.evaluate("(y) => window.scrollTo({top: y, behavior: 'smooth'})", y)
    settle(pg)
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
    """The narration must match the screen: the text is on the page and a viewer can read it, between the top bar and the caption band,
    not scrolled out of its box, not faded, not under a card. Fails loudly if it is not."""
    try:
        pg.wait_for_function("([s,t]) => window.__demo && window.__demo.visible(s, t) === ''", arg=[sel, text], timeout=timeout)
    except Exception:
        why = pg.evaluate("([s,t]) => window.__demo ? window.__demo.visible(s, t) : 'has no overlay to judge it'", [sel, text])
        raise AssertionError(f"narration expects '{text}' in {sel} on screen, but it {why}; the element has: {pg.locator(sel).inner_text()[:200]}")


def bring(pg, sel, text, pause=0.8):
    """Scroll a line the voice talks about into view, inside a scrolling box too (the operator log keeps its last forty lines)."""
    assert pg.evaluate("([s,t]) => window.__demo.bring(s, t)", [sel, text]), f"'{text}' is not in {sel}"
    settle(pg)
    nap(pause)


def present(pg, sel, text, timeout=15000):
    """Text is on the page, in view or not. For a cause the voice does not point at (a planted fault used up), and for text that arrives
    on its own (a webhook): a page that grows after it was scrolled leaves new rows below the band, so the script waits for them, then
    scrolls, then expect() proves they are in view."""
    pg.wait_for_function("([s,t]) => (document.querySelector(s)||{textContent:''}).textContent.includes(t)", arg=[sel, text], timeout=timeout)


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
    check_output(mp4, expected_rate=sr, expected_seconds=total)
    print("done", mp4, round(total), "s")


KOKORO_RATE = 24000


# No scene holds one picture this long: the longest, the production chapter, changes its highlights within a minute.
STILLEST = 75


def check_output(mp4, expected_rate=KOKORO_RATE, expected_seconds=None):
    """Reads the finished file back with ffprobe and refuses it unless its audio is what the published video must be: Kokoro's 24 kHz
    (a stand-in voice only when DEMO_MACOS_VOICE asked for it by name) and the length of the recording. Run it on any file before it is
    uploaded: `python demo/recorder.py --check demo/out/<name>.mp4` (agent log 42: a 22.05 kHz fallback was once published unnoticed)."""
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate:format=duration",
                            "-of", "json", str(mp4)], capture_output=True, text=True, check=True)
    info = json.loads(probe.stdout)
    streams = info.get("streams") or []
    if not streams:
        raise SystemExit(f"{mp4}: no audio stream")
    rate, seconds = int(streams[0]["sample_rate"]), float(info["format"]["duration"])
    if rate != expected_rate:
        raise SystemExit(f"{mp4}: audio is {rate} Hz, expected {expected_rate} Hz: not the published Kokoro voice")
    if expected_rate != KOKORO_RATE and not os.environ.get("DEMO_MACOS_VOICE"):
        raise SystemExit(f"{mp4}: a {rate} Hz stand-in voice that nobody asked for by name")
    if expected_seconds is not None and abs(seconds - expected_seconds) > 1.0:
        raise SystemExit(f"{mp4}: {seconds:.1f} s long, the recording was {expected_seconds:.1f} s")
    frozen = still_picture(mp4)
    if frozen:
        start, length = frozen
        raise SystemExit(f"{mp4}: the picture stands still for {length:.0f} s from {start:.0f} s while the voice goes on: the machine slept or "
                         "the browser stalled during the recording; record again (with `caffeinate -i` on macOS)")
    print(f"checked {mp4}: {rate} Hz, {seconds:.1f} s, the picture moves throughout", flush=True)


def still_picture(mp4, longest=STILLEST):
    """The first stretch where the picture stands still for longer than any scene does, as (start, length), or None. The audio check
    passed a video whose picture lagged its voice by four minutes: the Mac slept mid-recording, and the browser's clock counted the sleep
    while the narration's did not (agent log 66). A click ripple or a caption fade is noise, not motion (n=0.01)."""
    run = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(mp4), "-vf", f"freezedetect=n=0.01:d={longest}", "-map", "0:v",
                          "-f", "null", "-"], capture_output=True, text=True, check=True)
    starts = re.findall(r"freeze_start: ([\d.]+)", run.stderr)
    lengths = re.findall(r"freeze_duration: ([\d.]+)", run.stderr)
    if not starts:
        return None
    return float(starts[0]), float(lengths[0]) if lengths else float("inf")


if __name__ == "__main__" and sys.argv[1:2] == ["--check"]:
    for path in sys.argv[2:]:
        check_output(path)
