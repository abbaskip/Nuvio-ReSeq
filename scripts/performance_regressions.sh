#!/usr/bin/env bash
set -euo pipefail
export ANDROID_SERIAL=emulator-5554
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
out=build/performance-regressions
mkdir -p "$out"
python3 scripts/stability_fixtures.py > "$out/fixture-server.txt" 2>&1 &
server=$!
trap 'kill "$server" || true' EXIT
# These Android tests exercise device services, database import and Compose navigation. Timing comes from the separate minified targets.
./gradlew :app:connectedFullDebugAndroidTest --max-workers=2 -PstabilityFixtures=true -PuseLocalFfmpegDecoder=false -Pkotlin.compiler.execution.strategy=in-process
mapfile -t apks < <(find app/build/outputs/apk/full/benchmark -name '*-x86-benchmark.apk')
test "${#apks[@]}" = 1
# The debug test package and the minified test target use the same isolated, fixture-only ID.
if adb shell pm path io.github.abbaskip.nuvioreseq.debug | grep -q '^package:'; then adb uninstall io.github.abbaskip.nuvioreseq.debug; fi
python3 scripts/stability_run.py --device emulator --serial "$ANDROID_SERIAL" --apk "${apks[0]}" \
    --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" --output "$out/journeys"
