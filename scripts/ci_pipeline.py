"""Run reproducible CI lanes and record timings without recording service inputs."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def verify_test_results(directory: Path) -> int:
    suites = [ET.parse(p).getroot() for p in directory.glob("TEST-*.xml")]
    executed = sum(int(s.get("tests", 0)) - int(s.get("skipped", 0)) for s in suites)
    if not executed or any(int(s.get(k, 0)) for s in suites for k in ("failures", "errors")):
        raise ValueError("Missing or failing JVM regression results")
    return executed


def tasks_for(lane: str, device_apks: bool = False, apk: bool = False) -> list[str]:
    if lane == "release":
        return [":app:assembleFullRelease"]
    tasks = [":app:testFullDebugUnitTest"]
    if lane == "validate":
        tasks += [":app:lintFullDebug"]
    if device_apks or apk:
        tasks += [":app:assembleFullDebug"]
    if device_apks:
        tasks += [":app:assembleFullDebugAndroidTest"]
    return tasks


def run(lane: str, device_apks: bool = False, apk: bool = False) -> None:
    output = ROOT / "build" / "pipeline"
    output.mkdir(parents=True, exist_ok=True)
    measurements = []

    def execute(name, command, env=None):
        start = time.monotonic()
        result = subprocess.run(command, cwd=ROOT, env=env)
        measurements.append({"phase": name, "seconds": round(time.monotonic() - start, 3),
                             "exit_code": result.returncode})
        (output / f"{lane}.json").write_text(json.dumps(measurements, indent=2) + "\n")
        if result.returncode:
            raise SystemExit(result.returncode)

    if lane != "release":
        env = dict(os.environ, PYTHONPATH=str(ROOT / "scripts"))
        execute("pipeline unit tests", [sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests"], env)
        execute("sports feed fixtures", [sys.executable, "-m", "unittest", "discover", "-s", "tools/sports-feed", "-p", "test_*.py"])

    gradle = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    execute("gradle", [gradle, *tasks_for(lane, device_apks, apk),
                       "--init-script", "scripts/ci.gradle", "-PuseLocalFfmpegDecoder=false",
                       "-Pkotlin.compiler.execution.strategy=in-process", "--configuration-cache",
                       "--build-cache", "--parallel", "--max-workers=" + os.environ.get("NUVIO_CI_WORKERS", "2"),
                       "-Dorg.gradle.jvmargs=" + os.environ.get("NUVIO_CI_JVM_ARGS", "-Xmx4096m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"),
                       "--profile", "--console=plain", "--stacktrace"])

    if lane != "release":
        executed = verify_test_results(ROOT / "app/build/test-results/testFullDebugUnitTest")
        print(f"Verified {executed} executed JVM tests; no historical count threshold.")

    if lane == "validate":
        raw = gzip.decompress((ROOT / "automation/lint-reference-beta7.1.xml.gz").read_bytes())
        baseline = json.loads((ROOT / "automation/lint-baseline.json").read_text())
        if hashlib.sha256(raw).hexdigest() != baseline["origin"]["report_sha256"]:
            raise SystemExit("Released lint reference integrity check failed")
        (output / "released-lint.xml").write_bytes(raw)
        execute("lint regression gate", [sys.executable, "scripts/lint_guard.py", "check", "--project", ".",
                                        "--report", "app/build/reports/lint-results-fullDebug.xml",
                                        "--baseline", "automation/lint-baseline.json",
                                        "--released-report", "build/pipeline/released-lint.xml",
                                        "--require-ratchet", "--summary", "build/pipeline/lint-verdict.json"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lane", choices=("development", "validate", "release"))
    parser.add_argument("--device-apks", action="store_true")
    parser.add_argument("--apk", action="store_true")
    args = parser.parse_args()
    run(**vars(args))
