"""Compare visible Settings and Detail with their respective release wildcard removed."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
from threading import Thread
import time

from signed_rollback import BASE, DATA, PACKAGE, adb, cold, key, label, rail_settings, seed_settings, tree
from guest_failure import capture

OUT = Path("build/profile-ui")
TITLE = "Profile measurement series"
SERIES = "tt9900998"
ADDON = BASE + "/profile"


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path.endswith("/manifest.json"):
            payload = {"id": "com.reseq.fixture.profile", "version": "1.0.0", "name": "Profile fixture",
                       "resources": ["meta", "catalog", "stream"], "types": ["series", "movie"],
                       "idPrefixes": [SERIES,"tt990100"], "catalogs": [{"type":"movie","id":"local","name":"Guest films"}]}
        elif "/catalog/movie/" in self.path:
            payload = {"metas":[{"id":f"tt990100{i}","type":"movie","name":f"Guest film {i}","releaseInfo":"2024"}
                                for i in range(1,9)]}
        elif "/meta/series/" in self.path:
            payload = {"meta": {"id": SERIES, "type": "series", "name": TITLE,
                       "description": "Controlled series synopsis", "releaseInfo": "2024", "year": 2024,
                       "videos": [{"id": f"{SERIES}:1:{ep}", "season": 1, "episode": ep,
                                   "title": f"Experiment episode {ep:02}", "released": "2024-01-02T12:00:00Z"}
                                  for ep in range(1, 41)]}}
        elif "/stream/" in self.path:
            payload = {"streams": []}
        else:
            self.send_error(404)
            return
        raw = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def sample(mode, profile):
    installed = "package:"+PACKAGE in adb("shell", "pm", "list", "packages", PACKAGE).splitlines()
    if installed:
        adb("shell", "am", "force-stop", PACKAGE)
        assert "Success" in adb("uninstall", PACKAGE)
    shutil.copyfile(OUT/"metadata"/(profile + ".dm"), OUT/"base.dm")
    assert "Success" in adb("install-multiple", str(OUT/"base.apk"), str(OUT/"base.dm"))
    art = adb("shell", "pm", "art", "dump", PACKAGE)
    assert "status=speed-profile" in art and "reason=install-dm" in art, art[:500]
    uid = adb("shell", "stat", "-c", "%u", DATA)
    seed_settings(uid, {}, {"addon_preferences": {"installed_addon_urls_ordered": json.dumps([ADDON])}})
    cold()
    for _ in range(3):
        nodes = list(tree().iter("node"))
        if any(n.get("package") == PACKAGE and label(n) in ("Home", "Search", "Library") for n in nodes):
            break
        cold()
    else:
        raise AssertionError("The signed app's Home was not ready for a timed entry")
    if mode == "settings":
        rail_settings(activate=False)
    observed = adb("shell", "am", "instrument", "-w", "-e", "mode", mode,
                   "com.nuvio.perf.observer/.PickerObserver")
    measurement = re.search(r"pickerMs=(\d+)", observed)
    assert measurement, observed
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    for direction in ["DPAD_DOWN"] * 12 + ["DPAD_UP"] * 12:
        key(direction, .15)
    time.sleep(.6)
    frame_data = adb("shell", "dumpsys", "gfxinfo", PACKAGE)
    counts = re.search(r"Total frames rendered:\s*(\d+)", frame_data)
    p95 = re.search(r"95th percentile:\s*(\d+)ms", frame_data)
    assert counts and int(counts[1]) > 0 and p95
    return {"entryMs": int(measurement[1]), "frames": int(counts[1]), "dpadP95Ms": int(p95[1])}


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell", "getprop", "ro.kernel.qemu") == "1"
    adb("root")
    adb("wait-for-device")
    assert adb("shell", "id", "-u") == "0" and not adb("shell", "pm", "list", "packages", PACKAGE)
    assert "Success" in adb("install", "build/picker-observer/observer.apk")
    adb("shell", "wm", "size", "1920x1080")
    adb("shell", "wm", "density", "320")
    key("WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("reverse", "tcp:38791", "tcp:38791")
    mode = os.environ["UI_MODE"]
    assert mode in ("settings", "detail")
    apk, = (OUT/"apks").rglob("*-x86_64.apk")
    shutil.copyfile(apk, OUT/"base.apk")
    server = ThreadingHTTPServer(("127.0.0.1", 38791), Fixture)
    Thread(target=server.serve_forever, daemon=True).start()
    samples = []
    try:
        for index in range(8):
            pair = {}
            plans = ["all", "com.nuvio.tv.ui.screens." + mode]
            for profile in plans if index % 2 == 0 else reversed(plans):
                pair[profile] = sample(mode, profile)
                print(index, profile, pair[profile], flush=True)
            samples.append(pair)
            (OUT/"samples.json").write_text(json.dumps(samples, indent=2))
    except Exception:
        capture(OUT)
        raise
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
