#!/usr/bin/env python3
"""Make demo/out/who-is-who.gif: the who-is-who page stepping through one card payment, recorded in a real browser.

Run: uv run --project demo python demo/make_who_gif.py
Needs nothing running: the page is static, so it is served straight from src/main/resources/static. Every scene is checked against the
page's own panel before the GIF is written, and a script error on the page refuses it.
"""
import functools
import http.server
import pathlib
import shutil
import subprocess
import tempfile
import threading
import time

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
STATIC = ROOT / "src/main/resources/static"
OUT = ROOT / "demo/out/who-is-who.gif"
# (state, seconds on screen, what the panel's title must say): the overview, one party, then the nine steps. The two round trips to the
# issuer get the time to come back.
SCENES = [("", 3.0, "One payment, from booking to the books"), ("actor:issuer", 3.2, "Issuer: the guest's bank"),
          ("book", 3.4, "A guest books a stay"), ("link", 2.8, "The bridge asks the guest to pay"), ("card", 5.0, "The guest enters the card"),
          ("auth", 5.6, "Authorisation"), ("capture", 5.4, "Capture"), ("post", 2.8, "The payment goes on the guest's bill"),
          ("tell", 3.0, "The hotel's systems are told"), ("settle", 5.2, "Settlement"), ("reconcile", 4.0, "Finance checks that it all agrees")]


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main():
    work = pathlib.Path(tempfile.mkdtemp(prefix="who-gif-"))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Quiet, directory=str(STATIC)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch()
            size = {"width": 1280, "height": 674}
            ctx = browser.new_context(viewport=size, record_video_dir=str(work), record_video_size=size, color_scheme="light")
            page = ctx.new_page()
            started = time.monotonic()
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(f"http://127.0.0.1:{server.server_address[1]}/who-is-who.html")
            page.wait_for_selector("[data-testid=actor-issuer]")
            lead = time.monotonic() - started
            for state, seconds, title in SCENES:
                page.evaluate("s => { if (s) document.body.dataset.step = s; else delete document.body.dataset.step; }", state)
                page.wait_for_timeout(200)
                shown = page.locator("[data-testid=panel-title]").inner_text()
                assert title in shown, f"scene {state or 'overview'}: the panel says {shown!r}, expected {title!r}"
                page.wait_for_timeout(seconds * 1000 - 200)
            total = sum(s for _, s, _ in SCENES)
            video = page.video.path()
            ctx.close()
            browser.close()
        assert not errors, f"the page had script errors: {errors}"
        # few colours and no dithering: the page is flat colour, so the frames compress to what changed (the packet, a highlight)
        palette = "fps=6,scale=800:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", f"{lead:.2f}", "-t", f"{total:.2f}", "-i", str(video), "-vf", palette,
                        "-loop", "0", str(OUT)], check=True)
        print(f"wrote {OUT.relative_to(ROOT)}: {total:.0f} s, {OUT.stat().st_size / 1e6:.1f} MB")
    finally:
        server.shutdown()
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
