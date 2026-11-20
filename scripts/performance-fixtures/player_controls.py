"""Drive the signed VOD player with a seekable multi-track file and two selectable addon streams."""
import hashlib
from http.server import ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from threading import Thread
from urllib.parse import unquote, urlsplit

from guest_failure import capture
from shared_features import Fixture, playback_state, seed_mix, series
from signed_rollback import APK_HASHES, BASE, DATA, PACKAGE, adb, key, label, tree, write_preferences
from sports_actions import wait


OUT = Path("build/player-controls")
PRIMARY = "Guest episode source"
MIRROR = "Guest mirror source"


def test_media():
    with TemporaryDirectory() as temp:
        folder = Path(temp)
        srt = folder / "captions.srt"
        srt.write_text("1\n00:00:00,100 --> 00:04:59,900\nFixture caption\n")
        path = folder / "controls.mp4"
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                        "testsrc2=size=256x144:rate=25", "-f", "lavfi", "-i", "sine=frequency=440",
                        "-f", "lavfi", "-i", "sine=frequency=660", "-i", str(srt),
                        "-map", "0:v", "-map", "1:a", "-map", "2:a", "-map", "3:s", "-t", "300",
                        "-c:v", "libx264", "-preset", "ultrafast", "-g", "50", "-b:v", "200k",
                        "-c:a", "aac", "-b:a", "64k", "-c:s", "mov_text", "-metadata:s:a:0", "language=eng",
                        "-metadata:s:a:1", "language=spa", "-metadata:s:s:0", "language=fra",
                        "-movflags", "+faststart", str(path)], check=True)
        return path.read_bytes()


class PlayerAddon(Fixture):
    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
        if "/stream/series/" in path:
            episode = path.rsplit(":", 1)[-1].removesuffix(".json")
            assert episode in ("1", "2", "3")
            data = json.dumps({"streams": [
                {"name": name, "title": f"Guest {origin} episode {episode}",
                 "url": f"{BASE}/controls/{origin}-{episode}.mp4"}
                for name, origin in ((PRIMARY, "main"), (MIRROR, "mirror"))]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
        elif path.startswith("/controls/"):
            data = self.server.media
            start, end = 0, len(data) - 1
            header = self.headers.get("Range")
            if header:
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", header.strip())
                if not match or int(match[1]) > end:
                    self.send_error(416)
                    return
                start = int(match[1])
                end = min(end, int(match[2])) if match[2] else end
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
            else:
                self.send_response(200)
            self.server.played.add(path.rsplit("/", 1)[-1])
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Type", "video/mp4")
            data = memoryview(data)[start:end + 1]
        else:
            return super().do_GET()
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (ConnectionResetError, BrokenPipeError):
            pass


def visible():
    return {label(n) for n in tree().iter("node") if label(n)}


def press(name, reveal=False):
    """Activate the visible TV control before it fades, even when the video never idles."""
    for _ in range(3 if reveal else 1):
        if reveal:
            key("DPAD_UP", .2)
        result = adb("shell", "am", "instrument", "-w", "-e", "mode", "playerAction",
                     "-e", "actionLabel", "'" + name + "'", "com.nuvio.perf.observer/.PickerObserver")
        if "activated=" + name in result:
            return
    raise AssertionError("No selectable player action " + name)


def selected(name):
    for node in tree().iter("node"):
        if node.get("selected") == "true" or node.get("checked") == "true":
            if any(name.casefold() in label(n).casefold() for n in node.iter()):
                return True
    return False


def playing():
    state = playback_state()
    return state if state and state[0] == "PLAYING" else None


def checks(server, save):
    save("tvMixAndPlayPause", series(server))
    assert any(name.startswith("main-") for name in server.played)
    before = wait(playing, "Media session did not return to play")[1]
    key("MEDIA_FAST_FORWARD")
    forward = wait(lambda: s if (s := playing()) and s[1] >= before + 8_000 else None,
                   "Forward key did not seek ten seconds", 5)[1]
    key("MEDIA_REWIND")
    back = wait(lambda: s if (s := playing()) and s[1] < forward - 6_000 else None,
                "Rewind key did not move the playhead back", 5)[1]
    save("remoteSeekMs", [before, forward, back])

    press("Audio tracks", reveal=True)
    wait(lambda: {"English", "Spanish"} <= visible(), "Both audio tracks were not offered")
    press("Spanish")
    # Some player configurations close the picker on applying a track.
    if not {"English", "Spanish"} <= visible():
        press("Audio tracks", reveal=True)
    wait(lambda: selected("Spanish"), "Spanish was not selected")
    save("selectedAudio", "Spanish")
    key("BACK")

    key("CAPTIONS")
    press("French")
    press("Built in")
    wait(lambda: selected("Built in"), "The internal subtitle did not become selected")
    save("selectedSubtitle", "French, built in")
    key("BACK")
    key("INFO")
    wait(lambda: {"Spanish", "French", "Embedded"} <= visible(), "Stream info did not reflect both actual tracks")
    save("streamInfo", ["Spanish", "French", "Embedded"])
    key("BACK")

    press("More actions", reveal=True)
    press("Playback speed")
    press("1.5x")
    def speed_is_applied():
        return re.search(r"state=.{0,48}?, position=\d+[^}\n]*?speed=1\.5\b",
                         adb("shell", "dumpsys", "media_session")) is not None
    wait(speed_is_applied, "Android did not report playback at 1.5x", 12)
    save("mediaSessionSpeed", 1.5)

    press("Sources", reveal=True)
    wait(lambda: {PRIMARY, MIRROR} <= visible(), "The player did not offer both addon streams", 25)
    press(MIRROR)
    wait(lambda: any(name.startswith("mirror-") for name in server.played) and playing(),
         "Selected mirror did not supply playing media")
    save("requestedStreams", sorted(server.played))


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell", "getprop", "ro.kernel.qemu") == "1"
    adb("root")
    adb("wait-for-device")
    assert adb("shell", "id", "-u") == "0" and not adb("shell", "pm", "list", "packages", PACKAGE)
    OUT.mkdir(parents=True, exist_ok=True)
    adb("shell", "wm", "size", "1920x1080")
    adb("shell", "wm", "density", "320")
    key("WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("reverse", "tcp:38791", "tcp:38791")
    assert "Success" in adb("install", "build/picker-observer/observer.apk")
    server = ThreadingHTTPServer(("127.0.0.1", 38791), PlayerAddon)
    server.media = test_media()
    server.requests = {}
    Thread(target=server.serve_forever, daemon=True).start()
    failures = []
    try:
        for phase, folder in (("beta", "build/reference-apks"), ("integration", "build/guide-apks")):
            apk, = Path(folder).rglob("*-x86_64.apk")
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            if phase == "beta":
                assert digest == APK_HASHES["10.12"]
            assert "Success" in adb("install", "-r", str(apk))
            assert "Success" in adb("shell", "pm", "clear", PACKAGE)
            uid = adb("shell", "stat", "-c", "%u", DATA)
            seed_mix(uid)
            write_preferences(uid, {"update_settings": {"update_check_frequency": "NEVER", "update_banner_enabled": False}})
            server.played = set()
            result = {"apkSha256": digest}
            def save(name, value):
                result[name] = value
                (OUT / (phase + ".json")).write_text(json.dumps(result, indent=2))
                print(phase, name, value, flush=True)
            try:
                checks(server, save)
            except Exception as exc:
                capture(OUT / phase)
                save("error", str(exc))
                markers = ("Spanish", "English", "French", "Built in", "Embedded", "Sources", "Playback speed",
                           "Audio", "Subtitles", "Chapter", "Guest", "Loading", "Stream", "More actions", "Speed")
                save("visibleMarkers", sorted(v for v in visible() if any(m.casefold() in v.casefold() for m in markers)))
                failures.append((phase, str(exc)))
    finally:
        server.shutdown()
        server.server_close()
    assert not failures, failures


if __name__ == "__main__":
    main()
