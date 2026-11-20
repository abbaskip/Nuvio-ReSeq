"""Exercise cached basketball results and the two remote channel shortcuts in a signed Android TV app."""
from collections import Counter
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import sys
import time
from threading import Thread

from signed_rollback import BASE, DATA, PACKAGE, SERIAL, adb, cold, key, label, point, run, seed_settings, select, tree

OUT = Path("build/sports-actions")
CACHE = DATA + "/cache/sports"
LEAGUE = "basketball/womens-college-basketball"
EVENT = "900007001"
TEAMS = ("Fixture Robins", "Fixture Owls")
CHANNELS = ("Fixture Arena North", "Fixture Arena South")
ACTION = "Open in multi view"
RESULTS = {}


def record(name, value):
    RESULTS[name] = value
    (OUT / "results.json").write_text(json.dumps(RESULTS, indent=2))
    print(name, value, flush=True)


def screenshot(name):
    (OUT / (name + ".png")).write_bytes(run("adb", "-s", SERIAL, "exec-out", "screencap", "-p"))


def visible():
    return [label(n) for n in tree().iter("node") if label(n)]


def wait(check, description, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(.2)
    raise AssertionError(description)


def exists(name):
    return adb("shell", "sh", "-c", f"'test -s {CACHE}/{name} && echo present || echo missing'") == "present"


def put(uid, name, obj):
    temp = Path("build") / ("sports-actions-" + name)
    temp.write_text(json.dumps(obj))
    adb("shell", "mkdir", "-p", CACHE)
    adb("push", str(temp), CACHE + "/" + name)
    adb("shell", "chown", "-R", uid + ":" + uid, CACHE)
    adb("shell", "restorecon", "-R", CACHE)


def stamp():
    return int(adb("shell", "date", "+%s")) * 1000


def publish(uid, start, confirmed, *, unseen=False):
    now = stamp()
    def game(identifier, teams, at, final):
        sides = [{"homeAway": ha, "team": {"id": str(i), "name": team, "displayName": team,
                  "shortDisplayName": team, "abbreviation": team.split()[-1][:3].upper()}}
                 for i, (ha, team) in enumerate(zip(("away","home"), teams), 1)]
        if final:
            for side, score in zip(sides, ("72","70")):
                side.update(score=score, winner=(score == "72"))
        return {"id": identifier, "name": " at ".join(teams),
                "date": datetime.fromtimestamp(at/1000, timezone.utc).strftime("%Y-%m-%dT%H:%MZ"),
                "competitions": [{"competitors": sides, "broadcasts": [{"names": list(CHANNELS)}],
                    "status": {"type": {"state": "post" if final else "pre", "description": "Final" if final else "Scheduled",
                                         "shortDetail": "Final" if final else "Scheduled"}}}]}
    events = [game(EVENT, TEAMS, start, confirmed)]
    if unseen:
        events.append(game("900007002", ("Early Larks", "Early Ducks"), start + 15*60_000, False))
    put(uid, "base-feed.json", {"schemaVersion":1, "generatedAt":now, "entries":[
        {"provider":"espn", "leagueId":LEAGUE, "fetchedAt":now, "payload":{"events":events}}]})
    put(uid, "base-feed-state.json", {"url":"https://abbaskip.github.io/Nuvio-ReSeq/sports-base.json", "checkedAt":now})


def open_sports():
    cold()
    tap("Sports Hub")
    wait(lambda: any("All Events" in v or "TV Now" in v for v in visible()), "Sports Hub did not open")
    tap("All Events")


def tap(text):
    nodes = [n for n in tree().iter("node") if text.casefold() in label(n).casefold()]
    if not nodes:
        raise AssertionError("Missing " + text)
    x, y = point(min(nodes, key=lambda n: len(label(n))))
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(.5)


def scores_on_page():
    values = " ".join(visible())
    return all(team in values for team in TEAMS) and "FINAL" in values.upper() and all(
        re.search(r"\b" + score + r"\b", values) for score in ("72", "70"))


def restart(uid):
    # The isolated guest cannot download a newer real scoreboard between the two cold launches.
    adb("shell", "settings", "put", "global", "http_proxy", "127.0.0.1:9")
    seed_settings(uid, {"sports_mine":json.dumps({"setUp":True}), "sports":json.dumps({"region":"us"})})
    start = stamp() - 4 * 3_600_000
    publish(uid, start, True)
    open_sports()
    wait(scores_on_page, "The original confirmed game and both scores were not rendered")
    wait(lambda: exists("college-scores.json"), "The app did not persist the observed result")
    record("firstConfirmed", {"away":72,"home":70,"state":"FINAL"})
    screenshot("before-force-stop")
    adb("shell","am","force-stop",PACKAGE)
    publish(uid, start, False, unseen=True)
    # An absent game in the 512-item first-paint page must not mask a regression in the independent score cache.
    put(uid, "page-snapshot.json", {"version":1, "savedAt":stamp(), "items":[]})
    open_sports()
    wait(scores_on_page, "A force-stop lost the known final when the schedule reverted to pregame")
    wait(lambda: any("status pending" in v.casefold() for v in visible()), "The never-scored overdue fixture was not pending")
    record("afterStaleSchedule", {"away":72,"home":70,"state":"FINAL","unseenOverdue":"Status pending"})
    screenshot("after-force-stop")


class ChannelProvider(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        name = self.path
        self.server.requests[name] += 1
        if name == "/channels.m3u":
            data = ("#EXTM3U\n" + "".join(f'#EXTINF:-1 tvg-id="arena{i}" group-title="Sport",{ch}\n'
                    f'{BASE}/arena{i}.ts\n' for i, ch in enumerate(CHANNELS))).encode()
            mime = "audio/x-mpegurl"
        elif name in ("/arena0.ts", "/arena1.ts"):
            data = self.server.media
            mime = "video/mp2t"
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass


def held_menu():
    adb("shell", "input", "keyevent", "--longpress", "KEYCODE_DPAD_CENTER")
    wait(lambda: ACTION in visible(), "Holding the remote OK button did not open channel actions", 8)


def focus():
    return next((n for n in tree().iter("node") if n.get("focused") == "true"), None)


def focus_text(text):
    for _ in range(14):
        root = tree()
        current = next((n for n in root.iter("node") if n.get("focused") == "true"), None)
        if current is not None and any(text in label(n) for n in current.iter()):
            return
        target = next((n for n in root.iter("node") if text in label(n)), None)
        if current is None or target is None:
            key("DPAD_DOWN")
            continue
        dx = point(target)[0] - point(current)[0]
        dy = point(target)[1] - point(current)[1]
        if abs(dy) > 25:
            key("DPAD_DOWN" if dy > 0 else "DPAD_UP")
        else:
            key("DPAD_RIGHT" if dx > 0 else "DPAD_LEFT")
    raise AssertionError("Remote focus did not reach " + text)


def open_pane(server, channel, origin):
    i = CHANNELS.index(channel)
    before = server.requests[f"/arena{i}.ts"]
    screenshot(origin + "-actions")
    select(ACTION)
    wait(lambda: server.requests[f"/arena{i}.ts"] > before, "Pane did not request the held channel")
    # Wake the pane overlays, whose station label and sound marker may already have faded during the UI dump.
    key("DPAD_LEFT", .2)
    def pane():
        values = visible()
        return channel in values and "P1" in values and any(v in values for v in ("SOUND", "Sound: P1"))
    wait(pane, "The held station was not in audible pane 1", 10)
    screenshot(origin + "-pane")
    record(origin + "Picked", {"channel":channel, "pane":1, "mediaRequests":server.requests[f"/arena{i}.ts"] - before})
    for _ in range(4):
        key("BACK", .7)
        values = visible()
        if origin == "guide" and "Multiview" in values or origin == "sports" and "WATCH ON YOUR CHANNELS" in values:
            return
    raise AssertionError("Back did not restore " + origin)


def shortcuts(uid):
    server = ThreadingHTTPServer(("127.0.0.1",38791), ChannelProvider)
    server.media = Path("build/guide-delivery/live.ts").read_bytes()
    server.requests = Counter()
    Thread(target=server.serve_forever, daemon=True).start()
    adb("reverse","tcp:38791","tcp:38791")
    start = stamp() + 20 * 60_000
    source = {"id":"shortcuts","name":"Fixture sports","url":BASE+"/channels.m3u","useEmbeddedEpg":False}
    mapping = {name.lower():f"shortcuts|arena{i}|{name}" for i,name in enumerate(CHANNELS)}
    seed_settings(uid, {"playlists":json.dumps([source]),"sports_mine":json.dumps({"setUp":True}),
                        "sports":json.dumps({"region":"us","networkChannels":mapping})})
    publish(uid,start,False)
    try:
        cold()
        tap("Live TV")
        wait(lambda: all(ch in " ".join(visible()) for ch in CHANNELS), "Guide did not load both fixture channels")
        # The guide initially focuses the group chooser. Right moves into its channel grid.
        key("DPAD_RIGHT", .7)
        key("DPAD_DOWN", .7)
        held_menu()
        title = next(v for v in visible() if re.match(r"^\d+\s+Fixture Arena", v))
        channel = next(ch for ch in CHANNELS if ch in title)
        open_pane(server, channel, "guide")
        held_menu()
        assert any(re.match(r"^\d+\s+" + re.escape(channel), v) for v in visible())
        record("guideReturnFocus", channel)
        key("BACK")
        open_sports()
        wait(lambda: TEAMS[0] in " ".join(visible()), "Scheduled event not rendered")
        tap(TEAMS[0])
        wait(lambda: all(ch in visible() for ch in CHANNELS), "Game did not list two real matches")
        rows = [label(n) for n in tree().iter("node") if label(n) in CHANNELS]
        alternative = rows[-1]
        focus_text(alternative)
        held_menu()
        open_pane(server, alternative, "sports")
        wait(lambda: (n := focus()) is not None and any(alternative in label(c) for c in n.iter()),
             "Back did not restore the selected Sports channel")
        record("returnFocus", alternative)
    finally:
        server.shutdown()
        server.server_close()


def main():
    mode, = sys.argv[1:]
    assert mode in ("college-restart", "channel-shortcut")
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell","getprop","ro.kernel.qemu") == "1"
    adb("root")
    adb("wait-for-device")
    assert adb("shell","id","-u") == "0" and not adb("shell","pm","list","packages",PACKAGE)
    OUT.mkdir(parents=True, exist_ok=True)
    apk, = Path("build/guide-apks").rglob("*-x86_64.apk")
    assert "Success" in adb("install", str(apk))
    uid = adb("shell","stat","-c","%u",DATA)
    adb("shell","wm","size","1920x1080")
    adb("shell","wm","density","320")
    # Keep both the early game and the upcoming game in the guest's current calendar day.
    utc_hour = datetime.fromtimestamp(stamp()/1000, timezone.utc).hour
    offset = (12 - utc_hour + 12) % 24 - 12
    adb("shell","cmd","alarm","set-timezone","Etc/GMT"+f"{-offset:+d}" if offset else "Etc/UTC")
    key("WAKEUP")
    adb("shell","wm","dismiss-keyguard")
    try:
        (restart if mode == "college-restart" else shortcuts)(uid)
    except Exception:
        screenshot("last-screen")
        # All screens belong exclusively to a new fixture guest. Omit dynamic credentials and external content.
        words = ("All Events","TV Now","Sports Hub","Preferences","Live TV","Search","Home","FINAL",
                 "Starting","Status pending","Watch full screen","Channel options","Multiview",ACTION,
                 "WATCH ON YOUR CHANNELS","Favorite","Pane","Sound","Channel","No games",*TEAMS,*CHANNELS)
        labels = " ".join(visible())
        record("lastScreenMarkers", [w for w in words if w.lower() in labels.lower()])
        raise


if __name__ == "__main__":
    main()

