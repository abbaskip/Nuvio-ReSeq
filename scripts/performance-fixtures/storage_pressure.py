"""Run two pressure levels on a disposable, rooted Actions emulator using the signed production APKs."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from signed_rollback import PACKAGE, adb, cold, seed_settings, signed_apks, tree, label, OUT as rollback_out


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true"
    assert adb("shell", "getprop", "ro.kernel.qemu") == "1" and adb("shell", "getprop", "ro.build.type") == "userdebug"
    adb("root")
    adb("wait-for-device")
    assert adb("shell", "id", "-u") == "0" and PACKAGE not in adb("shell", "pm", "list", "packages", PACKAGE)
    out = Path("build/storage-pressure")
    out.mkdir(parents=True, exist_ok=True)
    rollback_out.mkdir(parents=True, exist_ok=True)
    apks = signed_apks()
    beta = apks["10.12"][0]
    current = out / "base.apk"
    shutil.copyfile(apks["integration"][0], current)
    dm = out / "base.dm"
    with zipfile.ZipFile(current) as apk:
        dm.write_bytes(apk.read("assets/updater_dm/0.dm"))
    filler = "/data/local/tmp/reseq-storage-pressure.bin"
    assert not adb("shell", "ls", "/data/local/tmp").count("reseq-storage-pressure.bin")
    results = []

    def free():
        return int(adb("shell", "df", "-k", "/data").splitlines()[-1].split()[3]) * 1024

    def art():
        return re.findall(r"\[(?:status|reason)=[^\]]*\]", adb("shell", "pm", "art", "dump", PACKAGE))

    for target_mib in (350, 85):
        assert "Success" in adb("install", "-r", beta)
        original_uid = adb("shell", "stat", "-c", "%u", "/data/user/0/" + PACKAGE)
        seed_settings(original_uid)
        cold()
        adb("shell", "am", "force-stop", PACKAGE)
        available = free()
        size = available - target_mib * 1024 * 1024
        assert 0 < size < 8 * 1024 ** 3, available
        try:
            adb("shell", "fallocate", "-l", str(size), filler)
            before = free()
            assert abs(before - target_mib * 1024 ** 2) < 24 * 1024 ** 2, before
            p = subprocess.run(["adb", "-s", "emulator-5554", "install-multiple", "-r", str(current), str(dm)],
                               capture_output=True, text=True, timeout=150)
            response = (p.stdout + p.stderr).strip()
            installed = p.returncode == 0 and "Success" in response
            if not installed:
                assert "STORAGE" in response or "space" in response.lower(), response
            item = dict(targetMiB=target_mib, freeBefore=before, freeAfter=free(), installSucceeded=installed,
                        error=None if installed else response[-800:], art=art())
            assert adb("shell", "stat", "-c", "%u", "/data/user/0/" + PACKAGE) == original_uid
            cold()
            item["appStart"] = True
            visible = {label(n) for n in tree().iter("node") if n.get("package") == PACKAGE}
            item["visibleHome"] = bool(visible & {"Home","Search","Library","Discover","Continue Watching"})
            assert item["visibleHome"], "App failed to render its Home navigation under storage pressure"
            results.append(item)
            (out / "results.json").write_text(json.dumps(results, indent=2))
            print(item, flush=True)
        finally:
            adb("shell", "rm", "-f", filler)
            assert free() > 512 * 1024 ** 2
    assert "Success" in adb("install-multiple", "-r", str(current), str(dm))
    print("Unpressured install", art(), flush=True)


if __name__ == "__main__":
    main()
