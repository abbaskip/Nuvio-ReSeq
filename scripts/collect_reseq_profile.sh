#!/usr/bin/env bash
# Run on a disposable emulator with the two nonMinifiedRelease APKs already built.
set -euo pipefail
: "${ANDROID_SERIAL:=emulator-5554}"
export ANDROID_SERIAL
[[ "$ANDROID_SERIAL" == emulator-* ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
out=build/reseq-profile
mkdir -p "$out"
mapfile -t target < <(find app/build/outputs/apk -iname '*-x86-nonMinifiedRelease.apk')
mapfile -t runner < <(find baselineprofile/build/outputs/apk -iname '*-nonMinifiedRelease.apk')
[[ "${#target[@]}" == 1 && "${#runner[@]}" == 1 ]]
adb install -r "${target[0]}"
adb install -r -t "${runner[0]}"
python3 baselineprofile/fixtures/server.py > "$out/fixture.log" 2>&1 &
server=$!
trap 'kill "$server" || true' EXIT
adb reverse tcp:38791 tcp:38791
adb reverse tcp:38792 tcp:38792
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am instrument -w -r -e reseqFixtures true \
  -e androidx.benchmark.suppressErrors EMULATOR \
  -e androidx.benchmark.output.enable true \
  -e additionalTestOutputDir /sdcard/Download \
  -e androidx.benchmark.targetPackageName io.github.abbaskip.nuvioreseq \
  -e class 'com.nuvio.tv.baselineprofile.ReSeqProfileGenerator#collectRelease' \
  com.nuvio.tv.baselineprofile/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrumentation.txt"
grep -Eq '^OK \(1 test\)' "$out/instrumentation.txt"
profile=$(adb shell find /sdcard/Download -name '*collectRelease-baseline-prof.txt' | tr -d '\r')
[[ -n "$profile" ]]
adb pull "$profile" "$out/collected.txt"
for path in livetv/ui livetv/sports livetv/ondemand ui/screens/settings; do
  grep -q "Lcom/nuvio/tv/$path/" "$out/collected.txt"
done
# Retain the startup and library rules on which existing sideloaded users rely.
LC_ALL=C sort -u app/src/main/generated/baselineProfiles/startup-prof.txt "$out/collected.txt" > "$out/baseline-prof.txt"
