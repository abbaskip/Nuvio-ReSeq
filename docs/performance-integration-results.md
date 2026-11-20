# ReSeq integration verification

The comparison uses the signed published 0.1.0-beta.10.12 and the six review branches together. The integration branch also contains the five commits already shipped in that beta so an in-place upgrade keeps its Continue Watching and navigation fixes. Every PR targets `dev`. The maintainer requested review before merge.

## RC1.0 source coverage, 20 November

The `1.0.0-rc.1` release commit is based on the same app tested on the onn, `4081e7ade`. The three changed files set Android build 10062 and the version name, test the beta-to-RC version comparison, and supply the release notes. [Release diff](https://github.com/abbaskip/Nuvio-ReSeq/compare/4081e7ade...b3cb65db9).

GitHub published RC1.0 as ReSeq’s Latest release. The signed workflow verified 4,099 passing JVM tests, passed the lint gate and checked 14,139 profiled class descriptors across 14 packages in all five APKs. The public universal APK has SHA-256 `a8af91e10c9141d66f80054958b1a18eeda2c14d2cd68b1e3157929e8818c99b`, the production package and the beta’s signing certificate. All five public asset hashes matched the signed release manifest. [Signed workflow](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44664381338) · [Published APKs and checksums](https://github.com/abbaskip/Nuvio-ReSeq/releases/tag/v1.0.0-rc.1).

Two clean 2 GiB Android 14 TV guests ran the published beta 10.12. Both displayed the RC banner with the beta’s default settings. After the test enabled automatic installs and granted the install permission, the beta downloaded the public universal APK. The test advanced its registered Android idle job. The beta’s own installer upgraded build 10061 to 10062, preserving the app UID, original install time and a file in private storage. In both runs the installed APK matched the published checksum. [First in-app update](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44668152114) · [Repeated update and Home launch](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44668790072).

The first test sampled ART as soon as the new version became visible and failed its compilation assertion. The repeat recorded the installed `base.dm` matching the APK’s Android 14 metadata. ART initially reported `run-from-apk`. About 8.2 seconds later it reported `speed-profile` with reason `install-dm`, and ReSeq opened Home. The test never requested a separate compilation. The physical onn still showed one ReSeq TV launcher and the tested beta build at the time of the read-only check. [Android install observations](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44668790072/artifacts/14598947888).

We checked all 18 open pull requests against the release commit. Each of the 12 current PR heads (#34–45) is a Git ancestor, including the overnight college results and the guide and Sports channel shortcuts for Multiview. For the four older app PRs, `git cherry` found no outstanding patch. Those cover Continue Watching and Live TV, IPTV source ranking, international cricket colours, and World Cup and followed fixtures. [Continue Watching and Live TV](https://github.com/abbaskip/Nuvio-ReSeq/pull/20) · [IPTV source ranking](https://github.com/abbaskip/Nuvio-ReSeq/pull/23) · [Cricket colours](https://github.com/abbaskip/Nuvio-ReSeq/pull/24) · [Fixture coverage](https://github.com/abbaskip/Nuvio-ReSeq/pull/25).

Two historical drafts contain extra beta 9.6 discovery tooling, measurements and design documents. Those materials remain on their review branches. [Phase 1 discovery](https://github.com/abbaskip/Nuvio-ReSeq/pull/14) · [Phase 2 design](https://github.com/abbaskip/Nuvio-ReSeq/pull/15).

## Follow-up qualification, 20 November

The first signed integration was `6c80c01`. Candidate `4081e7ade` includes the overnight college result fix, the Multiview channel shortcut in all 38 app locales and the smaller baseline profile. The signed workflow verified 4,098 passing JVM tests, passed the lint gate and checked 14,139 compiled class descriptors across 14 required packages in all five final APKs. It skipped publication. All twelve review PRs (#34–45) remain open against dev with green checks. [Earlier signed workflow](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44645609882). [Current signed workflow](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44659629603). [Verified APKs](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44659629603/artifacts/14596171018).

The maintainer's populated onn received `4081e7ade` in place through a signed ADB install session with the APK’s own dex metadata. The ARMv7 APK SHA-256 on the device was `ff725f91e999954f6f1a8fa91b5ac108f9860428abb16687ad0696ef9285a7d3`, matching the workflow artifact. Android retained the app UID and original install date, reported `speed-profile` / `install-dm`, and resolved exactly one ReSeq TV launcher. No data was cleared. Physical Fire TV and authenticated account sign-off remain open.

| Follow-up | Qualification status |
| --- | --- |
| A. Sports delivery | Guest pairs and one-factor control complete. Repeated signed observations on the onn remain open. |
| B. Storage | All 14 groups measured. Removed the Settings and Detail wildcards after eight matched pairs per screen, saving 7.66 MiB of guest odex. Physical Fire TV storage remains open. |
| C. Pre-existing bugs | All three fixes verified on Android, including all six previously failing instrumentation cases. PRs are open for review. |
| D. Sign-off | Partial. Account-dependent and physical-device checks are listed below. |
| E. Upstream comparison | Upstream binary identified. Three-build physical journeys await access to the onn. |
| F. Confirmation | Launcher timing explained. Downgrade playback failure reproduced on a cached M3U card. Full picker timed on two 2 GiB guests. Physical low-end picker timing remains open. |

### A. Sports delivery

Eight adjacent beta/integration pairs ran on one 2 GiB Android TV guest. Four began with the beta and four began with integration. Both apps used the same minified build type, profiles, fixtures and observation code. Times below are milliseconds. Parentheses give the 25th and 75th percentiles. Paired differences are calculated within each pair. [All 16 raw runs and observations](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44640254942).

| Cold observation | Beta median (IQR) | 6c80c01 median (IQR) | Median paired change |
| --- | ---: | ---: | ---: |
| First nonempty event publication | 1,894 (1,840–1,963) | 1,650 (1,619–1,710) | −253 |
| First actual channel association | 2,966 (2,752–3,128) | 2,631 (2,217–2,804) | −527 |
| First rendered game card | 3,108 (2,948–3,173) | 2,874 (2,659–2,953) | −336 |
| Saved reminder dialog visible | 16,652 (16,595–16,702) | 17,732 (17,668–17,808) | +1,074 |
| Correct game open from reminder | 17,875 (17,826–18,048) | 18,992 (18,964–19,017) | +1,131 |

The first 15-second reminder poll started after Home settled. Replacing just the Sports host with the beta version removed the repeated one-second delay. Its median paired differences were −80 ms for events, −284 ms for a channel, −265 ms for a card, −34 ms for the dialog and +105 ms for opening the game. PR #36 now starts that first interval at host entry and retains the Home readiness gate. [One-factor control and raw pairs](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44648260653).

Eight new counterbalanced pairs on a second 2 GiB guest run measured the combined correction. Values are milliseconds, rounded to the nearest millisecond. Physical paired measurements remain open. The event and reminder observations improved. The channel and card measurements varied in both directions. [Corrected integration, all 16 observations](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44645608361).

| Corrected integration | Beta median (IQR) | Integration median (IQR) | Paired change (IQR) |
| --- | ---: | ---: | ---: |
| First nonempty event publication | 2,484 (2,410–2,565) | 2,320 (2,212–2,388) | −163 (−223 to +19) |
| First actual channel association | 3,966 (3,875–4,063) | 3,989 (3,725–4,112) | +42 (−149 to +222) |
| First rendered game card | 3,891 (3,795–3,976) | 3,990 (3,777–4,143) | +208 (−60 to +277) |
| Saved reminder dialog visible | 17,060 (17,011–17,107) | 16,994 (16,954–17,093) | −19 (−170 to +41) |
| Correct game open from reminder | 18,617 (18,515–18,707) | 18,465 (18,389–18,557) | −230 (−278 to +92) |

Eight further counterbalanced pairs included college score retention and the channel shortcut. The paired differences below compare integration minus beta in milliseconds. One cold integration run took 1.98 s longer to publish events. No guest series established a consistent first-card improvement. [All 16 observations](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44653716202/artifacts/14593274572).

| Updated integration | Beta median | Integration median | Paired change (IQR) |
| --- | ---: | ---: | ---: |
| First nonempty events | 2,265 | 2,233 | −19 (−71 to +46) |
| First channel association | 3,758 | 3,335 | −258 (−751 to −176) |
| First rendered game card | 3,580 | 3,649 | +70 (−25 to +335) |
| Saved reminder visible | 16,907 | 16,754 | −183 (−237 to −82) |
| Game open from reminder | 18,293 | 18,314 | −58 (−103 to +124) |

### B. Storage

Both switches that enable M3U import now show the measured estimate of approximately 85 MiB per 100,000 playlist-listed films. The previous 98,000-film run added 83.7 MiB. [M3U review](https://github.com/abbaskip/Nuvio-ReSeq/pull/39).

For each row, the release optimizer removed just the named wildcard group. All five DEX checksums matched the signed integration. The all-groups control reproduced its embedded binary profile byte for byte. Every variant installed with a dex metadata profile on the same 2 GiB x86_64 Android 14 guest and compiled with ART `speed-profile`. The comparison used `base.odex` bytes. [Optimizer inputs and checksums](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44644271540). [Compiled size for every variant](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44653669223/artifacts/14592512176).

| Wildcard group removed | Marginal odex saving (MiB) | Wildcard group removed | Marginal odex saving (MiB) |
| --- | ---: | --- | ---: |
| Live TV and Sports | 8.744 | Settings | 5.515 |
| Media3 ExoPlayer | 4.785 | Detail | 2.049 |
| Home | 1.879 | UI components | 1.327 |
| Media3 common | 1.181 | Search and Advanced Discover | 0.761 |
| TVDB | 0.746 | Media3 datasource | 0.405 |
| Navigation | 0.392 | Media3 MP4 extractor | 0.282 |
| Media3 TS extractor | 0.271 | TV Mix | 0.090 |

With all rules, odex was 66,989,872 bytes. With only the generated journey profile it was 30,906,184 bytes, a difference of 34.412 MiB on this guest. Removing Settings and Detail together saved 7.663 MiB. These marginal costs overlap. The separate physical onn measurement reported 29.6 MiB of additional compiled files for the complete integration relative to beta.10.12.

PR #35 now keeps generated coverage for Settings and Detail and checks it in the release APK. The trimmed profile measured 58,954,784 odex bytes on the same matched DEX, 26.749 MiB above the generated-only control. For each screen, eight alternating pairs used the same signed APK on a 2 GiB Android 14 guest. Each install's odex size confirmed which profile had compiled. The 24-action D-pad traces used 12 presses down and 12 up. The Detail frame distribution was wider without its wildcard. [Settings samples](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44655759507/artifacts/14594391377). [Detail samples](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44655759507/artifacts/14593686699).

| Screen and measure | With wildcard, median (IQR), ms | With generated coverage, median (IQR), ms | Paired change (IQR), ms |
| --- | ---: | ---: | ---: |
| Settings cold entry | 397 (387–408) | 388 (379–401) | −10 (−31 to +7) |
| Settings frame P95 | 75 (73–82) | 75 (73–82) | 0 (−12 to +4) |
| Detail cold entry | 783 (770–817) | 787 (769–795) | −3 (−43 to +12) |
| Detail frame P95 | 250 (250–250) | 250 (250–263) | 0 (0 to +50) |

After the final in-place install on the onn, ART reported 46,089,008 bytes of odex, 43,123,368 bytes of vdex and 3,928,064 bytes of art. The compiled total was 88.83 MiB, down 5.56 MiB from the initial integration’s 94.39 MiB and **24.05 MiB above beta.10.12** (64.77 MiB). This compares the installed builds including follow-up code changes. [Final APKs](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44659629603/artifacts/14596171018).

On a fresh 2 GiB x86_64 guest, beta.10.12 and the final signed update used their own embedded dex metadata in successive install sessions. Both opened Home and Android retained the same UID. Measured before the first launch of each build, their compiled totals were 71.38 and 101.50 MiB respectively (+30.12 MiB). The update’s odex was 59,165,208 bytes. [Final signed guest comparison](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44661353586).

At 750 MiB free on a disposable API 34 guest, the signed update installed, ART reported `speed-profile` with reason `install-dm`, and Home opened. At 350 MiB free Android rejected the install session for insufficient space and the existing beta opened Home. At 85 MiB Android again rejected the session. Although its activity start returned success, the guest displayed the Android launcher throughout the 35-second observation window. After the filler was removed, the same signed update installed, compiled and opened Home. A physical Fire TV Stick remains outstanding. [Three pressure levels and recovery](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44646025999).

### C. Pre-existing defects

Guide reminders now mount at the shared navigation host. Guide and fullscreen clocks update while the bulk-work hold is active. The move of the maintenance loop conflicted with the original clock patch in LiveTvViewModel. PR #42 resolves the conflict on its own branch and retains the bulk anchor. [Guide reminders](https://github.com/abbaskip/Nuvio-ReSeq/pull/40). [Guide clock](https://github.com/abbaskip/Nuvio-ReSeq/pull/42).

On the follow-up signed APK a saved guide reminder appeared on Home two seconds after its due time across a process restart. Watch tuned the fixture stream. A second saved reminder appeared two seconds after its due time during fullscreen live playback. The fullscreen current/next overlay advanced from programmes 20/21 through 21/22 to 22/23. In the repeated run, after returning to the visible guide, the preview clock matched the device at 00:58 and 00:59 and its current programme advanced from 22 to 23. Both reminders again appeared within two seconds. [Signed reminder and fullscreen evidence](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44648539794). [Signed guide preview and reminder repeat](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44649623603).

The stored v3 fixture lacked staging tables and a failed publication deleted completed input needed for a retry. Both defects have fixes. Two native tests exposed late allocations retained after reset. The native arena test also assumed a full, empty 512-slot pool even though the allocator supports smaller pools and active users. Its revised check exhausts the available pool, verifies dynamic overflow and checks the exact recycled address. The JVM guide expectation that required deleting completed input conflicted with the Android retry test. The replacement assertion checks private-table cleanup and retries after reopening. [Guide storage](https://github.com/abbaskip/Nuvio-ReSeq/pull/41). [Allocator](https://github.com/abbaskip/Nuvio-ReSeq/pull/43). [Combined device suite](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44644347424).

The complete Android instrumentation rerun discovered 119 tests. Of these 106 passed, 13 retained their existing optional prerequisites, and zero failed. All three named guide cases and all three named allocator cases executed and passed. The new allocator regression also passed. Both the instrumentation and journey exit codes were zero. The paired playback suite passed against both binaries. [Android test XML](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44644347424/artifacts/14591665518). [Paired Continue Watching and Multiview playback](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44648757014).

### D. Maintainer and device sign-off

On the signed guest, TV Mix remixed the episode and opened the addon stream for episode 2. Its playing position advanced from 23 to 2,977 ms, paused at 3,164 ms and resumed. The detail screen rendered the expected rating of 8.3. A repeat played episode 1 and Advanced Discover returned three visible selectable results. Reset returned to the filter controls. Physical Sports-selected recording, system notification and Calendar, the full player controls, a low-storage Fire TV in-app upgrade and live Trakt, Simkl, MDBList, Gemini, Drive and third-party IPTV accounts remain open. [Signed TV Mix and ratings](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44650365432). [Advanced Discover, TV Mix and playback repeat](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44660870010/artifacts/14595113215).

### E. Nuvio + IPTV comparison

The upstream reference is Nuvio + IPTV 1.2.0-beta.2.1, ARMv7, published 18 November. Its signed APK SHA-256 is `52eca0e1675863228ecf7afb32d5017162d0e002b7e86c9133949d4602293a26`. The matched three-build physical traces remain pending. The maintainer resumed using the onn. [Upstream release](https://github.com/homelessbrian/NuvioPlusIPTV/releases/tag/1.2.0-beta.2.1).

### F. Launcher, rollback and large-library lookup

The archived launcher rows retain the same three content IDs in the same order at 60, 120 and 300 seconds. At 60 seconds the beta linked fixture `tt9900002` to its own series-detail page. Integration already had the episode projection and linked to `tt9900002:1:1`. At 120 and 300 seconds both carried the same season, episode and stream target. This observation confirms a difference in episode enrichment timing for the reported row. [Paired launcher data](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44582288333).

The signed rollback reproduced a failure on the transient plain-M3U shelf. The same fixture film played on integration. On signed beta.10.11 the cached card and its Play action were still visible, but selecting Play made zero requests to the available media endpoint. Selecting an original Xtream film in the same window successfully requested its media. The beta's refresh removed the temporary M3U titles. Re-upgrading offline recovered both titles, all three direct links and the same fingerprint for the 64 original Xtream films. The old code constructs an Xtream URL for the cached M3U item and cannot read its direct link. [Playback on the signed downgrade](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44647706777).

The repository lookup took 5.87 → 10.10 ms on the guest and 12.82 → 14.79 ms on the onn after adding 98,000 films. For the full stream picker, two 2 GiB guests used opposite library-size orders. Each cold-opened the same eight titles for each size. Timing ran from the remote press on **Play manually** until a selectable card displayed both the correct native provider and the title. With one added film the pooled median was 1.842 s (IQR 1.727–1.973). With 98,000 it was 1.962 s (1.742–2.189). The within-title median difference was +137 ms (IQR −78 to +405). The large-first guest showed +181 ms and the small-first guest showed −21 ms. The real low-end TV stream-screen check remains open. [Large library first](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44659955185/artifacts/14595736637). [Small library first](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44659955185/artifacts/14594998249).

The reported 3–4 a.m. college basketball cards illustrate a separate display issue. A pregame fixture stayed below live events and its countdown kept saying “Starting” after tip-off. The public ESPN and ReSeq feeds both had finals for all six women's fixtures at 3 and 4 a.m. Sydney. The display fix keeps the provider state and channel associations and shows “Status pending” until the real result arrives. [College basketball review](https://github.com/abbaskip/Nuvio-ReSeq/pull/44). [ESPN scoreboard](https://site.api.espn.com/apis/site/v2/sports/basketball/womens-college-basketball/scoreboard?dates=20261119&limit=1000&groups=50).

A force-stop also exposed the loss of already observed NCAA results when the first-paint cache omitted a game. On the signed guest, the app rendered and saved a 72–70 final. The test force-stopped the process, installed an older pregame fixture and emptied the first-paint page. On reopening Sports, the same game showed 72–70 FINAL and a different overdue game showed “Status pending”. The final candidate repeated both outcomes. [Regression tests](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44651668261). [Final signed restart and screenshots](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44661347446).

The signed Multiview check held the second fixture channel in the guide, opened **Open in multi view**, and confirmed the selected station, media request and sound in pane 1. Back returned to the same guide channel. The test repeated from the second matched station in a Sports game and verified that Back focused the same station in the game. Both paths passed again on the exact revision installed on the onn. [Shortcut PR](https://github.com/abbaskip/Nuvio-ReSeq/pull/45). [Final remote checks and screenshots](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44661350908).

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

### Signed live failover and archive playback

A separate Android user on the physical onn ran the normal signed integration app with a three-channel M3U and XMLTV guide. The selected primary returned HTTP 404. The guide switched to the working backup 8.25 s after the first failed request. From a process-cold Home, pressing Up revealed the favorited primary and its current programme. Selecting that card switched to the same backup in 8.53 s. SurfaceFlinger observed consecutive presented video buffers for both tunes. The Home test started before the guide had been opened in that process.

The first **Watch from the beginning** request tried the fixture's HLS archive, received HTTP 404, then opened the MPEG-TS archive 3.12 s after the first HLS request. The player's overlay showed catch-up and the expected programme. After a process restart, a second channel in that playlist opened its archive directly as MPEG-TS. Both replays produced advancing video frames. The intervals measure provider requests and exclude D-pad operation time.

The same signed installation displayed 111 games in All Events, marked two live, and one current cricket airing in the separate TV Now view. Its game page displayed the primary and backup under **Watch on your channels**. The primary returned HTTP 404, and the Sports preview opened the backup 8.20 s later. Its video surface advanced 2.77 s and retained 127 distinct presentation samples. The page marked the backup as playing. Selecting that match opened full-screen Live TV at 360p25, and Back returned to the original event with the primary match focused and the backup still marked as playing.

The signed published beta then opened the same favorite and date in the same Android profile. From cold Home the beta switched to the backup in 8.23 s, versus 8.53 s on integration. From the Sports event the same measurement was 8.29 s versus 8.20 s. Both displayed 360p25 full-screen video, restored the same event and channel focus and presented video buffers after failover. The guide still offered **Cancel reminder** for the future programme after the equal-version reinstall.

For Multiview, a second M3U provided a working copy of the failed channel. Both signed builds requested that copy 3.15 s after the original channel's first HTTP 404. The other pane then opened a different live channel. Three Android screen captures verified moving test-pattern pixels in the center of each unprotected video texture on both builds. The provider received distinct successful requests for the alternate playlist and the second pane.

Choosing **Record** on the same Sports event displayed **Recording set**. After forced restarts and equal-version reinstalls, both the published beta and integration displayed **Record** again on that event. The schedule's eventual execution remains unverified. On the backup's next guide programme, **Remind me** changed to **Cancel reminder**. Choosing **Record** in full-screen Live TV started Android's foreground recording service and showed the REC mark. Stopping cleared both. In a second recording the app presented 127 distinct video buffers and the latest presentation advanced 2.84 s during sampling. Stopping through the guide returned its action to **Record**. Android restricted direct inspection of the secondary user's recording files, so this run does not establish independent playback of the closed recording.

On two separate 2 GiB Android TV guests, the released and integration recording implementations each saved a ten-second H.264 archive and retained its title, duration, closed playlist and original segment bytes. With the provider then stopped, the production recording server played that archive and the decoder displayed frames with the playhead past one second. Each JUnit file recorded one executed test, one pass, zero skips and zero failures. [Paired offline replay](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44601478741).

A second paired run checked a future live recording with no early or late padding. Both builds persisted the selected source and start time. Android's real alarm then started fetching the segment during the 45-second recording window. Both saved the correct title, programme times and original H.264 bytes, closed the playlist and removed the schedule. Each build passed this check and the offline replay again, with zero skips, errors or failures. [Paired Android alarm and replay](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44608003440).

The saved guide reminder was due at 07:00 local. The signed integration still offered **Cancel reminder** for that programme just before it started. Neither build displayed its guide reminder dialog in the sampled due window. Both revisions define `LiveTvReminderHost` with a ten-second poll, and neither application's source contains a call to mount it. The programme was in the group used for the parental-control check. Delivery through this guide path remains open.

During continuous live preview, the visible guide clock stayed at 07:03 on integration through the 07:07 device sample. The signed beta showed 07:07 through the 07:10 sample under the same conditions. Both versions wait for active playback to end before advancing the guide's thirty-second maintenance loop. The selected fixture and TV clock were recorded separately.

With a PIN set in the disposable profile, locking the three-channel group reduced All channels from four to one and Favorites from one to zero. A cold Home hid the locked favorite. The guide rejected an incorrect PIN, accepted the correct PIN and showed the group's three original channels. Returning to Home in the same process restored the favorite and its current programme. Force-stopping the app hid the favorite again and the guide asked for the PIN on the next launch.

From a fresh process on each signed build, the same unlocked favorite on Home displayed the correct channel and current programme without opening the guide. Four screen captures sampled its focused card across about 90 seconds. The beta's filled bar advanced from 61 to 69 pixels on a 306-pixel track and its remaining time fell from 47 to 46 minutes. Integration's bar advanced from 77 to 87 pixels on the same track and its remaining time fell from 44 to 42 minutes. The runs started at different points in the hour. Focus stayed on the same card throughout.

## Scrolling and startup in the Android TV guest

Seven cold macrobenchmark iterations used the same 2 GiB API 34 x86 guest with SwiftShader. Cold initial display for Sports measured 1,762 ms in the release and 1,824 ms in integration. Guide measured 1,769 ms and 1,619 ms. The P95 CPU frame duration was 1,191.7 ms and 1,260.1 ms for Sports and 647.1 ms and 484.0 ms for the guide. Sports showed a small slowdown on this workload and flat physical P95. The automated builder test compares 300 live fixtures, partial channel matching, categories, follow filters, replays and day changes. [Paired minified runs](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44563757263).

A second seven-iteration run on an independently provisioned 2 GiB guest measured Sports initial display at 1,896 ms versus 1,737 ms and Sports P95 CPU frame duration at 1,375 ms versus 1,174 ms. Guide initial display was 1,652 ms versus 1,677 ms and guide P95 CPU frame duration was 598 ms versus 527 ms. Device and run variability matters for the Sports comparison. [Repeat macrobenchmarks](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44578442446).

Two separate 2 GiB fixture runs recorded the time from entry into the cold Sports route to its first nonempty repository event publication. The published and integration times were 2.138 and 2.931 s for a cached first batch of 506 events, and 2.931 and 4.548 s for first batches of 2,002 and 2,000 events respectively. In the second run, the first channel association appeared at 4.534 and 6.084 s. These are single observations from independently provisioned guests and measure repository delivery. [First paired event log](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44567918969). [Second paired event log](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44582288333).

On the same 2 GiB emulator, six process-cold launches per build measured the time from launching Sports to finding the first rendered game card in a seeded 2,000-game NBA schedule. Each test then opened the card and checked both teams and the channel section. The median was 2.611 s on the beta and 2.559 s on integration. The builds ran in published, integration, integration, published order, with three cold launches per fresh installation. Published samples in milliseconds were 4,172, 2,321, 2,549, 2,672, 2,783 and 2,497. Integration samples were 2,527, 2,614, 2,539, 2,892, 2,570 and 2,547. The comparison used the existing minified, profile-compiled targets. The comparison target and final signed build have identical Sports source. [Rendered first-card measurements](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44609425655).

## Catalogue compatibility

A 1.5 GiB API 34 Android TV guest ran the normally signed release APKs. The published beta imported a native Xtream catalogue with 64 movies. With the provider disconnected, the upgraded release still displayed the first, fourth and last titles at the same positions. A plain M3U with one live channel appeared with movie import off. Opting in preserved that live channel and exposed a movie and a series with both episodes in order. The normal player opened the movie and the second episode with their required HTTP headers.

Reinstalling the signed beta at the same version code retained the native catalogue. On first open the older beta briefly displayed a cached M3U shelf until its ordinary refresh. Stock Android TV 14 rejected installation of beta.10.11 at its lower version code. The catalogue uses schema v11 and the new M3U link index uses its own database.

A second test exercised a genuine lower-version install on an Android 14 developer emulator. It seeded synthetic source settings and used the signed release's TV controls to import 64 native movies. On upgrading to integration it opted into one plain M3U film and a two-episode series. With the fixture provider offline, Android installed the signed beta.10.11 in place at version code 10060, down from 10061. The older app opened the native shelf and retained the same ordered fingerprint for all 64 films. Its ordinary refresh removed the two M3U catalogue titles and preserved all three playback links in the separate database. Reinstalling integration offline recovered both titles with their original fingerprints. The main database stayed at v11 and Android retained the same app UID throughout. The signing certificate and SHA-256 of all three original APKs were checked. [Signed downgrade and recovery](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44620422558).

On the final signed APK a cold Continue Watching selection reopened the opted-in M3U movie with Reuse Last Link. The saved playhead was 80,862 ms. The first observed PLAYING position was 81,318 ms, 5,406 ms after selection. The provider had rotated both its media URL and its required header. Both requests used the current values, and scanning the release app's process log found zero references to the synthetic media URL, token or header. A pre-fix control made an expired request and received HTTP 403 before fallback recovered.

The physical onn 4K Pro and a 2 GiB Android TV guest each passed a functional test with two real ReSeq profiles, two local addon manifests, a native Xtream source and a plain M3U. The new M3U appeared after each profile’s existing saved order. Giving it first place and disabling the native source in the second profile preserved the first profile’s choices. Both profiles matched the same movie and retained their own saved link after switching back and forth. A match request carrying the previous profile ID returned zero streams. The test used the integration code in a separate fixture package and both temporary packages were removed from the onn. [Two-profile device test and emulator result](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44621812632).

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

Eight additional checks passed on each build. Local resume removal, local history removal and tracker-backed unwatch preserved the expected Continue Watching state. Removing a completed rewatch removed only the newest watch and retained both older and unrelated history. Removing a paused rewatch cleared only its pause and retained the earlier watched parts. Resuming from a stale card selected the current position. All 11 simulated Trakt scrobble and history writes matched in order, including the split TVDB episode, its two underlying provider episodes, and fractional pause progress. The production Multiview screen had two distinct players decoding video simultaneously, released the single-channel player and held bulk work. Backgrounding released its pane players. [Paired Multiview, undo and tracker tests](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44591314452).

A returning viewer saved a manual reminder for the upcoming NBA fixture in a 2,000-game Sports feed. Each test force-stopped the app and cold-launched its ordinary main activity. The published beta's in-app reminder was visible 21.971 s after launch and integration's at 23.033 s. Both showed Fixture Falcons at Fixture Owls starting in 44 minutes. Selecting **Open in Sports** displayed the correct teams, arena and **Watch on your channels** section at 25.334 and 26.647 s from launch. These are single accessibility observations on separate 2 GiB guests. The earlier fixture timed out during setup on both builds when it tried to select the game before opening Sports. [Cold Sports reminder screens and results](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/44611179118).

## Warning log cost

The desktop JVM ran six alternating warmed bursts of 4,000 warnings through the actual released and candidate Kotlin redactor and ring file implementations. Median elapsed time was 2,832 ms versus 39 ms, and median thread CPU was 2,140.5 ms versus 23 ms. Every run retained exactly 216,000 bytes with the same SHA-256. This measures a warning burst on the host filesystem. The existing redaction, rotation and crash-tail tests also passed. [Logger PR](https://github.com/abbaskip/Nuvio-ReSeq/pull/34).

On the physical signed integration, a failed channel produced warnings before the player opened its local report review. The first report held 2,204 cleaned technical entries and 15 app-start markers. After a force-stop and a cold launch, Settings opened a report with 2,301 entries and 16 start markers. All 16 entries at the tail of the first report reappeared in the same order before the new process marker. Both reports had zero occurrences of the synthetic playlist names, channel name, URLs and provider path. The comparison ran in the disposable Android profile and the report stayed in local review.

## Remaining device sign-off

| Area | Evidence collected | Outstanding on-device comparisons |
| --- | --- | --- |
| Installation and storage | Signed in-place upgrade on the populated onn, fresh and populated Android TV guests, ART footprint, five verified APKs | Repeat on a physical Fire TV Stick-class device, including profile compilation with low free storage. |
| Continue Watching | Real initial and return focus, order, shuffle, sequential selection and M3U resume. Paired local and tracker-backed unwatch, completed and paused rewatch, split-episode tracking and five TVDB responses. | Both Home layouts and profiles with a populated caught-up library, real Trakt, Simkl and MDBList account sync. |
| Sports | Five ordered match comparisons, rendered game details, first card on a 2,000-game schedule, full 300-live-event builder fixture, separate All Events and TV Now, Sports-to-player and return focus, backup recovery, recording and cold in-app reminder delivery and opening the game | User-saved and blocked matches with live Gemini refinement, personal teams and standings, Android system notification and Calendar entry, focus through all filters on the second device. |
| Live TV and playback | Ordered channel import, guide counts and return focus, 25 fps and 1080p50 refresh windows. Signed Home, guide, Sports and Multiview failover, persistent archive recovery, recording start/stop, guide reminders across restart and during playback, parental lock/unlock/relock, and channel holds from the guide and Sports. Paired Android recording alarm, offline replay, two-pane Multiview lifecycle and serialized Drive payload restore. | The Sports-selected recording on the physical box, authenticated Drive backup and recovery, and Fire TV hardware. |
| M3U VOD | Three import paths covered in tests, signed playback with required headers, cold link reuse after URL rotation, large catalogue import and lookup, signed lower-version rollback and offline recovery, independent source order and last links for two profiles on the physical onn | Fallback with a live third-party provider account. |
| Shared features | Full existing unit suite, release lint, six asserted profile journeys, byte-identical retained logger output and physical report recovery after a process kill. TV Mix played a selected addon episode, pause/resume advanced the real playhead, ratings rendered, and Advanced Discover displayed results and reset its filters. The published beta updated itself to RC1.0 on a 2 GiB Android TV guest and ART applied the profile. | Full addon/player controls and an in-app update on physical low-end hardware. |

The second connected physical TV was playing live media during qualification and the 1.5 and 2 GiB Android guests supplied memory-constrained coverage. A Fire TV Stick and authenticated test accounts were unavailable. The maintainer’s approved in-place update on the onn left it available for their own viewing. Repeated three-build physical journeys need a separate period of exclusive remote use. [Combined checklist](https://github.com/abbaskip/Nuvio-ReSeq/blob/perf-device-qualification/docs/performance-integration-checklist.md).
