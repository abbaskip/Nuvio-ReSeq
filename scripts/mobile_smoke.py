"""Exercise the mobile catalogue, library, downloads, VOD and live TV on an isolated emulator."""
import argparse
import base64
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


class AccountFixture:
    """Loopback Nuvio account with a browser approval and one interrupted status check."""

    def __init__(self):
        self.approved = False
        self.retry_after = None
        self.exchanges = 0
        self.watch_time = int(time.time() * 1000)
        self.nonce = None
        self.user = {"id": "280c2955-66e5-41f6-b618-30fe62cffdff", "email": "reseq@example.test",
                     "aud": "authenticated", "role": "authenticated", "app_metadata": {"provider": "email"},
                     "user_metadata": {}, "created_at": "2020-01-01T00:00:00Z"}

    def response(self, path, body, address):
        if path == "/approve-test":
            self.approved = self.nonce is not None
            return 200, {"approved": self.approved}
        if path == "/rest/v1/rpc/start_device_login_session":
            if self.nonce or body.get("p_device_type") != "mobile" or not body.get("p_device_nonce"):
                return 400, {"message": "Expected a single mobile sign-in session"}
            self.nonce = body["p_device_nonce"]
            url = address + "/sign-in-test"
            return 200, [{"device_code": "test-device", "user_code": "TEST42", "verification_uri": url,
                          "verification_uri_complete": url, "poll_interval_seconds": 2,
                          "expires_at": (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat()}]
        if path == "/rest/v1/rpc/poll_tv_login_session":
            if body.get("p_code") != "test-device" or body.get("p_device_nonce") != self.nonce:
                return 400, {"message": "Incorrect session"}
            if self.approved:
                if self.retry_after is None:
                    self.retry_after = time.monotonic() + 1
                if time.monotonic() < self.retry_after:
                    return 503, {"message": "Temporary interruption"}
            return 200, [{"status": "approved" if self.approved else "pending", "poll_interval_seconds": 2}]
        if path == "/functions/v1/tv-logins-exchange":
            if not self.approved or body != {"code": "test-device", "device_nonce": self.nonce}:
                return 403, {"message": "Approval required"}
            self.exchanges += 1
            encode = lambda value: base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=").decode()
            claims = {"sub": self.user["id"], "aud": "authenticated", "role": "authenticated",
                      "email": self.user["email"], "iat": int(time.time()), "exp": int(time.time()) + 3600}
            token = ".".join([encode({"alg": "HS256", "typ": "JWT"}), encode(claims), "dGVzdA"])
            return 200, {"access_token": token, "refresh_token": "loopback-only", "token_type": "bearer",
                         "expires_in": 3600, "user": self.user}
        if path == "/auth/v1/user":
            return 200, self.user
        if path == "/rest/v1/rpc/get_sync_owner":
            return 200, self.user["id"]
        if path == "/rest/v1/rpc/sync_get_watch_progress_delta_cursor":
            return 200, 1
        if path == "/rest/v1/rpc/sync_pull_watch_progress":
            return 200, [{"user_id": self.user["id"], "profile_id": 1, "content_id": "qafilmOne", "content_type": "movie",
                          "video_id": "qafilmOne", "position": 45000, "duration": 130000, "last_watched": self.watch_time,
                          "progress_key": "qafilmOne"}]
        if path == "/rest/v1/addons":
            return 200, [{"user_id": self.user["id"], "profile_id": 1, "name": "Offline fixture",
                          "url": address + "/addon/manifest.json", "enabled": True, "sort_order": 0}]
        if path.startswith("/rest/v1/"):
            return 200, []
        return None


@contextmanager
def fixture(account=None):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "live").mkdir()
        address = "http://127.0.0.1:38792"
        channels = ["One", "Two"]
        guide_channels = channels + [f"Zone{index}" for index in range(3, 9)]
        shutil.copyfile(ROOT / "app/src/main/res/drawable/rating_tmdb.png", root / "station.png")
        playlist = ["#EXTM3U"]
        guide = ET.Element("tv")
        now = datetime.now(timezone.utc)
        stamp = "%Y%m%d%H%M%S +0000"
        for channel in guide_channels:
            name = "QA " + channel
            # The real importer reserves .mp4 playlist entries for on-demand movies.
            shutil.copyfile(ROOT / "app/src/test/resources/usenet/test-pattern.mp4", root / "live" / channel)
            playlist += [f'#EXTINF:-1 tvg-id="{channel}" tvg-logo="{address}/station.png" group-title="Fixture",{name}', f"{address}/live/{channel}"]
            ET.SubElement(ET.SubElement(guide, "channel", id=channel), "display-name").text = name
        for channel in guide_channels:
            programme = ET.SubElement(guide, "programme", channel=channel, start=(now-timedelta(minutes=45)).strftime(stamp),
                                      stop=(now+timedelta(hours=2)).strftime(stamp))
            title = {"Zone3": "Zyntraxis ReSeq Fixture: Second episode", "Zone4": "Test film One"}.get(channel, "Programme " + channel)
            ET.SubElement(programme, "title").text = title
            if channel == "Zone3":
                ET.SubElement(programme, "episode-num", system="xmltv_ns").text = "0.1."
            if channel == "Zone4":
                ET.SubElement(programme, "category").text = "Movie"
            ET.SubElement(programme, "desc").text = "Local XMLTV fixture"
        (root / "live.m3u").write_text("\n".join(playlist) + "\n", encoding="utf-8")
        ET.ElementTree(guide).write(root / "guide.xml", encoding="utf-8", xml_declaration=True)
        played = {channel: Event() for channel in guide_channels}
        sources = []
        files = []
        offline = Event()
        movies = [{"id": "qafilm" + name, "type": "movie", "name": "Test film " + name, "releaseInfo": "2018"} for name in channels]
        series = {"id": "qaseries", "type": "series", "name": "Zyntraxis ReSeq Fixture", "releaseInfo": "2019-2024", "videos": [
            {"id": f"qaseries:1:{n}", "season": 1, "episode": n, "title": title, "released": "2020-01-01T00:00:00.000Z"}
            for n, title in enumerate(("Pilot", "Second episode"), start=1)]}
        manifest = {"id": "org.reseq.touchtest", "version": "1.0.0", "name": "Offline fixture",
                    "resources": ["catalog", "meta", "stream"], "types": ["movie", "series"], "idPrefixes": ["qafilm", "qaseries"],
                    "catalogs": [{"type": kind, "id": "offline-" + kind,
                                  "name": ({"movie": "Because you watched The Office", "series": "Because you watched Only Murders in the Building"}[kind]
                                           if account else "Offline fixture " + kind),
                                  "extra": [{"name": "search", "isRequired": False}]} for kind in ("movie", "series")]}
        # 130 seconds of generated H.264 and silent AAC exceeds the shared player's short-placeholder guard.
        clip = (ROOT / "scripts/fixtures/mobile-player.mp4").read_bytes()

        class Handler(SimpleHTTPRequestHandler):
            def account_request(self, path):
                if account is None:
                    return False
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                result = account.response(path, json.loads(body or b"{}"), address)
                if result is None:
                    return False
                status, data = result
                payload = json.dumps(data).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                return True

            def do_POST(self):
                if not self.account_request(urlsplit(self.path).path):
                    self.send_error(404)

            def do_GET(self):
                path = unquote(urlsplit(self.path).path)
                if account and path == "/sign-in-test":
                    page = b'<!doctype html><meta name="viewport" content="width=device-width"><a href="/approve-test">Approve test sign-in</a>'
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html")
                    self.send_header("Content-Length", str(len(page)))
                    self.end_headers()
                    self.wfile.write(page)
                    return
                if self.account_request(path):
                    return
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


def run(adb, serial, apk, catalog_only=False, drive_sign_in=False, extended=False, upgrade_apk=None,
        providers_only=False, account_link=False):
    if not re.fullmatch("emulator-[0-9]+", serial):
        raise ValueError("Use an isolated emulator serial for the touch smoke test")

    def command(*args, timeout=45):
        return subprocess.check_output([adb, "-s", serial, *args], timeout=timeout)

    def shell(*args):
        return command("shell", *args).decode().strip()

    def nodes():
        # Android can return exit code zero with no hierarchy while an Activity is attaching.
        dump = "/sdcard/reseq-smoke.xml"
        for _ in range(4):
            report = shell("uiautomator", "dump", dump)
            if "dumped to" in report.lower():
                try:
                    return ET.fromstring(command("exec-out", "cat", dump)).findall(".//node")
                except ET.ParseError:
                    pass
            time.sleep(.35)
        raise AssertionError(f"Could not read a fresh Android UI hierarchy on {serial}: {report}")

    def reveal_controls(tree):
        surface = next((n for n in tree if n.get("content-desc") == "Video player"), None)
        if surface is not None and not any(n.get("content-desc") == "Back" for n in tree):
            left, top, right, bottom = map(int, re.findall(r"-?\d+", surface.get("bounds")))
            shell("input", "tap", str(left + (right - left) // 7), str(top + (bottom - top) // 3))

    def locate(text=None, editable=None, seconds=60):
        labels = text if isinstance(text, tuple) else (text,)
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            tree = nodes()
            if editable is not None:
                fields = [node for node in tree if node.get("class") == "android.widget.EditText"]
                if len(fields) > editable:
                    return fields[editable]
            else:
                for node in tree:
                    if node.get("class") != "android.widget.EditText" and (node.get("text") in labels or node.get("content-desc") in labels):
                        return node
                if text in {"Back", "Audio", "Speed", "Sources", "Subtitles", "Playing download", "Pause", "Play"}:
                    reveal_controls(tree)
            time.sleep(.25)
        raise AssertionError(f"Missing {text or 'text field'} on {serial}. Visible labels: " +
                             str([n.get("text") or n.get("content-desc") for n in tree if n.get("text") or n.get("content-desc")]))

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", node.get("bounds")))
        shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def choose(text): tap(locate(text))

    def set_player_paused(paused):
        # A hierarchy dump can outlive the transport's auto-hide delay on a slow emulator.
        # Confirm the resulting state and retry the tap if it only revealed the controls.
        action, expected = ("Pause", "Play") if paused else ("Play", "Pause")
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            tree = nodes()
            buttons = {node.get("content-desc") or node.get("text"): node for node in tree}
            if expected in buttons:
                return
            if action in buttons:
                tap(buttons[action])
            else:
                reveal_controls(tree)
            time.sleep(.25)
        raise AssertionError(f"The video player did not switch to {expected}")

    def confirm_video_playback():
        # The generated H.264 fixture is navy. Check Android's composed display as well as its playback clock.
        deadline = time.monotonic() + 45
        first = None
        while time.monotonic() < deadline:
            tree = nodes()
            position = next((n.get("text") for n in tree if re.fullmatch(r"\d+:\d\d", n.get("text", ""))), None)
            if position is None:
                reveal_controls(tree)
                continue
            if first is None:
                first = position
            if position == first:
                continue
            frame = command("exec-out", "screencap")
            width, height, fmt = struct.unpack_from("<III", frame)
            assert fmt in (1, 2), f"Unexpected emulator screenshot format {fmt}"
            header = len(frame) - 4 * width * height
            assert header in (12, 16), f"Unexpected screenshot header {header}"
            pixel = header + 4 * ((height // 2) * width + width // 8)
            red, green, blue = frame[pixel:pixel+3]
            if blue > 80 and red < 40 and green < 40:
                return
        raise AssertionError("The VOD player did not present decoded fixture frames with an advancing clock")


    def choose_after_sheet_opens(text):
        # Nodes become visible while the bottom sheet is still moving; wait for their tap coordinates.
        previous = None
        for _ in range(5):
            node = locate(text)
            bounds = node.get("bounds")
            if bounds == previous and bounds:
                tap(node)
                return
            previous = bounds
        raise AssertionError(f"The bottom sheet did not settle on {text}")

    def enter_first_field(value):
        for _ in range(4):
            field = locate(editable=0)
            if field.get("focused") == "true":
                break
            tap(field)
        else:
            raise AssertionError("The first text field did not gain focus")
        shell("input", "text", value)
        if value not in locate(editable=0).get("text", ""):
            raise AssertionError("The emulator did not enter the expected field value")
        if "mInputShown=true" in shell("dumpsys", "input_method"):
            shell("input", "keyevent", "KEYCODE_BACK")

    def scroll_to(label):
        for attempt in range(30):
            tree = nodes()
            match = next((n for n in tree if label in (n.get("text"), n.get("content-desc"))), None)
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
    account = AccountFixture() if account_link else None
    if account:
        config = ROOT / "app/build/generated/source/buildConfig/mobile/debug/com/nuvio/tv/BuildConfig.java"
        if not ("debug" in apk.name and apk.resolve() == (ROOT / "app/build/outputs/apk/mobile/debug" / apk.name).resolve()
                and config.exists() and re.search(r'SUPABASE_URL = "http://127\.0\.0\.1:3879[12]"', config.read_text())):
            raise ValueError("Build the mobile debug APK against the local account fixture before running --account-link")
    with fixture(account) as (address, port, played, sources, files, offline):
        command("reverse", "tcp:38792", f"tcp:{port}")
        if account:
            command("reverse", "tcp:38791", f"tcp:{port}")
        try:
            shell("wm", "size", "1080x2160")
            shell("wm", "density", "420")
            rotate(0)
            shell("input", "keyevent", "KEYCODE_WAKEUP")
            shell("wm", "dismiss-keyguard")
            shell("am", "start", "-W", "-n", f"{MOBILE_ID}/{MOBILE_ACTIVITY}")
            locate("Catalog sources")
            if extended:
                choose("Flick Pick")
                locate("Pick and play")
                screenshot("phone-flick-pick", landscape=False)
                choose("Back")
            if account or extended:
                choose("Settings")
                def check_tracker_settings():
                    tap(scroll_to("Tracking and TVDB"))
                    scroll_to("MDBList")
                    if account is None and ("release" in apk.name or "universal" in apk.name):
                        scroll_to("Connect MDBList")
                    tap(scroll_to("MDBList ratings and API key"))
                    scroll_to("MDBList API key")
                    choose("Back")
                if not account:
                    check_tracker_settings()
                if account:
                    tap(scroll_to("Nuvio account"))
                    choose("Sign in to Nuvio")
                    locate("TEST42")
                    choose("Open Nuvio sign-in")
                    choose("Approve test sign-in")
                    deadline = time.monotonic() + 10
                    while not account.approved and time.monotonic() < deadline:
                        time.sleep(.1)
                    assert account.approved, "The browser did not confirm the test session"
                    shell("input", "keyevent", "KEYCODE_BACK")
                    if any(n.get("package") != MOBILE_ID and n.get("text") == "Approve test sign-in" for n in nodes()):
                        shell("input", "keyevent", "KEYCODE_BACK")
                    locate(account.user["email"])
                    assert account.retry_after and account.exchanges == 1, "Approval did not recover in the original session"
                    choose("Home")
                    choose("Catalog sources")
                    locate("Offline fixture", seconds=120)
                    shell("input", "keyevent", "KEYCODE_BACK")
                    scroll_to("Continue watching")
                    locate("Watch progress for Test film One", seconds=120)
                    heading = "Because you watched The Office"
                    for width in (840, 945):  # 320dp and 360dp at the configured 420dpi
                        shell("wm", "size", f"{width}x2000")
                        shell("input", "swipe", "400", "480", "400", "1660", "250")
                        for title in (heading, "Because you watched Only Murders in the Building"):
                            row = scroll_to(title)
                            _, top, _, bottom = map(int, re.findall(r"-?\d+", row.get("bounds")))
                            assert bottom - top <= 24 * 420 / 160, f"{title} wrapped at {width}px: {row.get('bounds')}"
                    tap(row)
                    locate("Zyntraxis ReSeq Fixture")
                    shell("input", "keyevent", "KEYCODE_BACK")
                    choose("Settings")
                    if any(n.get("text") == "Sync account now" for n in nodes()):
                        choose("Back")
                    check_tracker_settings()
                    print("Verified direct Nuvio linking before visiting Tracking settings, MDBList, restored account catalogues "
                          "and the partially watched film on Home. Verified the full recommendation heading on one "
                          "line at 320dp and 360dp and navigation into its shelf.")
                    return
                choose("Home")
            if providers_only:
                # Keep the tablet's 1067dp layout within the Pixel 5 emulator's touch surface.
                shell("wm", "size", "1000x1600")
                shell("wm", "density", "150")
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
                pilot = "S1E1 · Fixture pilot"
                choose_after_sheet_opens(pilot)
                for attempt in range(2):
                    try:
                        locate("Audio", seconds=10)
                        break
                    except AssertionError:
                        if attempt or not any(n.get("text") == pilot for n in nodes()):
                            screenshot("provider-episode-tap", landscape=False)
                            raise
                        choose_after_sheet_opens(pilot)
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
                locate("Guide time")
                screenshot("phone-guide", landscape=False)
                rotate(1)
                group = next(n for n in nodes() if n.get("text", "").startswith("Group:"))
                tap(group)
                choose("Fixture")
                locate("Group: Fixture")
                screen = nodes()
                visible_channels = [n for n in screen if n.get("text", "").startswith("QA ")]
                assert len(visible_channels) >= 4, "Expected at least four landscape guide rows"
                assert not any(n.get("text") == "Library" for n in screen), "Main navigation took space from the guide"
                screenshot("landscape-guide", landscape=True)
                rotate(0)
                choose("Calendar")
                choose("Month")
                choose("Next")
                screenshot("phone-calendar", landscape=False)
                rotate(1)
                choose("Leave Live TV")
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
                choose("Leave Live TV")
                shell("wm", "size", "1080x2160")
                shell("wm", "density", "420")
                rotate(0)
                # Start the catalogue journey on Home after the separate tablet navigation journey.
                # A fresh Activity reads the final display configuration on the hosted emulator.
                shell("am", "force-stop", MOBILE_ID)
                shell("am", "start", "-W", "-n", f"{MOBILE_ID}/{MOBILE_ACTIVITY}")
            choose("Catalog sources")
            # Keep Home deterministic while exercising long-press on a real installed catalogue.
            # An emulator with internet access can otherwise fill several pages with public rows.
            source_nodes = nodes()
            cinemeta = next((node for node in source_nodes if node.get("text") == "Cinemeta"), None)
            if cinemeta is not None:
                source_index = source_nodes.index(cinemeta)
                action = next((node for node in source_nodes[source_index + 1:]
                               if node.get("text") in ("Enabled", "Disabled")), None)
                if action is not None and action.get("text") == "Enabled":
                    tap(action)
                    locate("Disabled")
            if any(n.get("text") == "Offline fixture" for n in nodes()):
                shell("input", "keyevent", "KEYCODE_BACK")
            else:
                enter_first_field(address + "/addon/manifest.json")
                tap(scroll_to("Install source"))
            if extended and not catalog_only:
                choose("Reorder")
                locate("Reorder Home rows")
                choose_after_sheet_opens("Custom order")
                film, show = "Offline fixture movie", "Offline fixture series"
                def home_list_order(expected=None):
                    deadline = time.monotonic() + 18
                    while time.monotonic() < deadline:
                        tree = nodes()
                        labels = [n.get("text") for n in tree]
                        if film in labels and show in labels:
                            order = labels.index(film) < labels.index(show)
                            if expected is None or order == expected:
                                return order
                        time.sleep(.3)
                    screenshot("home-order-failure", landscape=False)
                    raise AssertionError(f"Expected film before series: {expected}. Visible labels: {labels}")
                original = home_list_order()
                lower = show if original else film
                choose_after_sheet_opens("Move " + lower + " up")
                home_list_order(expected=not original)
                shell("input", "keyevent", "KEYCODE_BACK")
                choose("Reorder")
                home_list_order(expected=not original)
                screenshot("phone-home-order", landscape=False)
                shell("input", "keyevent", "KEYCODE_BACK")
                choose("Live TV")
                choose("Zyntraxis ReSeq Fixture: Second episode")
                locate("Record programme")
                screenshot("phone-programme-actions", landscape=False)
                choose("Find in Nuvio")
                locate("Episode from the guide", seconds=65)
                locate("Play S1 E2")
                screenshot("phone-guide-episode", landscape=False)
                shell("input", "keyevent", "KEYCODE_BACK")
                locate("Guide time")
                choose("Test film One")
                choose("Find in Nuvio")
                locate("Play manually", seconds=65)
                locate("Test film One")
                shell("input", "keyevent", "KEYCODE_BACK")
                choose("Leave Live TV")
                print("Verified saved Home row order and Guide links to a film and the listed episode.")
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
            if extended:
                locate("Play S1 E1")
                locate("Up next")
                screenshot("phone-series-details", landscape=False)
            tap(scroll_to("S1E2 · Second episode"))
            choose("Play manually")
            if extended:
                locate("Alternate")
                screenshot("phone-manual-sources", landscape=False)
            choose("Alternate")
            choose("Play this file")
            locate("Audio")
            if not any("qaseries:1:2/Alternate" in path for path in files):
                deadline = time.monotonic() + 20
                while not any("qaseries:1:2/Alternate" in path for path in files) and time.monotonic() < deadline:
                    time.sleep(.25)
            assert any("qaseries:1:2/Alternate" in path for path in files), "Selected series episode did not play"
            confirm_video_playback()
            set_player_paused(True)
            set_player_paused(False)
            confirm_video_playback()
            set_player_paused(True)
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
            if not upgrade_apk:
                choose("Play manually")
                local_choice = locate("Play downloaded file")
                primary = locate("Primary")
                def top(node):
                    return int(re.findall(r"-?\d+", node.get("bounds"))[1])
                assert top(local_choice) < top(primary), "Manual sources should put the saved file first"
                files_before = len(files)
                tap(local_choice)
                locate("Playing download")
                assert len(files) == files_before, "Manual local playback downloaded the file again"
                choose("Sources")
                choose_after_sheet_opens("Play downloaded file")
                locate("Playing download")
                assert len(files) == files_before, "Switching to the download in the player fetched a remote file"
                shell("input", "keyevent", "KEYCODE_BACK")
                locate("Play manually")
            offline.set()
            counts = len(sources), len(files)
            # A saved position changes the primary movie action as soon as progress is loaded.
            tap(locate(("Play", "Resume")))
            locate("Playing download")
            rotate(0)
            locate("Playing download")
            confirm_video_playback()
            screenshot("phone-offline-playback", landscape=False)
            assert counts == (len(sources), len(files)), "Playback contacted a remote source despite the local download"
            shell("input", "keyevent", "KEYCODE_BACK")
            shell("input", "keyevent", "KEYCODE_BACK")
            shell("am", "force-stop", MOBILE_ID)
            if upgrade_apk:
                command("install", "-r", str(upgrade_apk), timeout=180)
            shell("am", "start", "-W", "-n", f"{MOBILE_ID}/{MOBILE_ACTIVITY}")
            scroll_to("Downloads")
            choose("Library")
            locate("Zyntraxis ReSeq Fixture")
            choose("Home")
            # The same movie can also be the Home highlight or a catalogue poster.
            # Select its saved-file row next to Manage, which plays the download directly.
            scroll_to("Manage")
            tree = nodes()
            def center_y(node):
                _, top, _, bottom = map(int, re.findall(r"-?\d+", node.get("bounds")))
                return (top + bottom) // 2
            manage_rows = [center_y(n) for n in tree if n.get("text") == "Manage"]
            download = next((n for n in tree if n.get("text") == "Test film One"
                             and any(abs(center_y(n) - y) < 8 for y in manage_rows)), None)
            if download is None:
                raise AssertionError("The completed download was missing from its Home row after restart")
            tap(download)
            locate("Playing download")
            assert counts == (len(sources), len(files)), "Restarted offline playback requested a remote source"
            shell("input", "keyevent", "KEYCODE_BACK")
            choose("Library")
            choose("Downloads")
            locate("Delete Test film One")
            locate("Delete Test film Two")
            screenshot("phone-library-downloads", landscape=False)
            choose("Delete Test film One")
            choose("Keep")
            choose("Delete Test film One")
            choose("Delete download")
            choose("Test film Two")
            locate("Playing download")
            assert counts == (len(sources), len(files)), "Downloads playback requested remote sources"
            shell("input", "keyevent", "KEYCODE_BACK")
            choose("Delete Test film Two")
            choose("Delete download")
            locate("No saved downloads in this profile.")
            print("Verified Library downloads, cancel and confirm deletion, playback of the remaining file and the empty list.")
            if upgrade_apk:
                print(f"Verified in-place update to {upgrade_apk.name} with the saved library and offline file.")
        finally:
            if account:
                command("reverse", "--remove", "tcp:38791")
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
    parser.add_argument("--account-link", action="store_true", help="Test browser-return account sync with a loopback debug build")
    parser.add_argument("--drive-sign-in", action="store_true", help="Check the real Google sign-in prompt without authorizing an account")
    parser.add_argument("--extended", action="store_true", help="Exercise collection editing, TV Mix playback, Sports alerts and VOD controls")
    options = parser.parse_args()
    run(options.adb, options.serial, options.apk, options.catalog_only, options.drive_sign_in, options.extended,
        options.upgrade_apk, options.providers_only, options.account_link)
