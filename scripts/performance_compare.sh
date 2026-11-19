#!/usr/bin/env bash
# Two release-equivalent builds, one disposable emulator, one set of fixture bytes.
set -euo pipefail
export ANDROID_SERIAL=emulator-5554
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
out=build/perf-comparison
mkdir -p "$out"
export RESEQ_COMPARISON_DATA="$PWD/build/comparison-inputs"
python3 scripts/performance_compare_fixtures.py "$RESEQ_COMPARISON_DATA"
python3 baselineprofile/fixtures/server.py > "$out/fixture-server.txt" 2>&1 &
server=$!
trap 'kill "$server" || true' EXIT
adb reverse tcp:38791 tcp:38791
adb reverse tcp:38792 tcp:38792
pkg=io.github.abbaskip.nuvioreseq
runner=com.nuvio.tv.baselineprofile
for side in published integration; do
    here="$out/$side"
    mkdir -p "$here"
    adb install -r "build/comparison/$side/target.apk"
    adb install -r -t "build/comparison/$side/runner.apk"
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell wm dismiss-keyguard
    adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n "$pkg/androidx.profileinstaller.ProfileInstallReceiver" | tee "$here/profile-install.txt"
    grep -Eq 'result=(1|2)([^0-9]|$)' "$here/profile-install.txt"
    adb shell cmd package compile -f -m speed-profile "$pkg"
    adb shell am start -W -S -n "$pkg/com.nuvio.tv.profiling.ReSeqJourneyActivity" --es journey compare
    ready=false
    for i in $(seq 1 90); do
        adb shell uiautomator dump /sdcard/comparison.xml >/dev/null 2>&1 || true
        adb shell cat /sdcard/comparison.xml > "$here/imports.xml" || true
        if grep -q 'Comparison complete' "$here/imports.xml"; then ready=true; break; fi
        if ! adb shell pidof "$pkg" | grep -Eq '[0-9]'; then break; fi
        sleep 2
    done
    if [[ "$ready" != true ]]; then adb logcat -d -s AndroidRuntime:E | tail -80; exit 1; fi
    python3 - "$here" <<'PY'
import json, sys, xml.etree.ElementTree as ET
from pathlib import Path
here = Path(sys.argv[1])
values = [n.get("text") for n in ET.parse(here / "imports.xml").iter("node") if "Comparison complete " in n.get("text", "")]
assert len(values) == 1
result = json.loads(values[0].removeprefix("Comparison complete "))
(here / "imports.json").write_text(json.dumps(result, indent=2))
print(result)
PY
    # Clear the synthetic import catalogue so the independently seeded guide/sports journeys use their own fixtures.
    adb shell pm clear "$pkg"
    adb shell am instrument -w -r -e reseqFixtures true -e androidx.benchmark.suppressErrors EMULATOR \
      -e androidx.benchmark.targetPackageName "$pkg" -e additionalTestOutputDir /sdcard/Download \
      -e class 'com.nuvio.tv.baselineprofile.ReSeqProfileBenchmark#liveGuide,com.nuvio.tv.baselineprofile.ReSeqProfileBenchmark#sportsEvent' \
      "$runner/androidx.test.runner.AndroidJUnitRunner" | tee "$here/instrumentation.txt"
    grep -Eq '^OK \(2 tests\)' "$here/instrumentation.txt"
    json=$(adb shell find /sdcard/Download -name '*benchmarkData.json' | tr -d '\r' | tail -1)
    [[ -n "$json" ]]
    adb pull "$json" "$here/benchmark.json"
    adb shell rm -f "$json"
    adb uninstall "$pkg"
    adb uninstall "$runner"
done
python3 - "$out" <<'PY'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
a,b = [json.loads((root / side / "imports.json").read_text()) for side in ("published","integration")]
for key in ("channels","channelKeyHash","programs"):
    assert a[key] == b[key], (key,a[key],b[key])
PY
