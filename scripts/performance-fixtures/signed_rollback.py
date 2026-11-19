"""Exercise the published releases and the signed candidate on a fresh CI Android guest."""
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Thread
import time
from xml.etree import ElementTree
from http.server import ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "baselineprofile/fixtures"))
sys.path.insert(0, str(ROOT / "scripts"))
from server import BASE, Web
from prepare_release_apks import certificate

PACKAGE = "io.github.abbaskip.nuvioreseq"
SERIAL = "emulator-5554"
DATA = f"/data/user/0/{PACKAGE}"
OUT = ROOT / "build/signed-rollback"
NATIVE = "rollback-native"
PLAIN = "rollback-m3u"
APK_HASHES = {
    "10.12": "fbe60e549ca695f0a28f6a8c0a28c5e25bc10cfe34318d958c222c2c62e31223",
    "integration": "85536b91337ac7b87fef53d9a3fd120da9e427e508cedefd5bbec47642b0b6f2",
    "10.11": "87784721f948614cbc492df9a590902c067bd405641364353533c248c3e753d5",
}
EVIDENCE = {}


def record(key, value):
    EVIDENCE[key] = value
    (OUT / "results.json").write_text(json.dumps(EVIDENCE, indent=2) + "\n")
    print(key, json.dumps(value), flush=True)


def run(*args, timeout=90):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=timeout).stdout


def adb(*args):
    return run("adb", "-s", SERIAL, *args).decode(errors="replace").strip()


class Provider(Web):
    online = True
    def log_message(self, *_):
        pass

    def do_GET(self):
        if not self.online:
            self.send_error(503)
            return
        if self.path != "/rollback.m3u":
            return super().do_GET()
        data = ("\n".join([
            "#EXTM3U",
            '#EXTINF:-1 tvg-id="rollback" group-title="Local",Rollback Channel', BASE + "/station.ts",
            '#EXTINF:-1 group-title="Local films",Offline Feature (2024)', BASE + "/feature.mp4|X-QA=fixture",
            '#EXTINF:-1 group-title="Local shows",Offline Show S01E01', BASE + "/e1.mp4|X-QA=fixture",
            '#EXTINF:-1 group-title="Local shows",Offline Show S01E02', BASE + "/e2.mp4|X-QA=fixture",
        ]) + "\n").encode()
        self.send_response(200)
        self.send_header("Content-Type", "audio/x-mpegurl")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def signed_apks():
    version = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    keys, apks = set(), {}
    for label, digest in APK_HASHES.items():
        apk, = (ROOT / "build/rollback-apks" / label).rglob("*-x86_64.apk")
        assert hashlib.sha256(apk.read_bytes()).hexdigest() == digest, label
        keys.add(certificate(str(version / "apksigner"), apk))
        manifest = run(str(version / "aapt"), "dump", "badging", str(apk)).decode()
        code = int(re.search(r"package: name='" + re.escape(PACKAGE) + r"' versionCode='(\d+)'", manifest)[1])
        assert "application-debuggable" not in manifest
        apks[label] = (str(apk), code)
    assert len(keys) == 1
    assert apks["10.11"][1] < apks["10.12"][1] == apks["integration"][1]
    record("signedPackages", {label: {"versionCode": code, "sha256": APK_HASHES[label]}
                               for label, (_, code) in apks.items()})
    record("sharedSignerSha256", keys.pop())
    return apks


def install(apks, label, *flags):
    path, code = apks[label]
    assert "Success" in adb("install", *flags, path)
    info = adb("shell", "dumpsys", "package", PACKAGE)
    assert re.search(r"\bversionCode=" + str(code) + r"\b", info)
    return adb("shell", "stat", "-c", "%u", DATA)


def varint(number):
    data = bytearray()
    while number >= 128:
        data.append((number & 127) | 128)
        number >>= 7
    data.append(number)
    return data


def field(tag, value):
    return bytes([tag << 3 | 2]) + varint(len(value)) + value


def seed_settings(uid):
    # AndroidX Preferences' normal on-disk protobuf. Only first-run settings and source
    # configuration are seeded; the signed release creates every database and catalogue row.
    sources = [
        dict(id=NATIVE, name="NativeFixture", url="", xtreamServer=BASE, xtreamUsername="fixture",
             xtreamPassword="fixture", importVod=True, importLive=False, useEmbeddedEpg=False),
        dict(id=PLAIN, name="PlainFixture", url=BASE + "/rollback.m3u", importVod=False,
             useEmbeddedEpg=False),
    ]
    files = {
        "app_onboarding": {"has_seen_auth_qr_on_first_launch": True},
        "profile_settings": {"profile_has_ever_selected": True, "remember_last_profile_enabled": True,
                             "startup_splash_enabled": False},
        "layout_settings": {"selected_layout": "MODERN", "has_chosen_layout": True},
        "experience_settings": {"addon_setup_skipped": True},
        "live_tv": {"playlists": json.dumps(sources)},
    }
    folder = DATA + "/files/datastore"
    adb("shell", "mkdir", "-p", folder)
    with TemporaryDirectory() as temp:
        for name, prefs in files.items():
            entries = bytearray()
            for key, value in prefs.items():
                encoded = bytes([8, int(value)]) if isinstance(value, bool) else field(5, value.encode())
                entries += field(1, field(1, key.encode()) + field(2, encoded))
            local = Path(temp) / (name + ".preferences_pb")
            local.write_bytes(entries)
            adb("push", str(local), folder + "/" + local.name)
    assert uid.isdigit() and int(uid) >= 10000
    adb("shell", "chown", "-R", f"{uid}:{uid}", DATA + "/files")
    adb("shell", "restorecon", "-R", DATA + "/files")


def tree():
    path = "/data/local/tmp/rollback-ui.xml"
    adb("shell", "uiautomator", "dump", path)
    return ElementTree.fromstring(adb("exec-out", "cat", path))


def label(node):
    return node.get("text") or node.get("content-desc") or ""


def point(node):
    left, top, right, bottom = map(int, re.findall(r"-?\d+", node.get("bounds")))
    return (left + right) // 2, (top + bottom) // 2


def key(name, delay=.3):
    adb("shell", "input", "keyevent", "KEYCODE_" + name)
    time.sleep(delay)


def cold():
    adb("shell", "am", "force-stop", PACKAGE)
    activity = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/com.nuvio.tv.launcher.AppIconDefault")
    assert "Status: ok" in activity, activity
    time.sleep(5)


def rail_settings():
    for _ in range(30):
        nodes = list(tree().iter("node"))
        focus = next((n for n in nodes if n.get("focused") == "true"), None)
        targets = [n for n in nodes if label(n) == "Settings" and point(n)[0] < 300]
        if focus is not None and point(focus)[0] < 210 and any(label(n) == "Settings" for n in focus.iter()):
            key("DPAD_CENTER", 2)
            return
        direction = "LEFT" if focus is None or point(focus)[0] > 210 or not targets else (
            "DOWN" if point(focus)[1] < point(targets[-1])[1] else "UP")
        key("DPAD_" + direction)
    raise AssertionError("Settings was unreachable from the launcher")


def controls():
    cold()
    rail_settings()
    for direction in ["DOWN"] * 8 + ["RIGHT"] * 4 + ["DOWN"]:
        key("DPAD_" + direction, .45)
    assert any(label(n) == "Open IPTV On Demand library" for n in tree().iter("node")), "On Demand settings"


def tap(text):
    for _ in range(8):
        target = next((n for n in tree().iter("node") if label(n) == text), None)
        if target is not None:
            x, y = point(target)
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(1.2)
            return
        key("DPAD_DOWN")
    raise AssertionError("Missing setting " + text)


def read_rows(database, sql, params=()):
    # The emulator has no user data. A transient copy includes WAL in case the app
    # is still running. Final comparisons are repeated after stopping the process.
    remote = DATA + "/databases/" + database
    available = set(adb("shell", "ls", DATA + "/databases").splitlines())
    with TemporaryDirectory() as temp:
        for suffix in ("", "-wal"):
            if database + suffix not in available:
                continue
            path = Path(temp) / (database + suffix)
            path.write_bytes(run("adb", "-s", SERIAL, "exec-out", "cat", remote + suffix))
        with sqlite3.connect(Path(temp) / database) as db:
            return db.execute(sql, params).fetchall()


def catalogue(playlist):
    columns = "kind,id,name,norm,cat,ext,tmdb,year,added"
    return read_rows("livetv_on_demand.db", f"SELECT {columns} FROM items WHERE pl=? ORDER BY kind,id", (playlist,))


def links():
    return read_rows("m3u-vod-links.db", "SELECT pl,item,entry,kind,name,cat,season,episode,title,url,headers "
                                        "FROM links ORDER BY pl,item,entry")


def wait_count(playlist, expected):
    deadline = time.monotonic() + 65
    while time.monotonic() < deadline:
        try:
            if len(catalogue(playlist)) == expected:
                return
        except (subprocess.CalledProcessError, sqlite3.DatabaseError):
            pass
        time.sleep(2)
    raise AssertionError(f"Missing {expected} catalogue rows for {playlist}")


def digest(rows):
    return hashlib.sha256(json.dumps(rows, ensure_ascii=False).encode()).hexdigest()


def snapshot(label):
    adb("shell", "am", "force-stop", PACKAGE)
    native, plain = catalogue(NATIVE), catalogue(PLAIN)
    assert {row[2] for row in native} == {f"Fixture Movie {i:03}" for i in range(1, 65)}
    schema = read_rows("livetv_on_demand.db", "PRAGMA user_version")[0][0]
    assert schema == 11
    result = dict(schema=schema, nativeTitles=len(native), nativeSha256=digest(native), plainTitles=len(plain))
    if label != "published":
        sidecar = links()
        assert len(sidecar) == 3 and {row[7] for row in sidecar} >= {1, 2}
        result.update(plainSha256=digest(plain), linkRows=len(sidecar), linkSha256=digest(sidecar))
    record(label, result)
    return result


def view_native():
    tap("Open IPTV On Demand library")
    for _ in range(7):
        visible = {label(n) for n in tree().iter("node")}
        if {"Fixture Movie 001", "Fixture movies"}.issubset(visible):
            return
        key("DPAD_DOWN", .5)
    raise AssertionError("The signed app did not display the saved native shelf")


def exercise():
    OUT.mkdir(parents=True, exist_ok=True)
    apks = signed_apks()
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell", "getprop", "ro.kernel.qemu") == "1"
    assert adb("shell", "getprop", "ro.debuggable") == "1" and adb("shell", "am", "get-current-user") == "0"
    adb("root")
    adb("wait-for-device")
    assert adb("shell", "id", "-u") == "0"
    assert PACKAGE not in adb("shell", "pm", "list", "packages", PACKAGE)
    record("guest", {"api": adb("shell", "getprop", "ro.build.version.sdk"),
                     "system": adb("shell", "getprop", "ro.build.type")})
    adb("shell", "wm", "size", "1920x1080")
    adb("shell", "wm", "density", "320")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    server = ThreadingHTTPServer(("127.0.0.1", 38791), Provider)
    Thread(target=server.serve_forever, daemon=True).start()
    try:
        adb("reverse", "tcp:38791", "tcp:38791")
        uid = install(apks, "10.12")
        adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        seed_settings(uid)
        controls()
        tap("Update Movies & Series Now")
        wait_count(NATIVE, 64)
        initial = snapshot("published")
        assert initial["plainTitles"] == 0

        assert install(apks, "integration", "-r") == uid
        controls()
        tap("Movies & Series from PlainFixture")
        wait_count(PLAIN, 2)
        candidate = snapshot("candidate")
        assert candidate["nativeSha256"] == initial["nativeSha256"]
        assert candidate["plainTitles"] == 2

        Provider.online = False
        assert install(apks, "10.11", "-r", "-d") == uid
        record("lowerVersionInstalled", True)
        controls()
        view_native()
        record("lowerBetaOfflineNativeShelfDisplayed", True)
        time.sleep(27)  # Allow the older release's normal 20-second catalogue maintenance.
        old = snapshot("lowerBeta")
        assert old["nativeSha256"] == initial["nativeSha256"] and old["linkSha256"] == candidate["linkSha256"]

        assert install(apks, "integration", "-r") == uid
        controls()
        view_native()
        wait_count(PLAIN, 2)
        final = snapshot("reupgradeOffline")
        assert final["nativeSha256"] == initial["nativeSha256"]
        assert final["plainSha256"] == candidate["plainSha256"] and final["linkSha256"] == candidate["linkSha256"]
        record("sameAndroidUidThroughout", True)
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    if sys.argv[1:] == ["--apks-only"]:
        OUT.mkdir(parents=True, exist_ok=True)
        signed_apks()
        sys.exit(0)
    try:
        exercise()
    except Exception:
        try:
            # Publish only static labels from the synthetic UI, never device codes or URLs.
            allow = {"Home", "Settings", "IPTV", "Providers", "On Demand", "Playlists & Guides",
                     "Open IPTV On Demand library", "Update Movies & Series Now", "Fixture Movie 001",
                     "Fixture movies", "Offline Feature", "Movies & Series from PlainFixture",
                     "Skip", "Continue", "Allow", "Default", "Modern", "Movies & series imported",
                     "Nothing imported yet", "NativeFixture", "PlainFixture", "General", "Profiles",
                     "Display", "Playback", "Layout", "Search", "Library", "Discover", "Choose Profile"}
            nodes = list(tree().iter("node"))
            record("visibleAtFailure", sorted({label(n) for n in nodes} & allow))
            focus = next((n for n in nodes if n.get("focused") == "true"), None)
            if focus is not None:
                record("focusedAtFailure", {"bounds": focus.get("bounds"),
                                   "labels": sorted({label(n) for n in focus.iter()} & allow)})
        except Exception:
            pass
        raise
