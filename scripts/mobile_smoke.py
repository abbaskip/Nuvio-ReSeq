"""Exercise the mobile launcher and real M3U/guide/player flow on an isolated emulator."""
import argparse
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
import time
import xml.etree.ElementTree as ET

from mobile_parity import MOBILE_ACTIVITY, MOBILE_ID, ROOT


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

        class Handler(SimpleHTTPRequestHandler):
            def do_GET(self):
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
                yield address, server.server_port, played
            finally:
                server.shutdown()
                thread.join()


def run(adb, serial):
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
                    if node.get("text") == text or node.get("content-desc") == text:
                        return node
            time.sleep(.25)
        raise AssertionError(f"Missing {text or 'text field'} on {serial}. Visible labels: " +
                             str([n.get("text") for n in tree if n.get("text")]))

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", node.get("bounds")))
        shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def choose(text): tap(locate(text))

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

    apk = ROOT / "app/build/outputs/apk/mobile/debug/app-mobile-x86_64-debug.apk"
    command("install", "-r", str(apk), timeout=180)
    size = shell("wm", "size")
    density = shell("wm", "density")
    rotation = shell("settings", "get", "system", "user_rotation")
    auto = shell("settings", "get", "system", "accelerometer_rotation")
    with fixture() as (address, port, played):
        command("reverse", "tcp:38792", f"tcp:{port}")
        try:
            shell("wm", "size", "1080x2160")
            shell("wm", "density", "420")
            shell("settings", "put", "system", "accelerometer_rotation", "0")
            shell("settings", "put", "system", "user_rotation", "0")
            shell("input", "keyevent", "KEYCODE_WAKEUP")
            shell("wm", "dismiss-keyguard")
            shell("am", "start", "-W", "-n", f"{MOBILE_ID}/{MOBILE_ACTIVITY}")
            choose("Setup")
            added_playlist = source("Add M3U playlist", "ReSeqFixtureM3U", address + "/live.m3u")
            added_guide = source("Add XMLTV guide", "ReSeqFixtureXMLTV", address + "/guide.xml")
            if not (added_playlist or added_guide):
                choose("Refresh sources")
            choose("Guide")
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
            shell("settings", "put", "system", "user_rotation", "1")
            choose("Discover")
            choose("Filters")
            locate("Title contains")
            screenshot("landscape-filters", landscape=True)
            shell("input", "keyevent", "KEYCODE_BACK")
            shell("wm", "size", "1600x2560")
            shell("wm", "density", "240")
            choose("Sports")
            locate("On TV now")
            screenshot("tablet-sports", landscape=True)
            choose("Calendar")
            locate("Month")
            screenshot("tablet-calendar", landscape=True)
        finally:
            command("reverse", "--remove", "tcp:38792")
            for setting, original in (("size", size), ("density", density)):
                override = re.search(r"Override (?:size|density): (\S+)", original)
                shell("wm", setting, override.group(1) if override else "reset")
            for setting, original in (("user_rotation", rotation), ("accelerometer_rotation", auto)):
                if original == "null":
                    shell("settings", "delete", "system", setting)
                else:
                    shell("settings", "put", "system", setting, original)
    print("Phone portrait, landscape, tablet and live channel navigation passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    options = parser.parse_args()
    run(options.adb, options.serial)
