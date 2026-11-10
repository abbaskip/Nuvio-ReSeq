# ReSeq feature profiles

Use a disposable English-language API 34 Android TV emulator with an empty app data directory. The fixture activity requires the shell's DUMP permission and exists only in the full profile-generation and benchmark targets. The host server binds to loopback and serves synthetic Xtream, XMLTV, Sports and NNTP data, including the existing test-pattern MP4.

1. Start `python baselineprofile/fixtures/server.py`. Set `ANDROID_SERIAL` to the dedicated emulator. Run `adb -s "$ANDROID_SERIAL" reverse tcp:38791 tcp:38791` and repeat for port 38792.
2. Assemble `:app:assembleFullNonMinifiedRelease` and `:baselineprofile:assembleNonMinifiedRelease`. Install the emulator ABI target APK and the instrumentation APK on that serial.
3. Run the command below. Pull the reported `ReSeqProfileGenerator_collectFeatures-baseline-prof.txt` with the same explicit serial. All four journeys must pass. The generator retains executed guide, sports and shared Live TV data/model rules from the measured paths. Replace `app/src/main/baselineProfiles/reseq-features.txt` with this output. AGP merges it alongside the existing app profiles.
4. To compare profiles, install a minified `:app:assembleFullBenchmarkRelease` target and its `:baselineprofile:assembleBenchmarkRelease` runner. Use the same command with `ReSeqProfileBenchmark` in place of `ReSeqProfileGenerator`. Run with the inherited and expanded profiles on the same emulator. Both runs require a successfully installed baseline profile. Each measures seven cold launches per feature. Collect the benchmark JSON from the reported output directory.

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e reseqFixtures true \
  -e androidx.benchmark.suppressErrors EMULATOR \
  -e androidx.benchmark.targetPackageName io.github.abbaskip.nuvioreseq \
  -e class com.nuvio.tv.baselineprofile.ReSeqProfileGenerator \
  com.nuvio.tv.baselineprofile/androidx.test.runner.AndroidJUnitRunner
```

The journeys scroll the live guide, open a cached Sports Hub fixture with a matching guide channel, navigate the On Demand shelf, and render a frame served by the local NNTP article. Provider setup runs outside collection and timing. Cold-launch timing measures initial display. Frame and ART metrics cover the exercised screen, including JIT compilation count and duration. Emulator results depend on host load.
