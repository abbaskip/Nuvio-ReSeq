"""Time a real matched native stream card with a small and a 98,000-film M3U library."""
from http.server import ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
from statistics import median
from threading import Thread
import time
import zipfile

from signed_rollback import (BASE, DATA, NATIVE, PACKAGE, PLAIN, Provider, adb, cold, controls,
                             label, read_rows, seed_settings, select, tree)

OUT = Path("build/stream-picker")
COUNT = 98_000


class Library(Provider):
    def do_GET(self):
        if self.path != "/large-picker.m3u":
            return super().do_GET()
        body = self.server.playlist
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Type", "audio/x-mpegurl")
        self.end_headers()
        self.wfile.write(body)


def rows(expected):
    deadline = time.monotonic() + 150
    observed = {}
    while time.monotonic() < deadline:
        try:
            observed = dict(read_rows("livetv_on_demand.db", "SELECT pl, count(*) FROM items GROUP BY pl"))
            if observed == {NATIVE: 64, PLAIN: expected}:
                return
        except Exception as err:
            observed = type(err).__name__
        time.sleep(2)
    raise AssertionError(f"Published catalogue counts were {observed}")


def exercise(server, count):
    # Empty userdebug Actions guest only. Both sizes have the same sources enabled.
    assert "Success" in adb("shell", "pm", "clear", PACKAGE)
    server.playlist = ('#EXTM3U\n#EXTINF:-1 tvg-id="station",Fixture station\n' + BASE + '/station.ts\n' + "".join(
        f'#EXTINF:-1 group-title="Library",Library Film {i:06} (2001)\n{BASE}/files/{i}.mp4\n'
        for i in range(count))).encode()
    uid = adb("shell", "stat", "-c", "%u", DATA)
    sources = [dict(id=NATIVE, name="NativeFixture", url="", xtreamServer=BASE,
                    xtreamUsername="fixture", xtreamPassword="fixture", importVod=True, importLive=False),
               dict(id=PLAIN, name="PlainFixture", url=BASE+"/large-picker.m3u", importVod=True)]
    seed_settings(uid, {"playlists": json.dumps(sources), "on_demand_in_streams": True})
    controls()
    select("Update Movies & Series Now")
    rows(count)
    samples = []
    for index in (1, 7, 13, 21, 28, 38, 47, 59):
        cold()
        assert any(label(n) in ("Home", "Library", "Search") for n in tree().iter("node"))
        output = adb("shell", "am", "instrument", "-w", "-e", "index", str(index),
                     "com.nuvio.perf.observer/.PickerObserver")
        observed = re.search(r"pickerMs=(\d+)", output)
        if not observed:
            labels = {label(n) for n in tree().iter("node")}
            markers = ("Fixture Movie", "NativeFixture", "No streams", "No sources", "Home", "Library",
                       "Loading", "Streams", "Try installing", "Choose", "Provider", "Retry", "stopped")
            print("Visible fixture markers:", sorted({marker for marker in markers if any(marker in s for s in labels)}), flush=True)
            # The target log records only match counts. Keep other app and system logs private.
            log = adb("logcat", "-d", "-s", "IptvVod:D")
            print("Match counts:", re.findall(r"kind=MOVIE .*?candidates=\d+ matches=\d+", log)[-5:])
            raise AssertionError(output)
        samples.append({"fixture": index, "ms": int(observed[1]),
                        "lastMissMs": int(re.search(r"previousMissMs=(\d+)", output)[1])})
        print(count, samples[-1], flush=True)
    return {"addedMovies": count, "nativeTitles": 64, "samples": samples,
            "medianMs": median(x["ms"] for x in samples)}


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell", "getprop", "ro.kernel.qemu") == "1"
    adb("root")
    adb("wait-for-device")
    assert adb("shell", "id", "-u") == "0" and not adb("shell", "pm", "list", "packages", PACKAGE)
    OUT.mkdir(parents=True, exist_ok=True)
    apk, = Path("build/guide-apks").rglob("*-x86_64.apk")
    shutil.copyfile(apk, OUT/"base.apk")
    with zipfile.ZipFile(apk) as archive:
        (OUT/"base.dm").write_bytes(archive.read("assets/updater_dm/0.dm"))
    assert "Success" in adb("install-multiple", str(OUT/"base.apk"), str(OUT/"base.dm"))
    assert "status=speed-profile" in adb("shell", "pm", "art", "dump", PACKAGE)
    assert "Success" in adb("install", "build/picker-observer/observer.apk")
    adb("shell", "wm", "size", "1920x1080")
    adb("shell", "wm", "density", "320")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("reverse", "tcp:38791", "tcp:38791")
    server = ThreadingHTTPServer(("127.0.0.1", 38791), Library)
    Thread(target=server.serve_forever, daemon=True).start()
    results = {}
    try:
        for count in ([COUNT, 1] if os.environ["FIRST_SIZE"] == "large" else [1, COUNT]):
            results[str(count)] = exercise(server, count)
            (OUT/"results.json").write_text(json.dumps(results, indent=2))
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
