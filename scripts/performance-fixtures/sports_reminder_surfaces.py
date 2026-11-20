"""Compare a saved Sports reminder on the signed beta and integration, including Android's surfaces."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

from guest_failure import capture
from signed_rollback import APK_HASHES, DATA, PACKAGE, SERIAL, adb, cold, key, seed_settings
from sports_actions import TEAMS, open_sports, publish, stamp, tap, visible, wait


OUT = Path("build/sports-reminder-surfaces")
CALENDAR = "content://com.android.calendar"


def calendar_events():
    result = subprocess.run(
        ["adb", "-s", SERIAL, "shell", "content", "query", "--uri", CALENDAR + "/events", "--projection", "_id"],
        capture_output=True, text=True, timeout=10,
    )
    output = result.stdout + result.stderr
    if "Could not find provider" in output or "Unable to find provider" in output:
        return {"provider": "absent"}
    if "No result found" in output:
        return {"provider": "available", "eventIds": []}
    ids = sorted(int(v) for v in re.findall(r"Row: \d+\s+_id=(\d+)", output))
    assert result.returncode == 0 and ids, output[:500]
    return {"provider": "available", "eventIds": ids}


def android_surfaces():
    keys = adb("shell", "cmd", "notification", "list").splitlines()
    app_keys = sorted(k.strip() for k in keys if f"|{PACKAGE}|" in k)
    return {"appNotificationKeys": app_keys, "androidCalendar": calendar_events()}


def calendar_control():
    before = calendar_events()
    if before["provider"] == "absent":
        return
    sync_uri = CALENDAR + "/calendars?caller_is_syncadapter=true&account_name=ReSeqFixture&account_type=LOCAL"
    adb("shell", "content", "insert", "--uri", "'" + sync_uri + "'",
        "--bind", "account_type:s:LOCAL", "--bind", "account_name:s:ReSeqFixture",
        "--bind", "name:s:ReSeqFixture", "--bind", "calendar_displayName:s:ReSeqFixture",
        "--bind", "calendar_access_level:i:700", "--bind", "ownerAccount:s:ReSeqFixture",
        "--bind", "calendar_color:i:65535", "--bind", "visible:i:1", "--bind", "sync_events:i:1")
    calendars = adb("shell", "content", "query", "--uri", CALENDAR + "/calendars", "--projection", "_id:name")
    match = re.search(r"_id=(\d+), name=ReSeqFixture\b", calendars)
    assert match, calendars
    now = stamp()
    adb("shell", "content", "insert", "--uri", CALENDAR + "/events", "--bind", "calendar_id:i:" + match[1],
        "--bind", "title:s:ReSeqCalendarControl", "--bind", "dtstart:l:" + str(now),
        "--bind", "dtend:l:" + str(now + 600_000), "--bind", "eventTimezone:s:UTC")
    assert len(set(calendar_events()["eventIds"]) - set(before["eventIds"])) == 1


def session(phase, apk):
    evidence = {"apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest()}

    def save(name, value):
        evidence[name] = value
        (OUT / (phase + ".json")).write_text(json.dumps(evidence, indent=2))
        print(phase, name, value, flush=True)

    if phase == "beta":
        assert evidence["apkSha256"] == APK_HASHES["10.12"]
    assert "Success" in adb("install", "-r", str(apk))
    assert "Success" in adb("shell", "pm", "clear", PACKAGE)
    uid = adb("shell", "stat", "-c", "%u", DATA)
    info = adb("shell", "dumpsys", "package", PACKAGE)
    requests_post = "android.permission.POST_NOTIFICATIONS" in info
    if requests_post:
        adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    save("declaresNotificationPermission", requests_post)
    seed_settings(uid, {
        "sports_mine": json.dumps({"setUp": True}),
        "sports": json.dumps({"region": "US", "remindMinutes": 15, "remindMyTeams": False}),
    })
    start = stamp() + 36 * 60_000
    publish(uid, start, False)
    open_sports()
    wait(lambda: TEAMS[0] in " ".join(visible()), "The scheduled game is missing from All Events")
    tap(TEAMS[0])
    wait(lambda: any("Remind me" in v for v in visible()), "The game did not offer Remind me")
    save("beforeSaving", android_surfaces())
    tap("Remind me")
    wait(lambda: any("Reminder set" in v for v in visible()), "The game did not save its reminder")
    save("afterSaving", android_surfaces())

    # Move only the disposable guest's clock into the saved game's reminder window.
    # The event and the app's saved reminder retain exactly the same kickoff and identity.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "cmd", "alarm", "set-time", str(start - 5 * 60_000))
    assert abs(stamp() - (start - 5 * 60_000)) < 3_000, "The guest clock did not advance"
    publish(uid, start, False)
    cold()
    wait(lambda: "Open in Sports" in visible(), "The saved Sports reminder did not appear on Home", 55)
    assert all(team in " ".join(visible()) for team in TEAMS)
    save("inAppReminderAfterRestart", True)
    save("whileDueOnHome", android_surfaces())
    tap("Open in Sports")
    wait(lambda: "WATCH ON YOUR CHANNELS" in " ".join(visible()).upper(), "The action did not open the game")
    assert all(team in " ".join(visible()) for team in TEAMS)
    save("openedCorrectGame", True)
    save("afterOpening", android_surfaces())
    return evidence


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell", "getprop", "ro.kernel.qemu") == "1"
    adb("root")
    adb("wait-for-device")
    assert adb("shell", "id", "-u") == "0" and not adb("shell", "pm", "list", "packages", PACKAGE)
    OUT.mkdir(parents=True, exist_ok=True)
    adb("shell", "wm", "size", "1920x1080")
    adb("shell", "wm", "density", "320")
    adb("shell", "settings", "put", "global", "http_proxy", "127.0.0.1:9")
    adb("shell", "settings", "put", "global", "auto_time", "0")
    utc_hour = time.gmtime(stamp() / 1000).tm_hour
    offset = (12 - utc_hour + 12) % 24 - 12
    adb("shell", "cmd", "alarm", "set-timezone", f"Etc/GMT{-offset:+d}" if offset else "Etc/UTC")
    key("WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")

    # Positive control for the same system notification query used at every step.
    tag = "reseq-qa-reminder-control"
    adb("shell", "su", "2000", "cmd", "notification", "post", tag, "Control")
    wait(lambda: tag in adb("shell", "cmd", "notification", "list"), "Android notification control missing", 10)
    calendar_control()
    previous = None
    for phase, folder in (("beta", "build/reference-apks"), ("integration", "build/guide-apks")):
        apk, = Path(folder).rglob("*-x86_64.apk")
        try:
            result = session(phase, apk)
        except Exception:
            capture(OUT / phase)
            raise
        if previous:
            for name in ("beforeSaving", "afterSaving", "whileDueOnHome", "afterOpening"):
                assert previous[name] == result[name], (name, previous[name], result[name])
        previous = result


if __name__ == "__main__":
    main()
