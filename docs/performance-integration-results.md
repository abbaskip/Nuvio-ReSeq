# ReSeq integration verification

The comparison uses the signed published 0.1.0-beta.10.12 and the six review branches together. The integration branch also contains the five commits already shipped in that beta so an in-place upgrade keeps its Continue Watching and navigation fixes. Every PR targets `dev`. The maintainer requested review before merge.

## Populated physical TV

An onn 4K Pro running Android TV was upgraded in place with its existing profile and remote. Android reported `speed-profile` for both sides after installation of their embedded profiles. Five cold starts used the same activity and launch sequence. D-pad traces used the same 24 actions. Measurements describe the complete integration, including the new profile.

| Measure | Published | Integration |
| --- | ---: | ---: |
| Cold Home initial display, median | 1,116 ms | 1,059 ms |
| Home fully drawn, median | 2,459 ms | 2,122 ms |
| Guide D-pad frames, P95 | 200 ms | 150 ms |
| Sports D-pad frames, P95 | 46 ms | 46 ms |
| Compiled ART files on device | 64.77 MiB | 94.39 MiB |

The odex grew from 22,207,988 to 52,251,524 bytes. The vdex grew from 43,063,148 to 43,104,492 bytes and the art file from 2,650,112 to 3,616,768. Low-storage devices should have that 29.61 MiB increase included in their release assessment.

Four visible Continue Watching series had the same order, focused card and saved shuffle choices on cold start and on return from the guide. The sequential show's coordinates and title matched. Three shows were set to shuffle. The paired timeline first saw their shuffle badges at 13.17 s on the beta and 12.89 s on integration. Both initially rendered the undecorated row. The first focused card appeared at 7.25 s and 6.38 s respectively on cold start, and at 3.83 s and 3.95 s on return. These last observations include ADB and accessibility polling time. The guide focus position and the separate All Events and TV Now views also matched in the paired traversal.

## Controlled imports during playback

The physical and emulator tests ran minified, non-debuggable app code in an isolated test package. Each used the actual repositories and a 25 fps synthetic stream. A 100,000-entry M3U had 98,000 VOD entries and 2,000 live channels. The guide supplied 36,000 XMLTV entries. Both versions produced the same ordered channel keys and numbers and reported 72,000 programme entries in the comparison workload.

| Device and workload | Published | Integration |
| --- | ---: | ---: |
| onn 4K Pro playlist, median of three runs | 24.520 s | 5.152 s |
| onn 4K Pro guide, median of three runs | 6.108 s | 6.614 s |
| 2 GiB API 34 guest playlist | 7.613 s | 1.275 s |
| 2 GiB API 34 guest guide | 1.230 s | 1.269 s |
| onn full opt-in M3U film import | — | 98,000 films in 181.677 s |
| 2 GiB guest full opt-in M3U film import | — | 98,000 films in 41.055 s |

All six 20-second active-refresh windows on the physical device delivered 500 frames and recorded zero dropped frames, audio underruns or rebuffers. The 2 GiB guest recorded 499 and 500 rendered frames during refresh and zero drops, underruns or rebuffers on both. The physical full VOD import measured a process high-water mark of 340,560 KiB. The new link index occupied 58,609,664 bytes on both devices.

The desktop JVM comparison used the released parser, the candidate and upstream at `c95144623` in rotating order. The medians of six warmed rounds were 815.5, 26.0 and 27.0 ms. All three produced the same ordered live-entry projection. These times isolate the channel parser and exclude network and SQLite.

A separate diagnostic ran three fresh guide imports on each app. On the onn, total median time was 6.626 s on the beta and 6.578 s on integration. Parsing, matching and storage accounted for 5.577 s and 5.598 s. The 2 GiB guest's total medians were 1.298 s and 1.293 s. Guide loading was effectively flat in this isolated comparison. [Guide and lookup benchmark](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44578442446).

Forty positive stream-screen matches on the onn measured native-library median lookup at 12.71 ms on the published app. On integration it was 12.82 ms before importing 98,000 plain M3U films and 14.79 ms afterwards. Plain M3U matches measured 16.27 ms median and 23.56 ms P95. The native P95 increased from 17.92 ms to 20.05 ms. On the 2 GiB guest its native median increased from 5.87 ms to 10.10 ms on integration. The combined catalogue and link databases grew by 87,732,224 bytes (83.67 MiB) for the large film import.

## Scrolling and startup in the Android TV guest

Seven cold macrobenchmark iterations used the same 2 GiB API 34 x86 guest with SwiftShader. Cold initial display for Sports measured 1,762 ms in the release and 1,824 ms in integration. Guide measured 1,769 ms and 1,619 ms. The P95 CPU frame duration was 1,191.7 ms and 1,260.1 ms for Sports and 647.1 ms and 484.0 ms for the guide. Sports showed a small slowdown on this workload and flat physical P95. The automated builder test compares 300 live fixtures, partial channel matching, categories, follow filters, replays and day changes. [Paired minified runs](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44563757263).

A second seven-iteration run on an independently provisioned 2 GiB guest measured Sports initial display at 1,896 ms versus 1,737 ms and Sports P95 CPU frame duration at 1,375 ms versus 1,174 ms. Guide initial display was 1,652 ms versus 1,677 ms and guide P95 CPU frame duration was 598 ms versus 527 ms. Device and run variability matters for the Sports comparison. [Repeat macrobenchmarks](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44578442446).

## Catalogue compatibility

A 1.5 GiB API 34 Android TV guest ran the normally signed release APKs. The published beta imported a native Xtream catalogue with 64 movies. With the provider disconnected, the upgraded release still displayed the first, fourth and last titles at the same positions. A plain M3U with one live channel appeared with movie import off. Opting in preserved that live channel and exposed a movie and a series with both episodes in order. The normal player opened the movie and the second episode with their required HTTP headers.

Reinstalling the signed beta at the same version code retained the native catalogue. On first open the older beta briefly displayed a cached M3U shelf until its ordinary refresh. Android 14 rejected installation of beta.10.11 at its lower version code. Its database-open path therefore remains unverified on this guest. The catalogue schema remains v11. The new M3U link index uses its own database.

## Profile and release packaging

The final integration commit is `6c80c0106ce07dae3cb08576ce45e6a5c541857c`. The signed workflow passed full unit regressions and lint, and verified 14,891 distinct profiled class descriptors across 14 required package groups on every post-signing APK. The publish job was skipped. [Signed workflow](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44579904228). [Five verified APKs](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44579904228/artifacts/14560901198).

Six emulator journeys asserted their final states, including a rendered video frame, guide scrolling and a matched Sports event. The generation collected 5,333,196 bytes of measured rules before preservation of startup coverage. Post-signing release verification checks the decoded profile against the final DEX files and required package groups in all four ABI builds and the universal APK. The existing in-app installer already writes matching dex metadata into its PackageInstaller session. [Profile collection](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44547277330).

## Instrumentation and regression fixtures

The published beta and integration each passed 112 of 118 supplemental Android instrumentation tests. Both failed the same three native allocator cases and the same three GuideBatchTest cases for cancellation, full-database retry and migration of a stored v3 guide. The released and candidate tests ran unchanged. The normal PR and signed-release jobs run the full unit suite separately. [Paired Android results](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44567918969).

The ten-minute soak reached a playing VOD with an advancing position and scheduled the fixture reminder on both sides. Sports reported 2,040 events, 39 matched events and three matched live events on both. At 0, 60, 120 and 300 seconds, the ordered in-app Continue Watching IDs, local raw progress and persisted projection agreed. At 60 seconds one launcher intent had resolved an episode on integration while the beta showed a detail link. The launcher rows agreed at 120 and 300 seconds. Both original host journeys then stopped while waiting for a removed cricket-detail log event. The follow-up checks the rendered teams and channel section.

## Warning log cost

The desktop JVM ran six alternating warmed bursts of 4,000 warnings through the actual released and candidate Kotlin redactor and ring file implementations. Median elapsed time was 2,832 ms versus 39 ms, and median thread CPU was 2,140.5 ms versus 23 ms. Every run retained exactly 216,000 bytes with the same SHA-256. This measures a warning burst on the host filesystem. The existing redaction, rotation and crash-tail tests also passed. [Logger PR](https://github.com/abbaskip/Nuvio-ReSeq/pull/34).

## Remaining device sign-off

The complete checklist includes third-party tracker sync, undo and rewatch, saved and blocked Sports channel matches, parental locks, recordings, Drive restore, multiview, reminders and updater behavior. The user's second physical television was playing live media during qualification. An emulator supplies memory-pressure and functional coverage. Fire TV Stick-class physical sign-off remains open. [Combined checklist](https://github.com/abbaskip/Nuvio-ReSeq/blob/perf-integration/docs/performance-integration-checklist.md).
