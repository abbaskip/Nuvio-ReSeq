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

The final signed commit repeated the initial-display improvement in a second set of five starts, from 1,128 to 1,097 ms. Fully drawn Home improved from 2,438 to 2,140 ms. On this exact build the odex grew from 22,207,988 to 52,251,572 bytes, the vdex from 43,063,148 to 43,105,548 bytes and the art file from 2,650,112 to 3,616,768 bytes. The installed increase was 29.61 MiB.

On a second traversal, Settings P95 was 81 ms on the published build and 97 ms on the final integration APK. Sports was 46 ms and 53 ms. Reversing build order and repeating the same 24 Settings actions four times produced P95 values of 150, 93, 77 and 73 ms on the beta and 125, 89, 65 and 69 ms on integration.

The original multi-screen trace measured 109 ms in the beta guide. The UI test runner exited on the candidate after TV Now while the release app remained alive. An isolated cold guide entry measured 250 ms on the candidate. Those two samples had different preceding journeys. A new matched run entered the guide directly from cold Home on each installation and repeated the same 24 D-pad actions three times. The corresponding P95 values were 300, 200 and 200 ms on the beta and 81, 150 and 200 ms on integration.

The same matched run entered Sports from cold Home, selected All Events, moved focus into the list and repeated the 24 D-pad actions three times. The beta's P95 values were 46, 44 and 57 ms and integration's were 46, 57 and 53 ms. The comparison supports faster Home and playlist loading. Sports frame timings varied across passes and devices.

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

<details>
<summary>Individual physical TV measurements</summary>

| Measure, in milliseconds | Published trials | Integration trials |
| --- | --- | --- |
| Cold initial display | 1,185 / 1,167 / 1,113 / 1,128 / 1,111 | 1,065 / 1,099 / 1,097 / 1,101 / 1,077 |
| Home fully drawn | 2,481 / 2,643 / 2,302 / 2,425 / 2,438 | 2,140 / 2,224 / 2,151 / 2,049 / 2,001 |
| 100,000-entry playlist | 21,172 / 24,566 / 24,520 | 7,126 / 4,887 / 5,152 |
| Guide immediately after playlist | 6,108 / 6,851 / 6,080 | 6,704 / 6,614 / 5,885 |
| Fresh guide source | 6,477 / 6,626 / 8,093 | 6,242 / 7,200 / 6,578 |

</details>

Forty positive stream-screen matches on the onn measured native-library median lookup at 12.71 ms on the published app. On integration it was 12.82 ms before importing 98,000 plain M3U films and 14.79 ms afterwards. Plain M3U matches measured 16.27 ms median and 23.56 ms P95. The native P95 increased from 17.92 ms to 20.05 ms. On the 2 GiB guest its native median increased from 5.87 ms to 10.10 ms on integration. The combined catalogue and link databases grew by 87,732,224 bytes (83.67 MiB) for the large film import.

The physical box also streamed a 1080p50 H.264 MPEG-TS feed at 6 Mbps. Across the 20-second active-refresh interval, the published beta rendered 1,000 frames and integration rendered 1,001. Both reported zero dropped frames, audio underruns and rebuffers. The idle controls rendered 999 and 1,000 frames respectively. This test used a byte-range-capable HTTP server. A preliminary server that could not seek in the large file triggered the released player's startup watchdog and was excluded before measurement.

## Scrolling and startup in the Android TV guest

Seven cold macrobenchmark iterations used the same 2 GiB API 34 x86 guest with SwiftShader. Cold initial display for Sports measured 1,762 ms in the release and 1,824 ms in integration. Guide measured 1,769 ms and 1,619 ms. The P95 CPU frame duration was 1,191.7 ms and 1,260.1 ms for Sports and 647.1 ms and 484.0 ms for the guide. Sports showed a small slowdown on this workload and flat physical P95. The automated builder test compares 300 live fixtures, partial channel matching, categories, follow filters, replays and day changes. [Paired minified runs](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44563757263).

A second seven-iteration run on an independently provisioned 2 GiB guest measured Sports initial display at 1,896 ms versus 1,737 ms and Sports P95 CPU frame duration at 1,375 ms versus 1,174 ms. Guide initial display was 1,652 ms versus 1,677 ms and guide P95 CPU frame duration was 598 ms versus 527 ms. Device and run variability matters for the Sports comparison. [Repeat macrobenchmarks](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44578442446).

## Catalogue compatibility

A 1.5 GiB API 34 Android TV guest ran the normally signed release APKs. The published beta imported a native Xtream catalogue with 64 movies. With the provider disconnected, the upgraded release still displayed the first, fourth and last titles at the same positions. A plain M3U with one live channel appeared with movie import off. Opting in preserved that live channel and exposed a movie and a series with both episodes in order. The normal player opened the movie and the second episode with their required HTTP headers.

Reinstalling the signed beta at the same version code retained the native catalogue. On first open the older beta briefly displayed a cached M3U shelf until its ordinary refresh. Android 14 rejected installation of beta.10.11 at its lower version code. Its database-open path therefore remains unverified on this guest. The catalogue schema remains v11. The new M3U link index uses its own database.

On the final signed APK a cold Continue Watching selection reopened the opted-in M3U movie with Reuse Last Link. The saved playhead was 80,862 ms. The first observed PLAYING position was 81,318 ms, 5,406 ms after selection. The provider had rotated both its media URL and its required header. Both requests used the current values, and scanning the release app's process log found zero references to the synthetic media URL, token or header. A pre-fix control made an expired request and received HTTP 403 before fallback recovered.

## Profile and release packaging

The final integration commit is `6c80c0106ce07dae3cb08576ce45e6a5c541857c`. The signed workflow passed full unit regressions and lint, and verified 14,891 distinct profiled class descriptors across 14 required package groups on every post-signing APK. The publish job was skipped. [Signed workflow](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44579904228). [Five verified APKs](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44579904228/artifacts/14560901198).

After the final comparison the onn returned to Home on the signed integration APK. Its installed `base.apk` SHA-256 matched the delivered ARMv7 artifact, `3f5e375582692e4d38af97725a341f36214f766e06285d6ef1bd528fa2cfae74`. The universal APK SHA-256 is `9dd120fe4615b57e10d88807ef5991a4db5cb86b21265ac7dee3420769564e99`.

Six emulator journeys asserted their final states, including a rendered video frame, guide scrolling and a matched Sports event. The generation collected 5,333,196 bytes of measured rules before preservation of startup coverage. Post-signing release verification checks the decoded profile against the final DEX files and required package groups in all four ABI builds and the universal APK. The existing in-app installer already writes matching dex metadata into its PackageInstaller session. [Profile collection](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44547277330).

## Instrumentation and regression fixtures

The published beta and integration each recorded **99 passes, 13 skips and 6 failures across 118 supplemental Android instrumentation cases**. The JUnit XML identifies the same skipped and failed cases on both. The skips include six public catalogue, extension, DNS and settings scenarios, three live MDBList account/library/scrobble scenarios and four optional profiling scenarios. Both failed the same three native allocator cases and the same three GuideBatchTest cases for cancellation, full-database retry and migration of a stored v3 guide. The tests ran unchanged. The normal PR and signed-release jobs run the full unit suite separately. [Paired Android results](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44567918969).

The passing instrumentation includes resuming bulk work with the latest Continue Watching projection after Live TV closes and restoring the serialized Google backup payload through the IPTV import and migration scenario. Those cases exercise on-device logic. Live Google Drive and MDBList account checks remain open.

The ten-minute soak reached a playing VOD with an advancing position and scheduled the fixture reminder on both sides. Sports reported 2,040 events, 39 matched events and three matched live events on both. At 0, 60, 120 and 300 seconds, the ordered in-app Continue Watching IDs, local raw progress and persisted projection agreed. At 60 seconds one launcher intent had resolved an episode on integration while the beta showed a detail link. The launcher rows agreed at 120 and 300 seconds. Both original host journeys then stopped while waiting for a removed cricket-detail log event. The follow-up checks the rendered teams and channel section.

The follow-up completed on both builds. Each of five entries into the live cricket event rendered both teams and the channel section. The ordered match fingerprints agreed at all five samples. The final fixture held 2,039 events, including 2,001 provider events, with 37,890 channel associations across 38 matched events. The comparison includes unmatched event IDs, ordered channel keys, confidence scores, match reasons, quality and catch-up. The full projection SHA-256 on each build was `f8d57961d8a2494efe3e7add08a2c15f7b0a130c54b80712e3b941f55ebeee54`.

The follow-up retained all three supported Continue Watching shows in the same order on both builds with missing, invalid, timed-out, unavailable and available TVDB responses. After fixture episode completion while Home was closed, both launcher rows briefly retained episode one and the persisted snapshot had yet to resolve episode two. Both resolved episode two after backgrounding and retained it on return to Home. The benchmark comparison uses signed and minified builds for timings. These journey assertions compare function using the Android test fixture. [Paired Sports, TVDB and launcher results](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44582288333).

## Warning log cost

The desktop JVM ran six alternating warmed bursts of 4,000 warnings through the actual released and candidate Kotlin redactor and ring file implementations. Median elapsed time was 2,832 ms versus 39 ms, and median thread CPU was 2,140.5 ms versus 23 ms. Every run retained exactly 216,000 bytes with the same SHA-256. This measures a warning burst on the host filesystem. The existing redaction, rotation and crash-tail tests also passed. [Logger PR](https://github.com/abbaskip/Nuvio-ReSeq/pull/34).

## Remaining device sign-off

| Area | Evidence collected | Outstanding on-device comparisons |
| --- | --- | --- |
| Installation and storage | Signed in-place upgrade on the populated onn, fresh and populated Android TV guests, ART footprint, five verified APKs | Repeat on a physical Fire TV Stick-class device, including profile compilation with low free storage. |
| Continue Watching | Real initial and return focus, order, saved shuffle and sequential selection, fixture local progress, TVDB responses and resumed M3U playhead | Full undo, rewatch and caught-up paths on both Home layouts and profiles, real Trakt, Simkl and MDBList account sync. |
| Sports | Five ordered match comparisons, rendered game details, full 300-live-event builder fixture, separate All Events and TV Now, fixture reminder scheduled | User-saved and blocked matches with live Gemini refinement, personal teams and standings, delivery from a cold notification and Calendar, focus through all filters on the second device. |
| Live TV and playback | Ordered channel import, guide counts, guide return focus, six 25 fps refresh windows and a 1080p50 refresh window | Real recordings and reminders, Drive backup and recovery, parental unlock and relock, backup switching from every entry point, Multiview and catch-up. |
| M3U VOD | Three import paths covered in tests, signed playback with required headers, cold link reuse after URL rotation, large catalogue import and lookup, equal-version rollback | Legacy third-party account fallback and two-profile ordering on physical hardware, and lower-version database opening on a device that permits a signed downgrade. |
| Shared features | Full existing unit suite, release lint, six asserted profile journeys and byte-identical retained logger output | End-to-end TV Mix playback, ratings and Advanced Discover, full addon/player controls, problem-report capture after a device kill, and an actual in-app update on low-end hardware. |

The second connected physical TV was playing live media during qualification and the 1.5 and 2 GiB Android TV guests supplied memory-constrained coverage. Physical Fire TV Stick-class sign-off remains open. The combined checklist provides the steps for each remaining comparison. [Combined checklist](https://github.com/abbaskip/Nuvio-ReSeq/blob/perf-integration/docs/performance-integration-checklist.md).
