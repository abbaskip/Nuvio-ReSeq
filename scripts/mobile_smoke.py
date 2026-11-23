"""Exercise the mobile catalogue, library, downloads, VOD and live TV on an isolated emulator."""
import argparse
import json
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
from threading import Event, Thread
from urllib.parse import parse_qs, unquote, urlsplit
import time
import xml.etree.ElementTree as ET

from mobile_parity import MOBILE_ACTIVITY, MOBILE_ID, ROOT


def provider_response(path, query):
    action = query.get("action", [""])[0]
    if path == "/player_api.php":
        return {
            "": {"user_info": {"auth": 1, "status": "Active"}},
            "get_vod_categories": [{"category_id": "2", "category_name": "Fixture films"}],
            "get_series_categories": [{"category_id": "3", "category_name": "Fixture shows"}],
            "get_vod_streams": [{"stream_id": 21, "name": "Xtream Fixture Film", "category_id": "2", "container_extension": "mp4"}],
            "get_series": [{"series_id": 31, "name": "Xtream Fixture Show", "category_id": "3"}],
            "get_vod_info": {"info": {"plot": "Xtream film description"}},
            "get_series_info": {"info": {"plot": "Xtream episode selection"}, "episodes": {
                "1": [{"id": "311", "episode_num": 1, "title": "Fixture pilot", "container_extension": "mp4"}]}},
            "get_live_categories": [], "get_live_streams": [],
        }.get(action, [])
    if path.endswith(("/load.php", "/portal.php")):
        if action == "handshake":
            value = {"token": "synthetic-portal-session"}
        elif action == "get_profile":
            value = {"status": 0}
        elif action == "get_categories":
            value = [{"id": "fixture", "title": "Portal selections"}]
        elif action == "get_ordered_list":
            rows = ([{"id": "41", "name": "Stalker Fixture Film", "category_id": "fixture", "cmd": "movie 41"}]
                    if query.get("type") == ["vod"] and query.get("p", ["1"]) == ["1"] else [])
            value = {"total_items": len(rows), "max_page_items": 12, "data": rows}
        else:
            value = []
        return {"js": value}
    return None


@contextmanager
def fixture():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "live").mkdir()
        address = "http://127.0.0.1:38792"
        channels = ["One", "Two"]
        playlist = ["#EXTM3U"]
        guide = ET.Element("tv")
        now = datetime.now(timezone.utc)
        stamp = "%Y%m%d%H%M%S +0000"
        for channel in channels:
            name = "QA " + channel
            # The real importer reserves .mp4 playlist entries for on-demand movies.
            shutil.copyfile(ROOT / "app/src/test/resources/usenet/test-pattern.mp4", root / "live" / channel)
            playlist += [f'#EXTINF:-1 tvg-id="{channel}" group-title="Fixture",{name}', f"{address}/live/{channel}"]
            ET.SubElement(ET.SubElement(guide, "channel", id=channel), "display-name").text = name
        for channel in channels:
            programme = ET.SubElement(guide, "programme", channel=channel, start=(now-timedelta(minutes=45)).strftime(stamp),
                                      stop=(now+timedelta(hours=2)).strftime(stamp))
            ET.SubElement(programme, "title").text = "Programme " + channel
            ET.SubElement(programme, "desc").text = "Local XMLTV fixture"
        (root / "live.m3u").write_text("\n".join(playlist) + "\n", encoding="utf-8")
        ET.ElementTree(guide).write(root / "guide.xml", encoding="utf-8", xml_declaration=True)
        played = {channel: Event() for channel in channels}
        sources = []
        files = []
        offline = Event()
        movies = [{"id": "qafilm" + name, "type": "movie", "name": "Test film " + name} for name in channels]
        series = {"id": "qaseries", "type": "series", "name": "Zyntraxis ReSeq Fixture", "videos": [
            {"id": f"qaseries:1:{n}", "season": 1, "episode": n, "title": title, "released": "2020-01-01T00:00:00.000Z"}
            for n, title in enumerate(("Pilot", "Second episode"), start=1)]}
        manifest = {"id": "org.reseq.touchtest", "version": "1.0.0", "name": "Offline fixture",
                    "resources": ["catalog", "meta", "stream"], "types": ["movie", "series"], "idPrefixes": ["qafilm", "qaseries"],
                    "catalogs": [{"type": kind, "id": "offline-" + kind, "name": "Offline fixture " + kind,
                                  "extra": [{"name": "search", "isRequired": False}]} for kind in ("movie", "series")]}
        # 130 seconds of generated H.264 and silent AAC exceeds the shared player's short-placeholder guard.
        clip = (ROOT / "scripts/fixtures/mobile-player.mp4").read_bytes()

        class Handler(SimpleHTTPRequestHandler):
            def do_GET(self):
                path = unquote(urlsplit(self.path).path)
                provider = provider_response(path, parse_qs(urlsplit(self.path).query))
                if provider is not None:
                    payload = json.dumps(provider).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                    return
                if path == "/series/testuser/testpass/311.mp4":
                    files.append(path)
                    self.send_response(200)
                    self.send_header("Content-Type", "video/mp4")
                    self.send_header("Content-Length", str(len(clip)))
                    self.end_headers()
                    self.wfile.write(clip)
                    return
                if path.startswith("/addon/"):
                    if path.endswith("/manifest.json"):
                        data = manifest
                    elif "/catalog/movie/" in path:
                        data = {"metas": [] if "/search=" in path and "film" not in path.casefold() else movies}
                    elif "/catalog/series/" in path:
                        data = {"metas": [series] if "/search=" not in path or "zyntraxis" in path.casefold() else []}
                    elif "/meta/series/" in path:
                        data = {"meta": series}
                    elif "/meta/movie/" in path:
                        movie_id = Path(path).stem
                        data = {"meta": next(m for m in movies if m["id"] == movie_id)}
                    elif "/stream/" in path:
                        sources.append(path)
                        if offline.is_set():
                            return self.send_error(503)
                        movie_id = Path(path).stem
                        data = {"streams": [{"name": variant, "url": f"{address}/files/{movie_id}/{variant}.mp4",
                                             "behaviorHints": {"proxyHeaders": {"request": {"X-Fixture": "offline"}}}}
                                            for variant in ("Primary", "Alternate")]}
                    else:
                        return self.send_error(404)
                    payload = json.dumps(data).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                    return
                if path.startswith("/files/"):
                    files.append(path)
                    if offline.is_set() or self.headers.get("X-Fixture") != "offline":
                        return self.send_error(403)
                    self.send_response(200)
                    self.send_header("Content-Type", "video/mp4")
                    video = clip
                    self.send_header("Content-Length", str(len(video)))
                    self.end_headers()
                    self.wfile.write(video)
                    return
                channel = self.path.removeprefix("/live/")
                if self.path.startswith("/live/") and channel in played:
                    played[channel].set()
                super().do_GET()

            def guess_type(self, path):
                return "video/mp4" if Path(path).parent == root / "live" else super().guess_type(path)

        with ThreadingHTTPServer(("127.0.0.1", 0), partial(Handler, directory=directory)) as server:
            thread = Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                yield address, server.server_port, played, sources, files, offline
            finally:
                server.shutdown()
                thread.join()


def run(adb, serial, apk, catalog_only=False, drive_sign_in=False, extended=False, upgrade_apk=None, providers_only=False):
    if not re.fullmatch("emulator-[0-9]+", serial):
        raise ValueError("Use an isolated emulator serial for the touch smoke test")

    def command(*args, timeout=45):
        return subprocess.check_output([adb, "-s", serial, *args], timeout=timeout)

    def shell(*args):
        return command("shell", *args).decode().strip()

    def nodes():
        shell("uiautomator", "dump", "/sdcard/reseq-smoke.xml")
        return ET.fromstring(command("exec-out", "cat", "/sdcard/reseq-smoke.xml")).findall(".//node")

    def locate(text=None, editable=None, seconds=60):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            tree = nodes()
            if editable is not None:
                fields = [node for node in tree if node.get("class") == "android.widget.EditText"]
                if len(fields) > editable:
                    return fields[editable]
            else:
                for node in tree:
                    if node.get("class") != "android.widget.EditText" and (node.get("text") == text or node.get("content-desc") == text):
                        return node
            time.sleep(.25)
        raise AssertionError(f"Missing {text or 'text field'} on {serial}. Visible labels: " +
                             str([n.get("text") for n in tree if n.get("text")]))

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", node.get("bounds")))
        shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def choose(text): tap(locate(text))

    def enter_first_field(value):
        tap(locate(editable=0))
        shell("input", "text", value)
        shell("input", "keyevent", "KEYCODE_BACK")

    def scroll_to(label):
        for attempt in range(30):
            tree = nodes()
            match = next((n for n in tree if n.get("text") == label), None)
            if match is not None:
                return match
            boxes = [tuple(map(int, re.findall(r"-?\d+", n.get("bounds")))) for n in tree if n.get("scrollable") == "true"]
            if boxes:
                left, top, right, bottom = max(boxes, key=lambda box: (box[2] - box[0]) * (box[3] - box[1]))
                x = str((left + right) // 2)
                start, end = str((top + 4 * bottom) // 5), str((3 * top + 2 * bottom) // 5)
                # Search results can arrive above the current position while another catalogue loads.
                if attempt >= 20:
                    start, end = end, start
                shell("input", "swipe", x, start, x, end, "350")
            else:
                time.sleep(.25)
        raise AssertionError(f"Could not scroll to {label}. Visible labels: " + str([n.get("text") for n in tree if n.get("text")]))

    def long_press(node):
        a, b, c, d = map(int, re.findall(r"-?\d+", node.get("bounds")))
        x, y = str((a+c)//2), str((b+d)//2)
        shell("input", "swipe", x, y, x, y, "800")

    def source(button, name, url):
        if any(node.get("text") == name for node in nodes()):
            return False
        choose(button)
        tap(locate(editable=0))
        shell("input", "text", name)
        tap(locate(editable=1))
        shell("input", "text", url)
        shell("input", "keyevent", "KEYCODE_BACK")
        choose("Add")
        return True

    output = ROOT / "build/mobile-smoke"
    output.mkdir(parents=True, exist_ok=True)

    def screenshot(name, landscape):
        png = command("exec-out", "screencap", "-p")
        width, height = struct.unpack(">II", png[16:24])
        if (width > height) != landscape:
            raise AssertionError(f"{name} rendered at unexpected dimensions: {width}x{height}")
        (output / (name + ".png")).write_bytes(png)

    def rotate(quarter_turns):
        # Use WindowManager's per-display rotation lock. A settings write can be overwritten
        # by a delayed display reconfiguration after changing the emulator size or density.
        for _ in range(16):
            shell("wm", "user-rotation", "lock", str(quarter_turns))
            png = command("exec-out", "screencap", "-p")
            width, height = struct.unpack(">II", png[16:24])
            if (width > height) == bool(quarter_turns % 2):
                return
            time.sleep(.5)
        raise AssertionError(f"Display rotation did not settle: {width}x{height}")

    command("install", "-r", str(apk), timeout=180)
    size = shell("wm", "size")
    density = shell("wm", "density")
    rotation = shell("settings", "get", "system", "user_rotation")
    auto = shell("settings", "get", "system", "accelerometer_rotation")
    with fixture() as (address, port, played, sources, files, offline):
        command("reverse", "tcp:38792", f"tcp:{port}")
        try:
            shell("wm", "size", "1080x2160")
            shell("wm", "density", "420")
            rotate(0)
            shell("input", "keyevent", "KEYCODE_WAKEUP")
            shell("wm", "dismiss-keyguard")
            shell("am", "start", "-W", "-n", f"{MOBILE_ID}/{MOBILE_ACTIVITY}")
            locate("Catalog sources")
            if providers_only:
                shell("wm", "size", "1600x2560")
                shell("wm", "density", "240")
                rotate(0)
                choose("Settings")
                tap(scroll_to("IPTV sources"))
                for button, name, fields in (
                    ("Add Xtream account", "FixtureXtream", [address, "testuser", "testpass"]),
                    ("Add Stalker portal", "FixtureStalker", [address + "/c/", "00:1A:79:00:00:02"]),
                ):
                    tap(scroll_to(button))
                    for index, value in enumerate([name, *fields]):
                        tap(locate(editable=index))
                        shell("input", "text", value)
                        shell("input", "keyevent", "KEYCODE_BACK")
                    choose("Add")
                    locate(name)
                choose("Library")
                choose("IPTV library")
                locate("Stalker Fixture Film", seconds=100)
                choose("Xtream Fixture Film")
                locate("Xtream film description")
                shell("input", "keyevent", "KEYCODE_BACK")
                choose("Series")
                choose("Xtream Fixture Show")
                choose("S1E1 · Fixture pilot")
                locate("Audio")
                deadline = time.monotonic() + 20
                while "/series/testuser/testpass/311.mp4" not in files and time.monotonic() < deadline:
                    time.sleep(.25)
                assert "/series/testuser/testpass/311.mp4" in files, "Xtream did not request the selected episode"
                print("Verified Xtream account and Stalker portal imports, provider details and selected Xtream episode playback.")
                return
            if not catalog_only:
                choose("Settings")
                choose("Google Drive backup and restore")
                drive_labels = {node.get("text") for node in nodes()}
                if "Connect Google Drive" not in drive_labels:
                    if "-release" in apk.name or "universal.apk" in apk.name or "Google Drive sign-in is unavailable in this build." not in drive_labels:
                        raise AssertionError("Drive connection was unavailable in the configured build")
                if drive_sign_in:
                    choose("Connect Google Drive")
                    # Reaching Google's user-code prompt exercises the packaged OAuth client without an account.
                    locate("Open sign-in page")
                    choose("Cancel")
                choose("Back")
                choose("IPTV sources")
                added_playlist = source("Add M3U playlist", "ReSeqFixtureM3U", address + "/live.m3u")
                added_guide = source("Add XMLTV guide", "ReSeqFixtureXMLTV", address + "/guide.xml")
                if not (added_playlist or added_guide):
                    choose("Refresh sources")
                choose("Live TV")
                locate("QA One")
                locate("Programme Two")
                choose("Programme One")
                locate("Local XMLTV fixture")
                choose("Watch channel")
                if not played["One"].wait(20):
                    raise AssertionError("ReSeq did not request the first channel stream")
                choose("Channels")
                choose("Guide")
                locate("Programme One")
                locate("Programme Two")
                screenshot("phone-player-guide", landscape=False)
                choose("QA Two")
                if not played["Two"].wait(20):
                    raise AssertionError("Switching channels did not request the second stream")
                shell("input", "keyevent", "KEYCODE_BACK")
                locate("Live guide")
                screenshot("phone-guide", landscape=False)
                choose("Calendar")
                choose("Month")
                choose("Next")
                screenshot("phone-calendar", landscape=False)
                rotate(1)
                choose("Search")
                choose("Advanced Discover")
                choose("Filters")
                locate("Title contains")
                screenshot("landscape-filters", landscape=True)
                shell("input", "keyevent", "KEYCODE_BACK")
                shell("wm", "size", "1600x2560")
                shell("wm", "density", "240")
                choose("Live TV")
                choose("Sports")
                locate("On TV now")
                screenshot("tablet-sports", landscape=True)
                if extended:
                    choose("Alerts")
                    choose("Hide scores")
                    shell("input", "keyevent", "KEYCODE_BACK")
                    choose("Alerts")
                    choose("Hide scores")
                    shell("input", "keyevent", "KEYCODE_BACK")
                choose("Calendar")
                locate("Month")
                screenshot("tablet-calendar", landscape=True)
                shell("wm", "size", "1080x2160")
                shell("wm", "density", "420")
                rotate(0)
                # Start the catalogue journey on Home after the separate tablet navigation journey.
                # A fresh Activity reads the final display configuration on the hosted emulator.
                shell("am", "force-stop", MOBILE_ID)
                shell("am", "start", "-W", "-n", f"{MOBILE_ID}/{MOBILE_ACTIVITY}")
            choose("Catalog sources")
            if any(n.get("text") == "Offline fixture" for n in nodes()):
                shell("input", "keyevent", "KEYCODE_BACK")
            else:
                tap(locate(editable=0))
                shell("input", "text", address + "/addon/manifest.json")
                shell("input", "keyevent", "KEYCODE_BACK")
                choose("Install source")
            # Search through the installed catalogue, save a series, and play a chosen episode.
            if extended:
                choose("Library")
                choose("Collections")
                choose("New collection")
                enter_first_field("TouchCollection")
                tap(scroll_to("Add folder"))
                enter_first_field("FixtureFolder")
                tap(scroll_to("Catalogues"))
                choose("Offline fixture movie · Offline fixture")
                tap(scroll_to("Done"))
                tap(scroll_to("Save folder"))
                tap(scroll_to("Save collection"))
                locate("TouchCollection")
                choose("FixtureFolder")
                tap(scroll_to("Test film One"))
                locate("Play manually")
                shell("input", "keyevent", "KEYCODE_BACK")
                shell("input", "keyevent", "KEYCODE_BACK")
                choose("Back")
            choose("Search")
            if any(n.get("text") == "Filters" for n in nodes()):
                shell("input", "keyevent", "KEYCODE_BACK")
            tap(locate(editable=0))
            shell("input", "text", "Zyntraxis")
            choose("Find")
            tap(scroll_to("Zyntraxis ReSeq Fixture"))
            choose("Add to library")
            locate("Remove from library")
            choose("Library")
            choose("Zyntraxis ReSeq Fixture")
            tap(scroll_to("S1E2 · Second episode"))
            choose("Play manually")
            choose("Alternate")
            choose("Play this file")
            locate("Audio")
            if not any("qaseries:1:2/Alternate" in path for path in files):
                deadline = time.monotonic() + 20
                while not any("qaseries:1:2/Alternate" in path for path in files) and time.monotonic() < deadline:
                    time.sleep(.25)
            assert any("qaseries:1:2/Alternate" in path for path in files), "Selected series episode did not play"
            if extended:
                choose("Speed")
                choose("1.25×")
                choose("Subtitles")
                locate("Reset delay")
                shell("input", "keyevent", "KEYCODE_BACK")
            choose("Back")
            if extended:
                tap(scroll_to("Add to TV Mix"))
                enter_first_field("FixtureMix")
                choose("Create mix with this show")
                deadline = time.monotonic() + 20
                while any(n.get("text") == "Create mix with this show" for n in nodes()):
                    if time.monotonic() >= deadline:
                        raise AssertionError("Saving the new TV mix did not close the sheet")
                shell("input", "keyevent", "KEYCODE_BACK")
                choose("TV Mix")
                choose("FixtureMix")
                locate("Play episode")
                episode_start = len(files)
                for step in ("Play episode", "Continue mix"):
                    before_play = len(files)
                    choose(step)
                    deadline = time.monotonic() + 185
                    while time.monotonic() < deadline:
                        labels = {n.get("text") for n in nodes()}
                        if "Continue mix" in labels and any("/qaseries:" in path for path in files[before_play:]):
                            break
                        if "Play this file" in labels:
                            choose("Play this file")
                        elif "Primary" in labels:
                            choose("Primary")
                    else:
                        raise AssertionError("The mix did not return to its continuation prompt")
                played_episodes = {path.split("/")[-2] for path in files[episode_start:] if "/qaseries:" in path}
                assert len(played_episodes) == 2, f"Expected two distinct mix episodes, saw {played_episodes}"
            choose("Home")
            long_press(scroll_to("Test film One"))
            choose("Download")
            deadline = time.monotonic() + 55
            while not any("qafilmOne/Primary" in f for f in files) and time.monotonic() < deadline:
                time.sleep(.5)
            if not any("qafilmOne/Primary" in f for f in files):
                raise AssertionError(f"Automatic download did not use the first selected source: {files}")
            locate("Play manually")
            shell("input", "keyevent", "KEYCODE_BACK")
            tap(scroll_to("Test film Two"))
            choose("Play manually")
            choose("Alternate")
            choose("Download this file")
            locate("Play manually")
            if not any("qafilmTwo/Alternate" in f for f in files):
                raise AssertionError(f"Manual download did not use the selected file: {files}")
            offline.set()
            counts = len(sources), len(files)
            choose("Play")
            locate("Playing download")
            rotate(0)
            locate("Playing download")
            screenshot("phone-offline-playback", landscape=False)
            assert counts == (len(sources), len(files)), "Playback contacted a remote source despite the local download"
            shell("input", "keyevent", "KEYCODE_BACK")
            shell("input", "keyevent", "KEYCODE_BACK")
            shell("am", "force-stop", MOBILE_ID)
            if upgrade_apk:
                command("install", "-r", str(upgrade_apk), timeout=180)
            shell("am", "start", "-W", "-n", f"{MOBILE_ID}/{MOBILE_ACTIVITY}")
            locate("Downloads")
            choose("Library")
            locate("Zyntraxis ReSeq Fixture")
            choose("Home")
            choose("Test film One")
            locate("Playing download")
            assert counts == (len(sources), len(files)), "Restarted offline playback requested a remote source"
            if upgrade_apk:
                print(f"Verified in-place update to {upgrade_apk.name} with the saved library and offline file.")
        finally:
            command("reverse", "--remove", "tcp:38792")
            for setting, original in (("size", size), ("density", density)):
                override = re.search(r"Override (?:size|density): (\S+)", original)
                shell("wm", setting, override.group(1) if override else "reset")
            if auto == "0" and rotation in ("0", "1", "2", "3"):
                shell("wm", "user-rotation", "lock", rotation)
            else:
                shell("wm", "user-rotation", "free")
            for setting, original in (("user_rotation", rotation), ("accelerometer_rotation", auto)):
                if original == "null":
                    shell("settings", "delete", "system", setting)
                else:
                    shell("settings", "put", "system", setting, original)
    print("Passed Home, Search, Library, episode playback, both download paths and offline restart.")
    if not catalog_only:
        print("Passed live TV and phone portrait, landscape and tablet layouts.")
    if extended:
        print("Passed collection editing, Sports alerts, player controls and multi-episode TV Mix.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/mobile/debug/app-mobile-x86_64-debug.apk")
    parser.add_argument("--catalog-only", action="store_true", help="Run only the on-demand journeys during development")
    parser.add_argument("--upgrade-apk", type=Path, help="Install an update over the seeded APK and verify the saved library and offline playback")
    parser.add_argument("--providers-only", action="store_true", help="Exercise Xtream and Stalker with local synthetic providers on a fresh emulator install")
    parser.add_argument("--drive-sign-in", action="store_true", help="Check the real Google sign-in prompt without authorizing an account")
    parser.add_argument("--extended", action="store_true", help="Exercise collection editing, TV Mix playback, Sports alerts and VOD controls")
    options = parser.parse_args()
    run(options.adb, options.serial, options.apk, options.catalog_only, options.drive_sign_in, options.extended, options.upgrade_apk, options.providers_only)
