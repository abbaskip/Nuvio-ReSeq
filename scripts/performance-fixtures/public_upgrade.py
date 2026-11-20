"""Exercise the public beta's real update banner, downloader and idle PackageInstaller job."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

from signed_rollback import APK_HASHES, DATA, PACKAGE, SERIAL, adb, cold, label, seed_settings, tree, write_preferences

OUT = Path("build/public-upgrade")


def job_state(number):
    result = subprocess.run(["adb", "-s", SERIAL, "shell", "cmd", "jobscheduler", "get-job-state", PACKAGE, str(number)],
                            capture_output=True, text=True, timeout=15)
    return result.stdout.strip() if result.returncode == 0 else ""


def package_info():
    info = adb("shell", "dumpsys", "package", PACKAGE)
    return (re.search(r"\bversionName=(\S+)", info)[1], int(re.search(r"\bversionCode=(\d+)", info)[1]),
            re.search(r"\bfirstInstallTime=([^\r\n]+)", info)[1].strip())


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell", "getprop", "ro.kernel.qemu") == "1"
    tag = os.environ["EXPECTED_TAG"]
    release = json.loads((OUT/"latest.json").read_text())
    assert release["tag_name"] == tag and not release["draft"] and not release["prerelease"]
    universal, = [a for a in release["assets"] if a["name"].endswith("-universal.apk")]
    expected = universal["digest"].removeprefix("sha256:")
    assert re.fullmatch("[a-f0-9]{64}", expected)
    apk, = OUT.glob("*-x86_64.apk")
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == APK_HASHES["10.12"]
    adb("root")
    adb("wait-for-device")
    assert adb("shell", "getprop", "ro.build.version.sdk") == "34" and adb("shell", "id", "-u") == "0"
    assert not adb("shell", "pm", "list", "packages", PACKAGE)
    assert "Success" in adb("install", str(apk))
    before, old_code, first_install = package_info()
    assert before == "0.1.0-beta.10.12" and old_code == 10061
    uid = adb("shell", "stat", "-c", "%u", DATA)
    seed_settings(uid, {"clock_24h":True})
    marker = OUT/"upgrade-marker.txt"
    marker.write_text("beta private data survives PackageInstaller")
    saved = DATA+"/files/upgrade-marker.txt"
    adb("push",str(marker),saved)
    adb("shell", "chown", uid+":"+uid, saved)
    adb("shell", "restorecon", saved)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    cold()
    results = {"beta":before, "target":tag, "publicAsset":universal["name"]}

    def record(key, value):
        results[key] = value
        (OUT/"results.json").write_text(json.dumps(results,indent=2))
        print(key, value, flush=True)

    # Leave automatic install at the beta default. Its real network check must show the public tag.
    for attempt in range(2):
        deadline = time.monotonic() + 65
        while time.monotonic() < deadline:
            labels = [label(n) for n in tree().iter("node") if n.get("package") == PACKAGE]
            if any(tag in entry for entry in labels) and any("Update available" in entry for entry in labels):
                record("defaultBanner", True)
                break
        if results.get("defaultBanner"):
            break
        cold()
    assert results.get("defaultBanner"), "Beta did not render the public update banner"
    assert package_info()[0] == before
    adb("shell", "am", "force-stop", PACKAGE)
    write_preferences(uid, {"update_settings":{"auto_install_enabled":True, "update_check_frequency":"ALWAYS"}})
    adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
    cold()
    # Advance the registered Android idle job so this test doesn't depend on the guest's idle timer.
    deadline = time.monotonic() + 360
    launched = False
    while time.monotonic() < deadline and package_info()[0] == before:
        state = job_state(10021)
        if not launched and re.search(r"\b(waiting|pending|ready|active)\b",state):
            record("scheduledIdleJob", True)
            adb("shell", "input", "keyevent", "KEYCODE_HOME")
            if "active" not in state:
                adb("shell", "cmd", "jobscheduler", "run", "-f", PACKAGE, "10021")
            launched = True
        time.sleep(2)
    installed, code, current_first = package_info()
    record("installedVersion", installed)
    record("installedCode", code)
    assert installed == tag.removeprefix("v") and code > old_code, "The beta's own update job did not install the RC"
    installed_apk = adb("shell", "pm", "path", PACKAGE).removeprefix("package:")
    assert re.fullmatch(r"/data/app/(?:[\w.+~=-]+/)+base\.apk", installed_apk)
    assert adb("shell", "sha256sum", installed_apk).split()[0] == expected
    record("installedPublishedUniversalSha256", expected)
    assert adb("shell", "stat", "-c", "%u", DATA) == uid and current_first == first_install
    assert adb("shell", "cat", saved) == marker.read_text()
    record("preservedUidFirstInstallAndPrivateData", True)
    art = adb("shell", "pm", "art", "dump", PACKAGE)
    assert "status=speed-profile" in art and "reason=install-dm" in art
    record("compiledAtInstall", "speed-profile/install-dm")
    cold()
    assert any(label(n) in ("Home", "Search", "Library") for n in tree().iter("node") if n.get("package") == PACKAGE)
    record("openedHome", True)


if __name__ == "__main__":
    main()
