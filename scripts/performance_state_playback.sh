#!/usr/bin/env bash
set -euo pipefail
out=build/performance-regressions
export M1_FIXTURE_PORT=38791
mapfile -t apks < <(find app/build/outputs/apk/full/benchmark -name '*-x86-benchmark.apk')
test "${#apks[@]}" = 1
args=(--adb "$(command -v adb)" --serial "$ANDROID_SERIAL" --apk "${apks[0]}" --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt")
result=0
for scenario in main paused; do
    mode=(--remaining)
    export M1_REWATCH_PAUSE=false
    if [[ "$scenario" == paused ]]; then
        mode=(--pause-rewatch-only)
        export M1_REWATCH_PAUSE=true
    fi
    python3 -u scripts/multiview_fixtures.py > "$out/$scenario-provider.log" 2>&1 &
    server=$!
    trap 'kill "$server" 2>/dev/null || true' EXIT
    curl --silent --fail --retry 12 --retry-connrefused --retry-delay 1 http://127.0.0.1:38791/stats >/dev/null
    python3 scripts/multiview_acceptance.py "${args[@]}" "${mode[@]}" --output "$out/$scenario" || result=$?
    kill "$server"
    wait "$server" || true
    trap - EXIT
done
exit "$result"
