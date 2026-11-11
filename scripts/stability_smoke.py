"""Launch a newly built minified benchmark APK on a disposable Android TV emulator.

Build first: gradlew :app:assembleFullBenchmark
Physical devices and existing installations are deliberately refused. This is a
launch check, not a fixture soak or proof of playback/Continue Watching behaviour.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import time

PACKAGE = "io.github.abbaskip.nuvioreseq.debug"
CRASH_PATTERNS = (
    r"FATAL EXCEPTION:", r"Fatal signal \d+", r"\bam_crash\s*:",
    r"\bam_anr\s*:", r"\bANR in ", r"\bANR of ",
)


def run(*command: str, timeout: int = 60, check: bool = True) -> str:
    result = subprocess.run(command, capture_output=True, encoding="utf-8",
                            errors="replace", timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command[0]} {command[1:]}\n{result.stdout}\n{result.stderr}")
    return result.stdout + result.stderr


def apk_identity(badging: str) -> str:
    match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not match or match[1] != PACKAGE:
        raise ValueError(f"Only the separate benchmark package {PACKAGE} is allowed")
    if "application-debuggable" in badging:
        raise ValueError("Expected a non-debuggable release-like benchmark APK")
    activity = re.search(r"^launchable-activity: name='([^']+)'", badging, re.MULTILINE)
    if not activity:
        raise ValueError("APK has no launchable activity")
    if not re.fullmatch(r"[A-Za-z0-9_.$]+", activity[1]):
        raise ValueError("Invalid launch activity")
    return activity[1]


def validate_emulator(serial: str, qemu: str, features: str, installed: str) -> None:
    if not re.fullmatch(r"emulator-\d+", serial) or qemu.strip() != "1":
        raise ValueError("Only an explicitly selected disposable emulator is allowed; never use this on Onn")
    if "feature:android.software.leanback" not in features.splitlines():
        raise ValueError("Android TV system image required")
    if f"package:{PACKAGE}" in installed.splitlines():
        raise ValueError("Benchmark package already installed; use a fresh disposable AVD")


def crash_lines(log: str) -> list[str]:
    return [line for line in log.splitlines() if any(re.search(p, line) for p in CRASH_PATTERNS)]


def smoke(args: argparse.Namespace) -> None:
    if args.seconds < 60:
        raise ValueError("Launch observation must last at least 60 seconds")
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError("Use a new empty output directory to preserve earlier results")
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"status": "failed", "environment": "Android TV emulator",
              "serial": args.serial, "package": PACKAGE, "requested_seconds": args.seconds}
    installed = False
    logger = None
    log_file = None
    failure = None

    def adb(*command: str, **kwargs) -> str:
        return run(args.adb, "-s", args.serial, *command, **kwargs)

    def save(name: str, value: str) -> None:
        (args.output / name).write_text(value, encoding="utf-8")

    try:
        # No mutation (including log clearing) occurs before all guards pass.
        activity = apk_identity(run(args.aapt, "dump", "badging", str(args.apk)))
        validate_emulator(args.serial, adb("shell", "getprop", "ro.kernel.qemu"),
                          adb("shell", "pm", "list", "features"),
                          adb("shell", "pm", "list", "packages", PACKAGE))
        report["fingerprint"] = adb("shell", "getprop", "ro.build.fingerprint").strip()
        report["model"] = adb("shell", "getprop", "ro.product.model").strip()
        report["heapgrowthlimit"] = adb("shell", "getprop", "dalvik.vm.heapgrowthlimit").strip()
        report["heapsize"] = adb("shell", "getprop", "dalvik.vm.heapsize").strip()
        save("device-meminfo.txt", adb("shell", "cat", "/proc/meminfo"))
        save("display.txt", adb("shell", "dumpsys", "display"))
        save("power.txt", adb("shell", "dumpsys", "power"))
        before_anr = adb("shell", "dumpsys", "activity", "lastanr")
        save("lastanr-before.txt", before_anr)
        # Own cleanup even if adb times out after the package manager installs it.
        installed = True
        installation = adb("install", str(args.apk), timeout=180)
        if "Success" not in installation:
            raise RuntimeError(f"APK installation did not succeed: {installation}")
        # Dedicated disposable emulator only; removes stale crash events.
        adb("logcat", "-b", "all", "-c")
        log_file = (args.output / "logcat.txt").open("w", encoding="utf-8")
        logger = subprocess.Popen([args.adb, "-s", args.serial, "logcat", "-v", "threadtime",
                                   "-b", "main", "-b", "system", "-b", "crash", "-b", "events"],
                                  stdout=log_file, stderr=subprocess.STDOUT)
        launch = adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{activity}")
        save("launch.txt", launch)
        if not re.search(r"^Status: ok\s*$", launch, re.MULTILINE) or "Error:" in launch:
            raise RuntimeError("Activity launch failed; see launch.txt")
        start = time.monotonic()
        sample = 0
        while True:
            elapsed = time.monotonic() - start
            if logger.poll() is not None:
                raise RuntimeError("Logcat collection stopped unexpectedly")
            if not adb("shell", "pidof", PACKAGE, check=False).strip().isdigit():
                raise RuntimeError("App process disappeared during launch observation")
            if elapsed >= sample * 30:
                save(f"meminfo-{sample:03}.txt", adb("shell", "dumpsys", "meminfo", PACKAGE))
                sample += 1
            if elapsed >= args.seconds:
                break
            time.sleep(min(1, args.seconds - elapsed))
        report["observed_seconds"] = round(time.monotonic() - start, 3)
        after_anr = adb("shell", "dumpsys", "activity", "lastanr")
        save("lastanr-after.txt", after_anr)
        if after_anr != before_anr and re.search(r"ANR (in|of) ", after_anr):
            raise RuntimeError("New activity ANR recorded; see lastanr-after.txt")
    except (Exception, KeyboardInterrupt) as error:
        failure = str(error) or type(error).__name__
    finally:
        if logger is not None:
            logger.terminate()
            try:
                logger.wait(timeout=10)
            except subprocess.TimeoutExpired:
                logger.kill()
                logger.wait(timeout=10)
        if log_file is not None:
            log_file.close()
            crashes = crash_lines((args.output / "logcat.txt").read_text(encoding="utf-8"))
            report["crash_anr_lines"] = crashes
            if crashes:
                failure = failure or "Crash/ANR in emulator logcat"
        if installed:
            try:
                adb("shell", "am", "force-stop", PACKAGE)
                cleanup = adb("uninstall", PACKAGE)
                save("cleanup.txt", cleanup)
                if "Success" not in cleanup:
                    raise RuntimeError("Uninstall did not report success")
            except Exception as error:
                failure = f"{failure or ''} Cleanup failed: {error}".strip()
        report["status"] = "failed" if failure else "passed"
        report["error"] = failure
        save("result.json", json.dumps(report, indent=2) + "\n")
    if failure:
        raise RuntimeError(failure)
    print(f"Measured on emulator: {args.seconds}s launch check passed; {args.output}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--seconds", type=int, default=60)
    parser.add_argument("--output", type=Path, required=True)
    smoke(parser.parse_args())


if __name__ == "__main__":
    main()
