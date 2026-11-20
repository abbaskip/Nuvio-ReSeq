#!/usr/bin/env bash
set -euo pipefail
src=scripts/performance-fixtures/observer
out=build/picker-observer
mkdir -p "$out/classes" "$out/dex"
tools="$ANDROID_HOME/build-tools/36.0.0"
platform="$ANDROID_HOME/platforms/android-36/android.jar"
javac --release 8 -encoding UTF-8 -classpath "$platform" -d "$out/classes" "$src/PickerObserver.java"
"$tools/d8" --lib "$platform" --min-api 28 --output "$out/dex" "$out/classes/com/nuvio/perf/observer/PickerObserver.class"
"$tools/aapt" package -f -M "$src/AndroidManifest.xml" -I "$platform" -F "$out/unsigned.apk"
(cd "$out/dex" && zip -q ../unsigned.apk classes.dex)
"$tools/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
keytool -genkeypair -alias observer -keystore "$out/guest.jks" -keyalg RSA -validity 3 -storepass guestonly -keypass guestonly -dname CN=GuestObserver >/dev/null
"$tools/apksigner" sign --ks "$out/guest.jks" --ks-pass pass:guestonly --out "$out/observer.apk" "$out/aligned.apk"
