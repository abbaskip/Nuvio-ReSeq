"""Exercise two persisted reminders and live programme boundaries in the signed app on a fresh guest."""
from collections import Counter
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
from threading import Thread
import time
from urllib.parse import urlsplit
import zipfile

from signed_rollback import PACKAGE, SERIAL, adb, cold, key, label, point, seed_settings, tree

OUT = Path("build/guide-delivery")
EVIDENCE = {}
REQUESTS = Counter()
PLAYLIST = "clock-qa"
CHANNEL = "Fixture Clock TV"
KEY = PLAYLIST + "|fixture-clock|" + CHANNEL
ORIGIN = "http://127.0.0.1:38791"


def record(key, value):
    EVIDENCE[key] = value
    (OUT / "results.json").write_text(json.dumps(EVIDENCE, indent=2))
    print(key, value, flush=True)


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        resource = urlsplit(self.path).path
        REQUESTS[resource] += 1
        if resource == "/clock.m3u":
            data = (f'#EXTM3U url-tvg="{ORIGIN}/guide.xml"\n'
                    f'#EXTINF:-1 tvg-id="fixture-clock" group-title="Fixture channels",{CHANNEL}\n'
                    f'{ORIGIN}/live.ts\n').encode()
            mime = "application/x-mpegURL"
        elif resource == "/guide.xml":
            def stamp(ts):
                return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y%m%d%H%M%S +0000")
            shows = []
            for i in range(-10, 30):
                at = self.server.origin + 45 * i
                shows.append(f'<programme start="{stamp(at)}" stop="{stamp(at+45)}" channel="fixture-clock">'
                             f'<title>Clock fixture {i+20}</title></programme>')
            data = ('<tv><channel id="fixture-clock"><display-name>Fixture Clock TV</display-name></channel>'
                    + "".join(shows) + "</tv>").encode()
            mime = "application/xml"
        elif resource == "/live.ts":
            data = self.server.media
            mime = "video/mp2t"
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Type", mime)
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass


def tap(text):
    for node in tree().iter("node"):
        if label(node) == text:
            x, y = point(node)
            adb("shell", "input", "tap", str(x), str(y))
            return
    raise AssertionError("Missing " + text)


def wait_for(text, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if any(label(n) == text for n in tree().iter("node")):
            return int(adb("shell", "date", "+%s"))
    raise AssertionError("Missing " + text)


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell", "getprop", "ro.kernel.qemu") == "1"
    assert adb("shell", "getprop", "ro.debuggable") == "1"
    adb("root")
    adb("wait-for-device")
    assert not adb("shell", "pm", "list", "packages", PACKAGE)
    OUT.mkdir(parents=True, exist_ok=True)
    apk, = Path("build/guide-apks").rglob("*-x86_64.apk")
    with zipfile.ZipFile(apk) as z:
        assert z.getinfo("assets/dexopt/baseline.prof").file_size > 4096
    assert "Success" in adb("install", str(apk))
    uid = adb("shell", "stat", "-c", "%u", "/data/user/0/" + PACKAGE)
    adb("shell", "wm", "size", "1920x1080")
    adb("shell", "wm", "density", "320")
    key("WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    server = ThreadingHTTPServer(("127.0.0.1", 38791), Provider)
    server.media = (OUT / "live.ts").read_bytes()
    server.origin = int(adb("shell", "date", "+%s"))
    Thread(target=server.serve_forever, daemon=True).start()
    first, second = server.origin + 30, server.origin + 115
    source = dict(id=PLAYLIST, name="Clock fixture", url=ORIGIN+"/clock.m3u", useEmbeddedEpg=True)
    reminders = [dict(k=KEY, c=CHANNEL, t=name, s=(at+60)*1000)
                 for name, at in (("First guide check", first), ("Playback guide check", second))]
    try:
        adb("reverse", "tcp:38791", "tcp:38791")
        seed_settings(uid, {"playlists":json.dumps([source]),"reminders":json.dumps(reminders),"clock_24h":True})
        cold()
        wait_for("Home", 20)
        cold()  # Normal process death and restart while the saved programme is still ahead.
        observed = wait_for("First guide check", 55)
        record("homeReminderSecondsFromDue", observed-first)
        assert 0 <= observed-first <= 15
        deadline = time.monotonic()+20
        while not REQUESTS["/clock.m3u"] and time.monotonic()<deadline:
            time.sleep(.25)
        time.sleep(2)
        tap("Watch")
        deadline = time.monotonic()+20
        while not REQUESTS["/live.ts"] and time.monotonic()<deadline:
            time.sleep(.25)
        assert REQUESTS["/live.ts"] > 0, "The saved reminder did not tune the real fixture channel"
        samples = []
        while int(adb("shell", "date", "+%s")) < second-4:
            key("INFO", .15)
            titles = [label(n) for n in tree().iter("node") if re.fullmatch(r"Clock fixture \d+", label(n))]
            if titles:
                samples.append({"at":int(adb("shell","date","+%s")), "titles":titles})
            time.sleep(2)
        record("fullscreenProgrammeSamples", samples)
        observed = wait_for("Playback guide check", 30)
        record("playbackReminderSecondsFromDue", observed-second)
        assert 0 <= observed-second <= 15
        tap("Dismiss")
        # Back first closes an open fullscreen info panel. Reach the guide before
        # sampling its clock, using its gear and channel text as screen evidence.
        for attempt in range(10):
            visible = {label(n) for n in tree().iter("node")}
            if "Guide display" in visible and any(t.startswith("Clock fixture ") for t in visible):
                break
            if "Live TV" in visible and visible & {"Home", "Library", "Search"}:
                tap("Live TV")
                time.sleep(1)
            elif attempt < 4:
                key("BACK", .8)
            else:
                key("DPAD_RIGHT", .5)
        else:
            record("previewEntryMarkers", sorted(visible & {"Home","Live TV","Library","Guide display","All channels","Dismiss","Watch","Settings",CHANNEL}))
            raise AssertionError("The remote did not enter the guide for the preview clock measurement")
        clocks = []
        for _ in range(4):
            texts = {label(n) for n in tree().iter("node")}
            wall = adb("shell", "date", "+%H:%M")
            clocks.append({"wall":wall, "wallShown":wall in texts, "guide":"Guide display" in texts,
                           "titles":sorted(t for t in texts if re.fullmatch(r"Clock fixture \d+",t))})
            if len({x['wall'] for x in clocks if x['wallShown'] and x['guide']}) >= 2:break
            time.sleep(26)
        record("previewGuideClock", clocks)
        assert len({s["titles"][0] for s in samples}) >= 2, "Fullscreen did not advance across a programme boundary"
        assert len({x["wall"] for x in clocks if x["guide"] and x["wallShown"]}) >= 2, "Preview clock failed to advance"
    finally:
        record("providerRequests", dict(REQUESTS))
        server.shutdown()
        server.server_close()

if __name__ == "__main__":
    main()
