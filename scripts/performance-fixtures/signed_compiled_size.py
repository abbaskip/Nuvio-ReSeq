"""Measure a signed beta and its in-place update with their own embedded install profiles."""
import hashlib
import json
import os
from pathlib import Path
import shutil
from zipfile import ZipFile

from signed_rollback import DATA, PACKAGE, adb, cold, label, seed_settings, tree


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell", "getprop", "ro.kernel.qemu") == "1"
    adb("root")
    adb("wait-for-device")
    assert adb("shell", "id", "-u") == "0" and not adb("shell", "pm", "list", "packages", PACKAGE)
    assert adb("shell", "getprop", "ro.build.version.sdk") == "34"
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    out = Path("build/compiled-size")
    out.mkdir(parents=True, exist_ok=True)
    results = {}
    uid = None
    for phase, directory in (("beta", "build/reference-apks"), ("integration", "build/guide-apks")):
        source, = Path(directory).rglob("*-x86_64.apk")
        apk, dm = out / "base.apk", out / "base.dm"
        shutil.copyfile(source, apk)
        with ZipFile(apk) as archive:
            dm.write_bytes(archive.read("assets/updater_dm/0.dm"))
        assert "Success" in adb("install-multiple", "-r", str(apk), str(dm))
        art = adb("shell", "pm", "art", "dump", PACKAGE)
        assert "status=speed-profile" in art and "reason=install-dm" in art, art[:500]
        base = adb("shell", "pm", "path", PACKAGE).split("package:")[1].rsplit("/", 1)[0]
        compiled = {ext:int(adb("shell", "stat", "-c", "%s", f"{base}/oat/x86_64/base.{ext}"))
                    for ext in ("odex", "vdex", "art")}
        assert all(size > 0 for size in compiled.values())
        owner = adb("shell", "stat", "-c", "%u", DATA)
        if uid is None:
            uid = owner
            seed_settings(uid, {})
        assert owner == uid
        cold()
        assert any(label(n) in ("Home", "Library", "Search") and n.get("package") == PACKAGE for n in tree().iter("node"))
        results[phase] = {"sha256":hashlib.sha256(apk.read_bytes()).hexdigest(), "bytes":compiled,
                          "sameAppUid":True, "installDmSpeedProfile":True, "openedHome":True}
        print(phase, results[phase], flush=True)
    results["changeBytes"] = {ext:results["integration"]["bytes"][ext] - results["beta"]["bytes"][ext]
                              for ext in results["beta"]["bytes"]}
    (out/"results.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
