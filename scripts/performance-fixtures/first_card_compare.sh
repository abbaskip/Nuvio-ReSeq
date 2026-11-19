#!/usr/bin/env bash
set -euo pipefail
export ANDROID_SERIAL=emulator-5554
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
out=build/sports-first-card
mkdir -p "$out"
python3 scripts/performance-fixtures/first_card_server.py >"$out/provider.log" 2>&1 &
provider=$!
trap 'kill "$provider" || true' EXIT
adb reverse tcp:38791 tcp:38791
curl --fail --silent --retry 10 --retry-delay 1 --retry-connrefused http://127.0.0.1:38791/sports |
    python3 -c 'import json,sys; assert len(json.load(sys.stdin)["entries"][0]["payload"]["events"]) == 2000'
runner=com.nuvio.tv.baselineprofile
pkg=io.github.abbaskip.nuvioreseq.perfcomparison
mapfile -t apk < <(find baselineprofile/build/outputs/apk -name '*benchmarkRelease*.apk')
test "${#apk[@]}" = 1
adb install -t "${apk[0]}"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell svc power stayon true
group=0
for side in published integration integration published; do
    group=$((group+1))
    here="$out/$group-$side"
    mkdir -p "$here"
    adb install "build/comparison/$side/target.apk"
    adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n "$pkg/androidx.profileinstaller.ProfileInstallReceiver" >"$here/profile.txt"
    grep -Eq 'result=(1|2)([^0-9]|$)' "$here/profile.txt"
    adb shell cmd package compile -f -m speed-profile "$pkg" | tee "$here/compile.txt"
    grep -q Success "$here/compile.txt"
    adb shell am instrument -w -r -e class com.nuvio.tv.baselineprofile.SportsFirstCardTest "$runner/androidx.test.runner.AndroidJUnitRunner" | tee "$here/tests.txt"
    grep -Eq '^OK \(1 test\)' "$here/tests.txt"
    test "$(grep -c '^RESEQ_FIRST_CARD' "$here/tests.txt")" = 3
    adb uninstall "$pkg"
done
