"""Exercise TV Mix, normal addon playback, metadata ratings and Advanced Discover in the signed app."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
from threading import Thread
import time
from urllib.parse import urlsplit

from signed_rollback import BASE, DATA, PACKAGE, adb, cold, key, label, point, rail_item, seed_settings, select, tree

OUT = Path("build/shared-features")
SERIES = "tt9900998"
MOVIE = "tt9900999"
SHOW = "Fixture mix series"
FILM = "Fixture ratings film"
MIX = "Fixture episode mix"
ADDON = BASE + "/guest-addon"
RESULT = {}
STEP = ""


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        path = urlsplit(self.path).path
        group = next((name for name in ("manifest","catalog","meta","stream","screen-media") if name in path),"other")
        self.server.requests[group] = self.server.requests.get(group,0)+1
        if path.startswith("/screen-media/"):
            self.server.played.add(path.rsplit("/", 1)[-1])
            data = self.server.media
            mime = "video/mp2t"
        else:
            if path.endswith("/manifest.json"):
                payload = {"id":"com.reseq.guest.features", "name":"Fixture addon", "version":"1.0.0",
                           "resources":["meta","catalog","stream"], "types":["series","movie"],
                           "idPrefixes":[SERIES,MOVIE], "catalogs":[{"type":"movie","id":"fixture","name":"Guest films"}]}
            elif "/catalog/movie/" in path:
                payload = {"metas":[{"id":MOVIE,"name":FILM,"type":"movie","releaseInfo":"2025"}]}
            elif "/meta/movie/" in path:
                payload = {"meta":{"id":MOVIE,"name":FILM,"type":"movie","imdbRating":"8.3",
                                    "description":"Guest metadata rating check","releaseInfo":"2025"}}
            elif "/meta/series/" in path:
                payload = {"meta":{"id":SERIES,"name":SHOW,"type":"series","releaseInfo":"2024","videos":[
                    {"id":f"{SERIES}:1:{n}","season":1,"episode":n,"title":f"Guest episode {n}",
                     "released":"2024-02-01T12:00:00Z","imdbRating":"8.3"} for n in (1,2,3)]}}
            elif "/stream/series/" in path:
                ep = re.search(r"(?:%3A|:)([123])\.json", path, re.I)
                if ep is None:
                    self.send_error(404)
                    return
                payload = {"streams":[{"name":"Guest episode source","title":"Playable guest media",
                                       "url":f"{BASE}/screen-media/episode-{ep[1]}.ts"}]}
            else:
                self.send_error(404)
                return
            data = json.dumps(payload).encode()
            mime = "application/json"
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (ConnectionResetError, BrokenPipeError):
            pass


def labels():
    return {label(n) for n in tree().iter("node")}


def wait_until(predicate, seconds=25):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = predicate()
        if value:
            return value
        time.sleep(.35)
    raise AssertionError("Expected visible or playback condition timed out")


def tap(phrase):
    for node in tree().iter("node"):
        if label(node) == phrase:
            x, y = point(node)
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(.5)
            return True
    return False


def playback_state():
    session = adb("shell", "dumpsys", "media_session")
    found = re.search(r"state=(?:(PLAYING|PAUSED)\([23]\)|([23])), position=(\d+)", session)
    return None if not found else (found[1] or {"2":"PAUSED","3":"PLAYING"}[found[2]], int(found[3]))


def series(server):
    global STEP
    STEP = "open mix"
    cold()
    for _ in range(12):
        visible = labels()
        if MIX in visible:
            focus = next((item for item in tree().iter("node") if item.get("focused") == "true"), None)
            if focus is not None and any(label(n) == MIX for n in focus.iter()):
                key("DPAD_CENTER")
            else:
                tap(MIX)
            if "Next random episode" in labels():
                break
        key("DPAD_DOWN")
    wait_until(lambda: any("Guest episode" in text for text in labels()))
    STEP = "choose another episode"
    select("Remix")
    wait_until(lambda: any("Guest episode" in text for text in labels()))
    STEP = "open selected episode"
    select("Play")
    STEP = "choose addon stream"
    def choose_source():
        if server.played:
            return True
        if "Guest episode source" in labels():
            select("Guest episode source")
            return True
        return False
    wait_until(choose_source, 35)
    def position(expected):
        actual = playback_state()
        return actual if actual and actual[0] == expected else None
    STEP = "observe playing position"
    first = wait_until(lambda: position("PLAYING"))
    second = wait_until(lambda: (p if (p := position("PLAYING")) and p[1] >= first[1]+600 else None))
    key("MEDIA_PAUSE")
    STEP = "observe pause and resume"
    paused = wait_until(lambda: position("PAUSED"))
    key("MEDIA_PLAY")
    wait_until(lambda: position("PLAYING"))
    assert server.played
    return {"servedEpisodes":sorted(server.played),"firstPlayingMs":first[1],"advancedMs":second[1],
            "pausedMs":paused[1],"resumed":True}


def rating():
    global STEP
    STEP = "read rating on detail"
    cold()
    adb("shell","am","start","-a","android.intent.action.VIEW","-d","nuvio://movie/"+MOVIE,"-p",PACKAGE)
    wait_until(lambda: any(FILM in text for text in labels()))
    wait_until(lambda: any(re.search(r"\b8\.3\b", text) for text in labels()))
    return {"renderedFixtureTitle":True,"displayedProviderRating":"8.3"}


def discover():
    global STEP
    STEP = "open Search from the sidebar"
    cold()
    rail_item("Search")
    STEP = "open Advanced Discover"
    wait_until(lambda: "Advanced Discover" in labels())
    assert tap("Advanced Discover")
    wait_until(lambda: "Show results" in labels())
    STEP = "render search results"
    assert tap("Show results")
    # A populated grid has actual poster buttons to the right of the filter column.
    def cards():
        nodes = list(tree().iter("node"))
        return [n for n in nodes if point(n)[0] > 870 and n.get("focusable") == "true"
                and n.get("clickable") == "true" and any(label(c) for c in n.iter())]
    results = wait_until(cards, 40)
    assert tap("Reset")
    assert "Show results" in labels()
    return {"visibleResultCards":len(results),"resetReturnedToFilters":True}


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell","getprop","ro.kernel.qemu") == "1"
    adb("root")
    adb("wait-for-device")
    assert adb("shell","id","-u") == "0" and not adb("shell","pm","list","packages",PACKAGE)
    OUT.mkdir(parents=True, exist_ok=True)
    apk, = Path("build/guide-apks").rglob("*-x86_64.apk")
    assert "Success" in adb("install",str(apk))
    uid = adb("shell","stat","-c","%u",DATA)
    adb("shell","wm","size","1920x1080")
    adb("shell","wm","density","320")
    key("WAKEUP")
    adb("shell","wm","dismiss-keyguard")
    adb("reverse","tcp:38791","tcp:38791")
    show = {"id":SERIES,"name":SHOW,"type":"series","imdbId":SERIES,"addonBaseUrl":ADDON}
    seed_settings(uid, {}, {"addon_preferences":{"installed_addon_urls_ordered":json.dumps([ADDON])},
                            "tv_mix":{"mix:guest-mix":json.dumps({"id":"guest-mix","name":MIX,"shows":[show]})}})
    server = ThreadingHTTPServer(("127.0.0.1",38791),Fixture)
    server.media = Path("build/guide-delivery/live.ts").read_bytes()
    server.played = set()
    server.requests = {}
    Thread(target=server.serve_forever,daemon=True).start()
    try:
        for name, check in (("tvMixAddonPlayer",lambda:series(server)),("metadataRating",rating),("advancedDiscover",discover)):
            try:
                RESULT[name] = check()
            except Exception as exc:
                allowed = ("Home","Search","TV Mix","Next random episode","Remix","Play",MIX,FILM,
                           "Guest episode","Fixture addon","Released","Advanced Discover","Show results","Reset",
                           "No results","IMDb","Sources","Default","Addons")
                visible = labels()
                RESULT[name] = {"error":str(exc),"step":STEP,"requests":dict(server.requests),
                                "visibleMarkers":[a for a in allowed if any(a in item for item in visible)]}
            (OUT/"results.json").write_text(json.dumps(RESULT,indent=2))
            print(name, RESULT[name],flush=True)
    finally:
        server.shutdown()
        server.server_close()
    assert all("error" not in result for result in RESULT.values())


if __name__ == "__main__":
    main()
