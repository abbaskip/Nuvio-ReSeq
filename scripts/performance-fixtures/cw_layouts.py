"""Exercise local Continue Watching through each Home layout and profile on two signed APKs."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
from threading import Thread
from urllib.parse import unquote, urlsplit

from guest_failure import capture
from signed_rollback import (
    APK_HASHES, BASE, DATA, PACKAGE, SERIAL, adb, cold, key, label, point, run,
    seed_settings, tree, write_preferences,
)
from sports_actions import focus_text, stamp, wait


OUT = Path("build/cw-layouts")
ADDON = BASE + "/history-addon"
NAMES = {1: "Amber", 2: "Blue"}
LAYOUTS = ("MODERN", "CLASSIC", "GRID")
DURATION = 2_400_000
SHELF = {"id": "tt9800300", "type": "movie", "name": "Catalogue control"}


def library(profile):
    prefix = NAMES[profile]
    titles = [prefix + " Pause", prefix + " Next"] + [f"{prefix} Done {i:02d}" for i in range(1, 13)]
    return [(f"tt980{profile}{i:03d}", title) for i, title in enumerate(titles)]


TITLES = dict(library(1) + library(2))


class Addon(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
        if path.endswith("/manifest.json"):
            payload = {"id": "com.reseq.historyfixture", "version": "1.0.0", "name": "History fixture",
                       "resources": ["catalog", "meta"], "types": ["movie", "series"], "idPrefixes": ["tt980"],
                       "catalogs": [{"id": "shelf", "type": "movie", "name": "Fixture films"}]}
        elif "/catalog/movie/" in path:
            payload = {"metas": [SHELF]}
        elif "/meta/" in path:
            identifier = path.rsplit("/", 1)[-1].removesuffix(".json")
            if identifier == SHELF["id"]:
                payload = {"meta": SHELF}
            elif identifier in TITLES:
                name = TITLES[identifier]
                series = not name.endswith("Pause")
                meta = {"id": identifier, "type": "series" if series else "movie", "name": name,
                        "releaseInfo": "2024", "description": "Synopsis for " + name}
                if series:
                    meta["videos"] = [{"id": f"{identifier}:1:{ep}", "season": 1, "episode": ep,
                                       "title": f"Chapter {ep}", "released": f"2024-02-0{ep}T12:00:00Z"}
                                      for ep in (1, 2)]
                self.server.requested.add(identifier)
                payload = {"meta": meta}
            else:
                self.send_error(404)
                return
        else:
            self.send_error(404)
            return
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (ConnectionResetError, BrokenPipeError):
            pass


def seed_history(uid):
    profiles = [dict(id=i, name=name, avatarColorHex=color) for (i, name), color
                in zip(NAMES.items(), ("#CC7722", "#3366CC"))]
    files = {"update_settings": {"update_check_frequency": "NEVER", "update_banner_enabled": False},
             "profile_settings": {"profiles_json": json.dumps(profiles), "profile_has_ever_selected": True,
                                  "remember_last_profile_enabled": False, "startup_splash_enabled": False}}
    now = stamp()
    for profile in NAMES:
        suffix = "" if profile == 1 else "_p2"
        progress, watched = {}, set()
        for index, (identifier, name) in enumerate(library(profile)):
            recent = now - 60_000 * (index + 1)
            series = index > 0
            for ep in ((1, 2) if index > 1 else (1,)):
                at = recent - (2 - ep) * 1000
                row = dict(contentId=identifier, contentType="series" if series else "movie",
                           name=name, videoId=identifier, lastWatched=at, duration=DURATION,
                           position=profile * 60_000 + 420_000, addonBaseUrl=ADDON, source="local")
                storage_key = identifier
                if series:
                    row.update(videoId=f"{identifier}:1:{ep}", season=1, episode=ep,
                               episodeTitle=f"Chapter {ep}", position=DURATION)
                    storage_key = f"{identifier}_s1e{ep}"
                    watched.add(json.dumps(dict(contentId=identifier, contentType="series", title=name,
                                                season=1, episode=ep, watchedAt=at)))
                progress[storage_key] = row
        assert len(watched) == 25 and len(progress) == 26
        files["addon_preferences" + suffix] = {"installed_addon_urls_ordered": json.dumps([ADDON])}
        files["experience_settings" + suffix] = {"addon_setup_skipped": True}
        # An unauthenticated Trakt selection intentionally does not display local Nuvio history.
        files["trakt_settings" + suffix] = {"watch_progress_source": "NUVIO_SYNC", "library_source_mode": "LOCAL"}
        files["watch_progress_preferences" + suffix] = {"watch_progress_storage_version": 1}
        files["watch_progress_recent_preferences" + suffix] = {"watch_progress_map": json.dumps(progress)}
        files["watched_items_preferences" + suffix] = {"watched_items": watched}
    seed_settings(uid, {}, files)


def projection(profile):
    paths = adb("shell", "find", DATA + "/files", "-name", f"projection_{profile}_NUVIO_SYNC.json").splitlines()
    if len(paths) != 1:
        return {}
    document = json.loads(adb("exec-out", "cat", paths[0]))
    assert document["profileId"] == profile
    members = document.get("presentedMembers") or {}
    visible = {identifier: value for identifier, value in members.items()
               if value.get("inProgress") or value.get("nextUp")}
    ((movie, _), (series, _), *finished) = library(profile)
    pause = (visible.get(movie) or {}).get("inProgress") or {}
    next_ep = (visible.get(series) or {}).get("nextUp") or {}
    final_episodes = {identifier for identifier, records in document.get("completed", {}).items()
                      if any(r.get("season") == 1 and r.get("episode") == 2 for r in records.values())}
    return {"visible": sorted(visible), "finalesInHistory": sorted(final_episodes),
            "resumeMs": pause.get("position"), "durationMs": pause.get("duration"),
            "nextVideo": next_ep.get("videoId"), "nextEpisode": next_ep.get("episode")}


def home_row(profile):
    nodes = list(tree().iter("node"))
    headings = [n for n in nodes if label(n).casefold() == "continue watching"]
    if not headings:
        return None
    below = min(point(h)[1] for h in headings)
    cards = {}
    for node in nodes:
        name = label(node)
        if name in TITLES.values() and point(node)[1] > below:
            cards[name] = point(node)[0]
    expected = {v for _, v in library(profile)[:2]}
    if set(cards) != expected:
        return None
    focus = next((n for n in nodes if n.get("focused") == "true"), None)
    if focus is None:
        return None
    labels = [label(n) for n in focus.iter() if label(n)]
    focused = next((name for name in labels if name in cards), None)
    return {"order": sorted(cards, key=cards.get), "focus": focused or (labels or ["unlabelled control"])[0]}


def exercise(phase, layout, profile, server, result):
    cold()
    wait(lambda: set(NAMES.values()) <= {label(n) for n in tree().iter("node")}, "Profile picker missing")
    focus_text(NAMES[profile])
    key("DPAD_CENTER")
    rows = library(profile)
    (movie, movie_name), (series, _), *finished = rows
    initial = wait(lambda: home_row(profile), "Home did not show both positive controls", 65)
    result["home"] = initial

    expected = {"visible": sorted((movie, series)), "finalesInHistory": sorted(i for i, _ in finished),
                "resumeMs": profile * 60_000 + 420_000, "durationMs": DURATION,
                "nextVideo": series + ":1:2", "nextEpisode": 2}
    wait(lambda: projection(profile) == expected, "The settled library or episode mapping did not match", 75)
    result["projection"] = expected
    assert all(identifier in server.requested for identifier, _ in rows[1:]), "Missing fixture episode metadata"
    assert initial["order"] == [name for _, name in rows[:2]]
    (OUT / f"{phase}-{layout}-{profile}.png").write_bytes(run("adb", "-s", SERIAL, "exec-out", "screencap", "-p"))

    focus_text(movie_name)
    adb("shell", "input", "keyevent", "--longpress", "KEYCODE_DPAD_CENTER")
    wait(lambda: "Go to details" in {label(n) for n in tree().iter("node")}, "Card menu missing")
    focus_text("Go to details")
    key("DPAD_CENTER")
    def in_details():
        labels = {label(n) for n in tree().iter("node")}
        return "Synopsis for " + movie_name in labels and "Continue Watching" not in labels
    wait(in_details, "Expected movie detail did not open")
    key("BACK")
    def back_on_movie():
        row = home_row(profile)
        return row if row and row["focus"] == movie_name else None
    returned = wait(back_on_movie, "Back did not focus the selected movie", 20)
    assert returned["order"] == initial["order"] and projection(profile) == expected
    result["returnFromDetail"] = returned


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
    server = ThreadingHTTPServer(("127.0.0.1", 38791), Addon)
    server.requested = set()
    Thread(target=server.serve_forever, daemon=True).start()
    evidence = {}
    try:
        for phase, folder in (("beta", "build/reference-apks"), ("integration", "build/guide-apks")):
            apk, = Path(folder).rglob("*-x86_64.apk")
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            if phase == "beta":
                assert digest == APK_HASHES["10.12"]
            assert "Success" in adb("install", "-r", str(apk))
            uid = adb("shell", "stat", "-c", "%u", DATA)
            cases = {}
            evidence[phase] = {"apkSha256": digest, "uid": uid, "cases": cases}
            if phase == "beta":
                seed_history(uid)
            else:
                assert uid == evidence["beta"]["uid"] and projection(2), "Upgrade lost the beta profile"
            for layout in LAYOUTS:
                adb("shell", "am", "force-stop", PACKAGE)
                settings = {"selected_layout": layout, "has_chosen_layout": True}
                write_preferences(uid, {"layout_settings": settings, "layout_settings_p2": settings})
                for profile in NAMES:
                    case = f"{layout}-{profile}"
                    result = cases[case] = {}
                    print("Checking", phase, case, flush=True)
                    try:
                        exercise(phase, layout, profile, server, result)
                    except Exception as failure:
                        capture(OUT / (phase + "-" + case))
                        result["error"] = str(failure)
                        result["observedProjection"] = projection(profile)
                        result["metadataRequested"] = sorted(server.requested)
                        values = [label(n) for n in tree().iter("node") if label(n)]
                        result["visibleMarkers"] = [v for v in values if v in TITLES.values() or v in NAMES.values()
                                                    or re.search("Continue Watching|Go to details|Profile|Who.s watching|Chapter|Synopsis", v, re.I)]
                    (OUT / "results.json").write_text(json.dumps(evidence, indent=2))
                    print(phase, case, result, flush=True)
                    if "error" in result:
                        raise AssertionError(f"{phase} {case}: {result['error']}")
        assert evidence["beta"]["cases"] == evidence["integration"]["cases"], "Signed builds differed"
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
