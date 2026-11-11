# Stability programme: Phase 1 discovery

**Phase 1 measurements are in progress. Do not approve Phase 2 yet.**
No design or functional fixes are part of this branch.

## Approved source and workflow registration

The maintainer selected published beta **9.6.1**, build **10051**, commit
`c2b4d94f32372446fedb24ee951d0a026ddd4e1f`. The original tooling commit was
rebased onto that exact source as `47c461eb4`; PR #14 remains the Phase 1 PR
on `codex/stability-phase1-tooling`.

GitHub refused manual dispatch while the workflow was absent from the default
branch. With explicit maintainer approval, **`1bce7ac66`** registers only
`.github/workflows/stability-emulator.yml` on `dev`, parent `94a87b487`.
Workflow-only follow-ups `567a772a7`, `250e00c1c`, `87af286be`, `f8c543dfb` and `b0eeb9529`
synchronise the CI compiler strategy, bounded heap fallback and explicit AVD
directory (including a corrected expression context), disposable-guest ADB
authentication and single-tier reruns. Across these commits, the only changed
path on dev is this workflow. The file is identical to the Phase 1 branch version. It has only
`workflow_dispatch`, `permissions: contents: read`, and no secret inputs.
Immediately after checkout it fails clearly when `scripts/stability_smoke.py`
is absent. Every dispatch uses `--ref codex/stability-phase1-tooling`, never
`dev`. Dev otherwise remains unchanged; Phase 1 app/tooling has not been merged.

The older memory branch `9c35d5edf` differs from the selected published source
only in existing publication records. No release, tag, publication hold,
version, or release workflow is changed by this programme. The original
programme attachment remains untracked.

## Tooling ownership and test isolation

Normal builds have `STABILITY_FIXTURES=false`; only explicitly opted-in debug
and minified benchmark builds include the fixture activities. They are protected
by Android's DUMP permission and restricted to the separate `.debug` and
`.iptvdebug` application IDs. Fixture builds ignore local service-properties
files, use synthetic localhost services, and deny external HTTP(S) through a
local non-forwarding proxy. Test packages never read production app data.

The diagnostics receiver is shell/DUMP protected, off by default and not
persisted. It emits bounded reasons/identifiers and counters to logcat, with no
new job, scope, timer, event buffer or disk cache. The existing two-thread
`BackgroundWork.quiet` and `reads` executors retain their priority, queue and
thread factory; each adds one saturation counter. Existing CW/shuffle/TVDB,
visible-Home and playback-idle gate calls and Sports reminder/detail events are
observed without changing their decisions. Priority classes remain those of the
observed work; Phase 2 has not introduced a shared policy yet.

Test-only registry: `StabilityJourneyActivity.lifecycleScope` owns a 30-second
heap/GC/CW/thread sampler (measurement overhead), fixture setup and navigation
(USER_VISIBLE), synthetic catalogue/guide workloads on the existing quiet pool
(BULK), and the NNTP fixture request on existing Dispatchers.IO
(PLAYBACK_CRITICAL), with a bounded 60-second player-state sampler. `StabilityVodActivity.lifecycleScope` samples the real player
once per second and mutes it. All cancel on activity destruction. Fixture
servers retain synthetic catalogue/feed data on the host, never in production.
No new production cache, pause signal or loop is introduced.

The host runner refuses pre-existing test packages, validates the minified APK and device ABI
identity, filters logs by the new process, and samples package memory. Its
finally path independently restores original power/screensaver/timeout values,
media volume and only its newly created ADB reverse mappings, stops/uninstalls
its test package, and verifies unchanged production version. Automated guard
checks inject workload, partial/absent install and uninstall failures, and incompatible ABIs. Onn selection is limited
to the already-authorised `192.168.1.121:5555`; its permitted run is smoke, soak
and five cricket opens only. Display/power are observed before and after each
run; physical TV state is not inferred from the Android display flag.

Reproduction uses `-PstabilityFixtures=true` for the `fullBenchmark` and
`fullDebug`/instrumentation APKs, `python scripts/stability_fixtures.py`, and
`python scripts/stability_run.py --device emulator --serial emulator-5556
--apk <x86-benchmark.apk> --aapt <aapt> --adb <adb> --output <new-directory>`.
The runner records APK SHA-256, checkout HEAD, runner SHA-256 and UTC bounds.
Local APKs were built from app source `89eeff8b3`; subsequent changes are host
tooling/workflow/report only. The x86 APK SHA-256 is
`2ea4ba7d5415d54cb062551880a88a3d2a17e6e9bf1bae216dff6d41a10adfb4`;
the Onn ARMv7 APK is
`8501455dc7f483ed1e181417c9ae07d8802fd1ab01a2fb103a61bedd6b7301cb`.

The fixture activity enables diagnostics for its process. For a separate manual
test-package run, the hidden toggle is `adb -s <serial> shell am broadcast -n
io.github.abbaskip.nuvioreseq.debug/com.nuvio.tv.core.diagnostics.StabilityDiagnosticsReceiver
--ez enabled true` (use false to disable). The 60-second main-activity smoke runs
before fixture seeding/toggle activation. Fixture VOD is a short four-second clip;
the trailer loops it. This proves the exercised player paths start, not sustained
high-bitrate/decoder performance.

Each output directory retains PID-filtered logcat, periodic meminfo, runtime/GC
samples, CW/launcher rows, environment and before/after cleanup evidence.
`python scripts/stability_summarize.py <directory>` derives `summary.json`.
The manual CI workflow uploads the corresponding artifacts and instrumentation
reports for 14 days. Onn raw device artifacts stay local; only the permitted
environment, measurement and restoration facts appear in this report.

## Reproducible static evidence

`python scripts/stability_inventory.py --ref c2b4d94f3 --output build/stability/inventory.json`
recreates [static-inventory.json](static-inventory.json), which uses committed
blobs, file/symbol/line references and explicit Sydney-midnight history bounds.
Counts are lexical evidence, not runtime ownership or saturation measurements.

## F1 — change volume and bundling: confirmed, counts revised

- 199 reachable commits from 5 November, including 121 with author name `Codex`;
  160 from 9 November. First-parent count from 5 November is 179. These measures
  include upstream history and are not counts of distinct production changes.
- Hotspots since 5 November: SportsRepository 13, SportsMatcher 8, NewsArticles 9,
  PlayerRuntimeController 8, HomeEpisodeShuffle 7, UsenetFile 8, SportsScreen 6,
  StreamScreenViewModel 4, PlayerRuntimeControllerStreams 5,
  PlayerRuntimeControllerInitialization 4, TvdbContinueWatching 4,
  HomeViewModelContinueWatching 8, LiveTvRepository 5, UsenetPlayback 6.
- Seven commits touch the Home directory on 10 November with the explicit local
  day bounds. This is a directory measure, not seven independent CW bug fixes.
- `9ba34fa00` combines Sports and CW: **26 files, +1,960/-61**, rather than the
  supplied 16/+1,540. `217375143` combines Home, Sports, TV Now and cricket:
  19 files, +558/-48. Reviewed commit messages and diffs confirm cross-area bundling.
- Translation churn is present: `3b2fa1a9d`, `c48b4eff6`, `7410d1d1e`.
  The latter two touch 37 and 38 translated `values-*` string files respectively.
- `docs/UPSTREAM_SYNC.md` specifies Monday/Thursday 02:00 Sydney syncs. This
  verifies the written schedule, not that every scheduled job executed.
- Current release metadata confirms 9.2, 9.3 and 9.6 are drafts. The existing
  9.6.1 validation record describes withdrawing and republishing 9.6 before
  changing its name. Earlier 6/6.1 withdrawal history was not independently
  retrieved in this run; do not infer it from current draft status alone.

## F2 — verification gap: confirmed for the release workflow

`.github/workflows/reseq-release.yml:validate` invokes `ci_pipeline.py validate`
for JVM/lint. `device_tests` is explicitly APK construction only. The release
job builds and verifies signed/minified APKs but does not launch one. There is no
emulator job in that workflow. Published source contains **521 Kotlin files under
app/src/test** and **34 under app/src/androidTest**, including helpers. These are
file counts, not executed tests; the supplied 518/37 is not reproduced at this ref.

`aa23fe40e` introduces Guava revalidation-cache use; `217375143` replaces it with
`core/image/RevalidationCooldown.kt`, used by
`StaleWhileRevalidateCacheStrategy.recentlyRevalidated`. The code transition and
fix history are confirmed. This run did not execute the old APK, retrieve its
device crash or independently rerun its historical JVM results. The claim that
this exact change crashed after passing tests remains historical evidence,
**needs device validation**, not a new reproduction.

Existing macrobenchmarks and `baselineprofile/fixtures` are useful foundations.
They do not establish the requested 10-minute cross-feature baseline. The fixture
journeys assert emulator hardware; their fixture activity is wired to generated
profile source sets, not every APK named benchmark. Removing those protections
or installing an arbitrary existing APK on Onn would be incorrect.

## F3 — fragmented priorities: confirmed; fixture blocking measured

Files mentioning each gate (declarations/imports included; exact hits in inventory):

| Gate or pool | Files |
| --- | ---: |
| PlayerSessionTracker | 11 |
| BackgroundWork idle / blocking idle | 5 |
| quiet | 5 |
| reads | 2 |
| heavy / awaitNoHeavy | 3 |
| navigation yield / notification | 4 |
| LiveTvFullscreen.active | 5 |
| isPlaybackActive (all references, not exclusively qualified calls) | 4 |
| guideBuilding | 3 |
| liveChannelOpen | 3 |
| Sports gate function names | 2 |
| Home gate names | 6 |

`livetv/data/BackgroundWork` owns two fixed threads at priority 9 (`quiet`) and
two at priority 10 (`reads`). `isNuvioPlaying` reads `PlayerSessionTracker.hasPlayback`,
which includes trailers; Sports gates and visible-Home work use `isSessionOpen`,
which counts full players only. `TrailerPlayerPool` opens its trailer session
for BUFFERING/READY without requiring `playWhenReady`. A paused READY trailer can
therefore keep the broader gate active; no pause duration was measured.

`OnDemandDatabase.replace` calls `awaitPlaybackIdleBlocking` between batches;
`StalkerVod` passes it as `beforePage`; `OnDemandRepository.streamItems` uses it
and its portal import wraps suspend fetches in `runBlocking`. Automatic work is
launched on `BackgroundWork.quiet`. `SportsHost` dispatches reminder computation
on the same pool after a 15-second delay. `SportsRepository.runMatch`, Live TV
work and artwork also use it. The blocking wait parks a worker until playback
ends. The initial 3-GB run measured a **61,857 ms** wait on `nuvio-quiet-2`
and a **3,910 ms** reminder delay. The requested concurrent guide build was
deferred: `checkGuideBudget` rejects builds during playback, and sampled
`guideBuilding`/heavy-job values stayed false/zero. Thus this measures reminder
delivery with a parked import worker, not the stronger three-way contention case.
Bypassing that production gate would change the baseline and was not done.

`sportsBackgroundAllowed` excludes a loaded preview (`channelKey != null`).
`sportsChannelLookupAllowed` permits targeted lookups with viewers, independent
of preview buffering, and drives `refreshTvNow` every 30 seconds. Two matcher
instances are confirmed at `SportsRepository.matcher` / `tvNowMatcher`.

Inventory finds **79 lines containing CoroutineScope construction syntax**, 68
also containing `Dispatchers.IO`. The claimed 71 long-lived scopes / 67 IO scopes
is not reproduced with that method; lifetime and ownership require semantic
classification. The claimed 24 singleton init starters has not been exhaustively
classified and is not treated as confirmed. There is no shared registration
policy in the inspected implementation.

`UsenetFile.prefetch`, `NewsArticles.warm`, and `UsenetPlayback` dispatch blocking
work on shared `Dispatchers.IO`. Confirmed constants: windows 6/12, add-on connection
limit clamped to 1..12, preparation timeout 120 seconds, article read budget 45
seconds. These are limits, not observed blocked thread or socket counts. The
runtime IO pool size was not measured; do not report 64 occupied workers.

## F4 — fragile pipelines: partly confirmed; several old drop paths fixed

### Continue Watching

The four named core files total **3,869 lines**: pipeline 3,220, shuffle 349,
TVDB helpers 133, reconciliation 167. Additional helpers are outside this total.
There are many early returns, but an exact count of 70 *show-removing* exits was
not established: `mapNotNull` can also group duplicate episodes without removing
the show.

| Supplied path | Result at 10051 |
| --- | --- |
| 6-second timeout vs 20-second TVDB resolution | Partly confirmed. `resolveMetaForProgress` now uses **22 seconds for selected orders**, six otherwise; five-minute negative TTL remains. `TvdbEpisodeResolver` has a 20-second timeout; order/client mutexes still serialize work. |
| selectedOrderSeed → identity pending | Confirmed null path. `selectedOrderSeed` returns null on absent/non-TVDB metadata, missing progress snapshot or unresolved identity. `buildNextUpItem` records `pipeline identity-pending` and returns null. Source alone cannot establish whether projection seeds retain the show; the later completion fixture did reproduce its disappearance. |
| Shuffle removes cards without cached videos | Not reproduced by source inspection. `applyHomeShuffle` retains prior selection or the item when no eligible selection exists. |
| Shuffle writes display TVDB ID, projection reads tracking ID | Not reproduced by source inspection. Shuffle now writes `episodeIdentity.trackingEpisodeId` and playback context. |
| withTvdbLabels drops unprojectable cards | Not reproduced by source inspection. It uses `map(::projectTvdbItem)`, whose fallback is `forSeriesChoice()`. |
| No TVDB key removes every supported show | Not demonstrated at this base. Fallback cards exist, but the upstream null path and first tracker refresh need the requested timed test. The exact unavailable-on-this-TV label and all provider permutations are not certified. |

`86af0341b` is relevant earlier fix history. Existing Home shuffle and TVDB tests
cover several helper cases and ran in the full JVM suite. Source findings alone
do not establish the five-minute invariant; timed fixture observations are below.

`HomeViewModel` still holds independent metadata, negative, badge, sibling,
TMDB-ID, next-up, older-seed, processed-ID and overlay state. Five visible LRU maps
have a 64-entry bound; `cwNextUpResolutionCache`, negative timestamps and
`discoveredOlderNextUpItems` have no fixed size bound at declaration, though
profile changes/invalidation remove entries. Disk enrichment snapshots,
WatchedSeriesStateHolder, tracker caches and provider/order caches add layers.
The supplied exact count of 18 was not treated as a well-defined metric; owners,
keys, invalidation and bounds are the design-relevant facts.

### Sports

**43 Kotlin files / 13,855 lines**, 26 MutableStateFlow construction mentions in
SportsRepository; two matchers. Repeated repository work includes updateLoop
(10/30 seconds), TSDB (30), visible base-feed refresh (30), TV Now (30), cricket
(30), watched/shared scores (30 or kick), and match kick (15 minutes). Add reminder
checks (15 seconds), view-model clock (15), game extras (60), and screen refresh
(90). Cadences depend on gates; they are not all active simultaneously. These
already exceed a simple eight-loop description. Retry and UI effects require
separate classification before a scheduler migration.

Streaming is confirmed in `SportsBaseFeed` and `SportsGuideCache` with Moshi
JsonReader. `SportsDiskCache.directory` moves legacy cache files by rename or
deletes recoverable files rather than loading their contents. Not everything is
streamed: `SportsRepository.matchLoop` still reads `matches.json` into a string,
and applying a feed retains parsed event collections. Measured fixture heap peaks
are reported below, separately from inferred per-consumer ownership.

### Usenet

NewsArticles / UsenetFile hotspot counts are 9/8. Shared IO dispatch and resource
limits are confirmed as above. The corrected 3-GB fixture run measured one
`DefaultDispatcher-worker` blocked in NNTP socket read at the 1/3/5/10-second
samples, then zero from 15 seconds onward. The first four seconds of media played;
later article responses stalled. The stack's Java state was RUNNABLE while its
native socket read was blocked (`NewsConnection`, recovered using R8 mapping).
Real-provider behaviour remains untested. Historical absence of all device/provider
validation cannot be proved from static code alone.

## F5 — separate memory budgets: confirmed, with formula corrections

| Consumer | Verified source policy |
| --- | --- |
| Managed VOD, `MemoryBudget.snapshotFor` | >=512 MB budget heap: 85%. Below that: min(65% of heap, heap minus 210 MB), then a device-sensitive floor. It is **not** simply 65% minus 210 MB. A 1-GB physical-RAM tier may halve the nominal large heap. |
| Default VOD, PlayerRuntimeControllerInitialization | DefaultLoadControl path when the buffer engine is disabled, with a 1.5-second back buffer. The bundled `app/libs/lib-exoplayer-release.aar` exposes video **131,072,000 bytes (125 MiB)** and audio **13,107,200 bytes (12.5 MiB)** defaults; verified with `javap -constants` on its `classes.jar`. These are track buffer targets, not measured allocations. |
| Coil, NuvioApplication.newImageLoader | 15/20/25% based on physical-RAM tier; uses MemoryBudget heap correction when smaller than runtime max heap. `allowHardware(false)` is set. Sharing a heap input is not an allocation ledger. |
| Live TV, playerBufferBytes | 30% runtime max heap, clamped **32–160 MiB**. |
| Guide build, LiveTvRepository | Checks used heap against 85% runtime max heap. |
| UsenetMemoryProfile in UsenetStartupTrace.kt | LOW 16 MiB/window 6; NORMAL 32 MiB/window 12; low-RAM flag or memoryClass <192 selects LOW. |
| TrailerPlayerPool.trailerTargetBufferMb | Own tier cap, additionally limited by a smaller user buffer target. |
| Parallel ranges | MemoryBudget combines configured VOD/range overhead locally; native limits also come from NuvioExoPlayerPerformanceHelper. No accounting for all other consumers was found. |

The wording "none knows about the others" is too broad: VOD/range overhead and
some heap-tier inputs are shared. There is no single reservation/reclamation
ledger spanning all these consumers. Limits cannot be added and called measured
usage. The causal claim that this alone explains the withdrawn Onn ANR remains
**needs device validation**.

## Known 10050 risks at the 10051 application source

### Home-hidden snapshot / launcher: reproduced on local 2-GB and 3-GB emulators

`runVisibleHomeWork` cancels children unless Home is visible and no player session
is open. It encloses the CW pipeline. `MainActivity` refreshes connected tracking
with INVALIDATED after player close; that does not itself run the hidden Home
pipeline. `AndroidTvChannelSyncService` observes snapshotVersion and reconciles
cached snapshots on backgrounding. It does not independently enrich a new episode.

Other writers prevent the stronger claim that snapshots *never* change off Home:
`StartupSyncService` prunes snapshots after remote deletions, profile/source changes
invalidate them, and TVDB order control invalidates affected content. Direct
snapshot consumers were found in Home and Android TV sync, not TV Mix's own path.
Details has an invalidation dependency. External-player return enters tracking
refresh and still needs lifecycle coverage.

**Measured on emulator:** `build/stability/baseline-local-3072/` contains the
completed minified run and independent cleanup verification. For the sequential
control `tt9900003`, before completion both the saved snapshot and actual
TvProvider program/intent referenced S1E1 (snapshotVersion 5). After the fake
player completed S1E1 and closed onto a non-Home route, raw progress said completed,
but both consumers still referenced S1E1. They remained stale while backgrounded
and at the 35-second sample after returning Home. The next-up builder had already
logged S1E2, but the saved snapshot did not advance until a later sample about
100 seconds after the Home return, during subsequent TVDB probes. That later
repair must not be attributed to a particular key change without isolation.

The corrected full local **2-GB** run independently reproduced the same four
timeline observations: S1E1 before completion, then stale S1E1 after closing to
non-Home, backgrounding and the 35-second Home-return sample, despite raw progress
marking S1E1 completed. SnapshotVersion remained 5 at those checkpoints. Both
snapshot and actual launcher-intent assertions were false at the latter stages.

The ordered fixture `tt9900002` also disappeared from Home after this completion
sequence, **before** the missing-key probe, while raw tracker progress still
contained it. Diagnostics subsequently recorded `pipeline identity-pending` and
`buildNextUpItem-null metaResolved=true`. Its absence persisted through the
missing/invalid/timeout/unavailable/recovery samples. This is the unresolved
ordered-identity fixture case, not proof that every correctly resolved TVDB
playback completion fails. The invariant failure is retaining no series-choice
card for a tracker-supported show while identity resolution is pending.

The executable host timeline records expected/observed snapshot and launcher
identities at each stage. Invariant failures are reported as discovery findings;
a missing stage or failed fixture journey fails the harness. Raw rows are in
`events.json` and `timeline-assertions.json`, with obfuscated stacks recoverable
using the benchmark mapping. No behaviour was fixed.

### Cricket opening memory: not reproduced in completed fixture runs

The large synthetic fixture has 2,000 sports channels, 96,000 guide programmes,
20,000 On Demand movies, 2,000 basketball feed events and one live Test cricket
match. The runner requires the actual cricket-detail diagnostic on each of five
opens, and samples package memory every second during that sequence.

**Measured on emulator:** `build/stability/focused-local-3072/` completed five
actual LIVE cricket-detail opens after a cold process start. Observed peaks were
143,287 KiB Dalvik heap allocated, 60,291 KiB native heap allocated, 147,200 KiB
Java PSS, 77,772 KiB native PSS and 280,372 KiB total PSS. Graphics PSS was reported
as zero by this SwiftShader emulator; it is not a physical GPU measurement.
No crash/ANR was detected. This does not reproduce the withdrawn release's ANR
and does not validate real-provider guide/feed complexity.

**Measured on Onn (Android display ON/Awake):** the matching 32-bit benchmark
completed the 60-second smoke, ten-minute soak and all five actual LIVE cricket
opens. Cricket peaks were 155,492 KiB Dalvik allocated, 66,472 KiB native allocated,
181,812 KiB Java PSS, 78,124 KiB native PSS, 34,992 KiB graphics PSS and 358,584 KiB
total PSS. Largest cricket-phase logged GC pause was 4.265 ms. No crash/ANR was
detected. These are sampled fixture peaks, not a claim of improvement or proof
that the historical real-data ANR cannot recur.

## Measurements and tooling status

Results are pending the full runs on API 34 Android TV at 2,048/3,072/4,096 MB
RAM. The permitted Onn subset is complete with independently verified restoration.
No zero values substitute for missing data.
There are no claims of improvement; these are baseline measurements.

Memory values below are **KiB**, from `dumpsys meminfo` for the test package.
Allocated Dalvik/native heap is distinct from Java/native proportional set size
(PSS); these columns must not be added together. Sampling is approximately 30 s
during soak and 1 s during cricket, so short peaks may be missed. GC phases use
the latest diagnostic event in log order; phase-boundary attribution is approximate.
The initial local 3-GB run's smoke/early CW overlapped host JVM/lint work, which
finished before the timed soak. The focused repeat and Onn workload did not
overlap a local app build.

| Completed soak | Dalvik allocated first / last / peak | Native allocated first / last / peak | Java PSS peak | Native PSS peak | Graphics PSS peak | Total PSS peak |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Local 2 GB, corrected LIVE fixture | 10,977 / 47,532 / 95,063 | 25,901 / 23,972 / 46,726 | 117,732 | 88,132 | 0 | 262,364 |
| Local 3 GB, preliminary timestamps | 10,707 / 47,517 / 99,374 | 22,735 / 28,880 / 55,915 | 112,468 | 98,212 | 0 | 269,582 |
| Onn, corrected LIVE fixture | 10,827 / 50,852 / 148,697 | 21,465 / 50,388 / 63,017 | 168,520 | 88,872 | 58,792 | 361,804 |

The preliminary 3-GB and Onn soaks have 19 periodic memory captures; the corrected
2-GB run has 63 because it supplements the NNTP section with 1-second sampling.
Capture work adds a small interval overhead. First-to-last increases include newly loaded screens, media
and caches. A single ten-minute pass cannot establish a leak or bounded long-run
growth. Emulator graphics PSS zero is the image's report, not zero graphics cost.

| Completed LIVE cricket sequence | Dalvik allocated peak | Native allocated peak | Java PSS peak | Native PSS peak | Graphics PSS peak | Total PSS peak | Max logged GC pause |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Local 2 GB, corrected full run | 101,972 | 58,035 | 188,116 | 78,128 | 0 | 326,897 | 40.642 ms |
| Local 3 GB, corrected focused repeat | 143,287 | 60,291 | 147,200 | 77,772 | 0 | 280,372 | 84.452 ms |
| Onn, corrected full subset | 155,492 | 66,472 | 181,812 | 78,124 | 34,992 | 358,584 | 4.265 ms |

Each row includes cold Sports loading and five actual LIVE detail opens. All
completed with no detected process death, crash or new test-package ANR.

The preliminary 3-GB cold CW samples at requested 0/60/120/300 s actually occurred
at **1.22/62.23/121.14/301.19 s**, including the fake Trakt refresh at the 60 s
stage. All contained the shuffle, ordered and sequential-control shows (3 cards).
An earlier automatic sample was empty during startup, then filled within about
0.7 s; the first-frame invariant is not certified. The subsequent ordered-show
drop and launcher timeline failure are described above.

The corrected 2-GB cold CW checkpoints occurred at **1.19/62.30/120.12/301.12 s**,
also retaining all three shows. Its stalled-NNTP samples again found **one**
socket-blocked dispatcher worker early and zero after the timeout, with actual
READY playback advancing through the first four seconds.

TVDB client probes (same preliminary 3-GB run): missing key **1 ms**, invalid key
**36 ms**, stalled response **24,006 ms** (harness timeout), service 503 **14 ms**,
and successful recovery **18 ms / 8 episodes**. Missing/invalid/503 used the real
client's `TvdbException` (obfuscated `ca.d0`); timeout used `TimeoutCancellationException`.
The ordered show had already disappeared before these probes, so they do not
independently prove that removing a key caused that disappearance.

An independent **fresh-install 2-GB TVDB control**, without fake episode completion,
then exercised the same five key/service modes. It retained **3/3 cards in every
mode** and cleaned up successfully (`tvdb-isolated-local-2048/tvdb-invariants.json`).
This control had already seeded metadata; it verifies retained cached cards, not
every cold/uncached order or correctly resolved playback-completion permutation.
It supports separating the unresolved-completion identity failure from key failure
alone. No fallback behavior was changed.

Reminder due-to-show was **3,910 ms** on the preliminary 3-GB run and **2,983 ms**
on Onn. Their import workers were parked for **61,857 / 61,894 ms** respectively;
maximum observed quiet-queue lengths across each complete run were **8 / 7**.
The guide was requested but deferred during playback, so simultaneous active
guide-build contention remains unmeasured at the unchanged baseline gates.
The corrected local 2-GB reminder delay was **4,048 ms**, with an import pause of
**61,913 ms**; the same guide-build limitation applies.

**Verified by test:** host suite 123 tests, one skipped, including failure-path
restoration guards. Minified and debug fixture APK builds pass. A disposable
3,072 MB TV emulator preflight loaded all channels/catalogue entries and showed
all three fixture series in CW. Preflight is harness validation, not the soak
baseline. Actual emulator memoryClass/largeMemoryClass is 192/576 MB despite
requested 256m/384m boot properties. The image ignores those overrides; this
mismatch must accompany the measurement tables. The final minified/debug/instrumentation APK builds pass.

**Verified by test, with an intermittent failure recorded:** the full JVM run
executed 3,490 tests: 3,487 passed, one failed and two skipped. The failure was a
Windows file-sharing error in `LiveTvGroupPreferencesTest` opening its temporary
DataStore file. An isolated rerun passed all four tests in that class, and a fresh
full rerun passed **3,490 tests, zero failures/errors, two skipped**. This does
not retroactively turn the first full run green. Original XML is preserved in
`build/stability/jvm-suite-first/`, the isolated result in `jvm-isolated-rerun/`,
and the final full rerun in the app's test-results directory.

**Lint is not green:** full lint reported 4,065 errors, 2,050 warnings and 48 hints.
The existing `lint_guard.py check` classified 4,064 tracked errors as inherited
and excluded one non-source finding. Its six failing comparisons are dependency
version advisories (`AndroidGradlePluginVersion`, `GradleDependency`,
`NewerVersionAvailable`); no dependency or lint-baseline change was made.
The new fixture manifest has two `DiscouragedApi` orientation warnings; no new
diagnostics-source errors were found. See `build/stability/lint-comparison.json`
and `app/build/reports/lint-results-fullDebug.xml`.

Manual CI attempts: `43005035828` was superseded by fixture validation;
`43006310645` exposed cold Kotlin compiler OOM before emulator startup;
`43008504974` was superseded by the live-score timestamp correction.
`43008991196` built successfully on the first completed tier, but its emulator
could not find the AVD created by `avdmanager`; remaining jobs were cancelled.
The workflow now uses the explicit AVD creator validated locally. A job-env
`runner.temp` expression was rejected before dispatch and corrected to
`github.workspace`. `43013669152` was superseded during build by the independently
observed Onn absent-package return-code correction. Active verification is
`43014856509`, using the repository's
in-process Kotlin strategy, one compile worker and existing runner-capacity heap setting. No emulator
result is claimed from any build-only/cancelled attempt.

In that run, the 4-GB build passed but its fresh emulator remained ADB-unauthorized
until startup timed out. The 2-GB tier reached its workload. A workflow-only
follow-up starts ADB first and uses the emulator's supported `-skip_adb_auth`
option for the disposable CI guest only. The single-tier 4-GB repeat is
`43019500282`; no physical-device authorization was changed.

Fixture validation found and corrected two input issues: ESPN dates require the
parser's UTC `Z` format, and synthetic schedule timestamps must precede live-score
responses. Otherwise the app's newest-timestamp merge can select a schedule's
PRE projection over LIVE. This is fixture validation, not a production fix or a
claim to have reproduced a real-provider incident. The initial local 3-GB timed
run used the earlier timestamps and is retained as preliminary evidence; live
cricket measurements must use the corrected inputs.

## Onn environment and restoration

The initial read-only inspection observed Android display ON, power Awake,
physical TV power unknown; heap properties 256m/384m; MemTotal 2,998,840 kB;
production build 10051 / 0.1.0-beta.9.6.1. An initial ARM64 install attempt failed
before app launch because the Onn userspace supports `armeabi-v7a,armeabi` only.
Settings and volume were restored and the test package was confirmed absent.
The runner now rejects incompatible APKs before changing state and avoids an
unnecessary uninstall of an absent package. Its activity dump is filtered on the
device to test-package rows before collection.

The matching 32-bit benchmark retry observed Android display **ON**, power
**Awake**, app memoryClass **256 MB**, largeMemoryClass **384 MB**, max heap
**402,653,184 bytes**. Physical TV power remains unknown. This is a separate test
instance with synthetic service configuration; its initial real-login screen
cannot log in, and fixture seeding supplies the test profile afterwards.
The full permitted workload completed. The runner's final `pm path` check returned
exit 1 because the package was successfully absent, and incorrectly marked cleanup
failed. The original result is retained. A subsequent independent `pm list packages`
check confirmed absence and independently re-read/compared settings, volume and
production version; all matched. The runner now uses that command and still
treats command failures as failures. Evidence is in
`build/stability/baseline-onn-retry/independent-cleanup-verification.json`.

| Onn state | Before | After / independent verification |
| --- | --- | --- |
| Android display / power | ON / Awake | ON / Awake |
| Physical TV power | Unknown | Unknown |
| stay_on_while_plugged_in | 3 | 3 |
| screensaver_enabled / activate_on_sleep | 1 / 1 | 1 / 1 |
| screen_off_timeout | 600000 ms | 600000 ms |
| Media stream volume | 25 / 25 | 25 / 25 (test ran at 0) |
| Production version | 10051 / 0.1.0-beta.9.6.1 | Identical |
| Separate benchmark package | Absent | Absent |

Model: `onn 4K Pro Streaming Device`; Android 14; ABI `armeabi-v7a,armeabi`;
fingerprint `onn/jarvis2/jarvis2:14/URO3.260203.035.A1/15640252:user/release-keys`.
The production app was not launched, stopped, read for account/data contents or
changed. All test media was muted. Only the test package and owned reverse mappings
were removed; no reboot or other SDK/device change was needed.

After this completed measurement, the maintainer unplugged HDMI and asked to keep
the box awake overnight. ADB still reached the powered box, but it reported
Asleep / display OFF. `svc power stayon true` woke it; later checks reported Awake
and stay-on true, with logical display ON despite disconnected HDMI. The global
stay-on value remains its original **3**. This later state change is separate
from the completed baseline and does not relabel its display observations.

## Ranking by user impact

This combines source evidence and fixture observations. It is not a causal
attribution of every historical production incident.

1. Release validation misses execution of minified code and long-lived timing
   failures: every shipped area is exposed and regressions can bypass JVM tests.
2. CW has multiple removal/invalidation owners and depends on visible-Home
   lifecycle: stale launcher state and an absent unresolved ordered-series card
   were reproduced, directly affecting continuation. Five-minute startup loss
   was not reproduced in the completed three-show cold-start fixture.
3. Shared-pool thread blocking and inconsistent playback gates: an import parked
   a quiet worker for about 62 seconds, and stalled NNTP occupied a dispatcher
   worker. Observed reminders stayed within the 15-second cadence; starvation
   with a concurrently active guide build was not reproduced.
4. Separate resource limits and large resident data: fixture memory residency is
   measured, but per-consumer ownership and the historical ANR's cause remain
   unproven. No ANR was reproduced by the completed Onn/3-GB fixture runs.
5. Cross-area release bundling/high churn increases isolation and verification
   cost, making the other problems harder to diagnose and roll back.

New details for design after Phase 1: keep the already-fixed CW fallbacks; include
off-Home snapshot consumers and deletion writers; account for paused trailers;
retain the targeted TV Now path until a shared scheduler preserves its freshness;
distinguish nominal cap from actual residency; match heap class rather than RAM
alone; avoid assuming profile fixture APKs are safe for the everyday device.

## Remaining Phase 1 work and approval boundary

The base and manual workflow registration are approved. Complete the fixture-only soak and cricket workload,
fake tracker/TVDB and stalled NNTP scenarios, launcher timeline test and release-safe
diagnostics. Run the launch/instrumentation workflow, measure all three emulator RAM
tiers and the permitted Onn subset, preserve artefacts and record restored device
state. Replace every pending entry with evidence or an explicit environment
limitation. Only then request Phase 1 approval for solution design. No DESIGN.md
or Phase 3 fixes have been started.

No version/release change is recommended in this checkpoint. If later required,
remember that ReleaseSelector uses GitHub's single latest release and
VersionUtils.isRemoteNewer compares version names: reusing a name can strand
devices already running it. Publication decisions stay with the maintainer.
