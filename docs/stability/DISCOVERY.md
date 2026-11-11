# Stability programme: Phase 1 interim discovery

## Decision needed before continuing

**Phase 1 is incomplete. Do not approve Phase 2 from this report.** The programme's
section 1.1 says to stop and ask when the published tag, `dev`, and memory branch
disagree. Which base should be used: published **9.6.1 / 10051 / `c2b4d94f3`**
(recommended), the memory branch `9c35d5edf`, or a future `dev` containing it?

There is also **no configured Android emulator or installed system image** on this
machine. The SDK and emulator executable exist. Provision a disposable Android TV
AVD, or run the standalone emulator workflow after confirming the base. The
programme requires reporting this rather than treating unrun checks as passes.

Independent work completed: F1–F5 static review against the published source,
reproducible inventory, guarded emulator launch script, a standalone manual CI
workflow that builds minified benchmark APKs and runs existing instrumentation,
and host tests for the script. No app behaviour was changed. The 10-minute fixture
soak, expanded fixtures, runtime diagnostics, and time-sequenced launcher test
remain unfinished. Onn application tests were not started while the base is
unresolved. This is the programme's unattended-run checkpoint, not its Phase 1
acceptance checkpoint.

## Source and environment

Read-only GitHub queries on 11 November 2026 returned:

| Reference | Commit / state |
| --- | --- |
| Latest published beta | `v0.1.0-beta.9.6.1`, `c2b4d94f32372446fedb24ee951d0a026ddd4e1f`, published 09:15:36 UTC, regular release |
| Remote `dev` | `94a87b487c3a90688c44aed888787b1b5d7bf79c`, beta 9.5; does not contain 10051 |
| Remote memory branch / starting checkout | `9c35d5edfa3e3c9dabf23bf4465d448f361593fa` |
| Beta 9.6 | Draft/withdrawn, tag points to `98d71862c5ccb162347bdfaaa2a85e7a30c44e31` |

The memory branch differs from the 10051 source commit only in existing publication
records: `automation/publication-hold.json` and
`releases/0.1.0-beta.9.6.1-validation.md`. A diff of app sources, app Gradle configuration,
and the release workflow is empty. The independent tooling branch
`codex/stability-phase1-tooling` was made from the starting checkout, without
rewriting those records. Its provisional parent is not a decision to use that
base for the programme. Recheck remote refs and replay only the tooling commit
onto the agreed base before continuing.

No tags were fetched, created or changed. No releases, publication holds, versions
or release workflows were changed or dispatched. The attachment remains untracked.

Host: Windows, PowerShell, Python 3.10, SDK at the configured Android SDK directory.
`emulator -list-avds` returned no entries. `adb devices -l` identified one already
authorised Onn; no connection was enabled or authorised. Initial sandbox network
and adb restrictions were resolved using approved read-only access.

## Reproducible evidence

Run from the repository root:

```text
python scripts/stability_inventory.py --ref c2b4d94f3 --output build/stability/inventory.json
python -m unittest scripts.tests.test_stability_smoke
```

[static-inventory.json](static-inventory.json) contains committed-blob evidence,
file/line/source hits, hotspot counts and the exact revision. It deliberately
does not inspect configuration, credentials or device app data. Counts are lexical;
comments, declarations and helper files are included where stated. They are not
a runtime ownership registry or evidence of resource saturation.

History counts use explicit Sydney-midnight boundaries, 5–11 November inclusive
and 9–11 November inclusive, and all reachable parents. An initial abbreviated
date-only query gave 173/84; those ambiguous bounds have been superseded by the
explicit, reproducible 199/160 below. Commit frequency is evidence of review
exposure, not proof that any particular commit caused a crash.

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

## F3 — fragmented priorities: confirmed statically; contention unmeasured

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
ends; its effect on reminder latency is **needs device validation**.

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
| selectedOrderSeed → identity pending | Confirmed null path. `selectedOrderSeed` returns null on absent/non-TVDB metadata, missing progress snapshot or unresolved identity. `buildNextUpItem` records `pipeline identity-pending` and returns null. Overall show disappearance is not proven because projection seeds/snapshots may retain it. |
| Shuffle removes cards without cached videos | Not reproduced by source inspection. `applyHomeShuffle` retains prior selection or the item when no eligible selection exists. |
| Shuffle writes display TVDB ID, projection reads tracking ID | Not reproduced by source inspection. Shuffle now writes `episodeIdentity.trackingEpisodeId` and playback context. |
| withTvdbLabels drops unprojectable cards | Not reproduced by source inspection. It uses `map(::projectTvdbItem)`, whose fallback is `forSeriesChoice()`. |
| No TVDB key removes every supported show | Not demonstrated at this base. Fallback cards exist, but the upstream null path and first tracker refresh need the requested timed test. The exact unavailable-on-this-TV label and all provider permutations are not certified. |

`86af0341b` is relevant earlier fix history. Existing Home shuffle and TVDB tests
cover several helper cases, but were not executed in this run. Source findings
above do not establish the five-minute invariant.

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
and applying a feed retains parsed event collections. No heap peak was measured.

### Usenet

NewsArticles / UsenetFile hotspot counts are 9/8. Shared IO dispatch and resource
limits are confirmed as above. Real-provider behaviour and device blocking have
not been exercised in this run. Historical absence of all device/provider
validation cannot be proved from static code alone.

## F5 — separate memory budgets: confirmed, with formula corrections

| Consumer | Verified source policy |
| --- | --- |
| Managed VOD, `MemoryBudget.snapshotFor` | >=512 MB budget heap: 85%. Below that: min(65% of heap, heap minus 210 MB), then a device-sensitive floor. It is **not** simply 65% minus 210 MB. A 1-GB physical-RAM tier may halve the nominal large heap. |
| Default VOD, PlayerRuntimeControllerInitialization | Stock DefaultLoadControl path when buffer engine is disabled, with back-buffer configuration. The prompt's ~131/~13 MB library defaults were not independently derived from the resolved library in this run. |
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

### Home-hidden snapshot / launcher: confirmed source hazard, not reproduced at runtime

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

Required executable scenario remains **not implemented / not run**:

1. Seed a fake local/tracker show with episode 1 partially watched; display Home;
   assert saved snapshot and launcher provider row identify episode 1.
2. Open a fake player, complete episode 1, and close onto Details, Sports and
   Live TV in separate cases. Await the tracking refresh completion; record
   raw progress, snapshotVersion, snapshot episode identity and launcher row.
3. Background without visiting Home. Await actual channel reconciliation;
   assert its rows against the newly completed progress, not just a flow emission.
   The suspected failure is an old snapshot being republished.
4. Return to Home and verify repair; repeat with external-player return, app
   exit directly from player, profile switch, and tracker refresh delay/failure.

This specification is not a passing test and must be implemented before closing
the risk. Do not fix behaviour during discovery.

### Cricket opening memory: not reproduced (not run)

Streaming/cache changes are present. No low-RAM emulator, fixture cricket open,
five-repeat measurement or Onn application run occurred here. The existing
fixture sports journey is insufficient evidence for a large live Test-match feed
plus large guide. Build that fixture, assert the cricket detail actually opens,
and collect peak Java/native/graphics plus GC pause/thread/ANR evidence across
five opens. Use the same fixture for both environments. Real playlists, real
guide complexity, rendering and decoder behaviour with the TV on remain separate
maintainer validation.

## Measurements and tooling status

| Check / metric | 2-GB TV emulator | Normal TV emulator | Onn |
| --- | --- | --- | --- |
| 60-second minified launch, crash/ANR | Not run (no emulator) | Not run (no emulator) | Not run (base unresolved; fixture-safe package not built) |
| Existing instrumentation | Not run (no emulator) | Not run (no emulator) | Not run; not required on Onn by this phase |
| 10-minute soak, 30-second Java/native/graphics, GC, threads, CW IDs | Not run (no emulator); journey unfinished | Not run (no emulator); journey unfinished | Not run (base unresolved; journey unfinished) |
| Cricket peak heap, GC pauses, five opens | Not run (no emulator) | Not run (no emulator) | Not run (base unresolved; large fixture unfinished) |
| Reminder due-to-display delay under stalled import + guide build | Not run (no emulator) | Not run (no emulator) | Not run; no latency measured |
| Stalled NNTP blocked worker count | Not run (no emulator) | Not run (no emulator) | Not run; no thread measurement |
| CW IDs/count at 0, 1, 2, 5 minutes, first fake Trakt refresh | Not run (no emulator) | Not run (no emulator) | Not run; no card measurement |
| Launcher timeline | Not run (no emulator); test unfinished | Not run (no emulator); test unfinished | Not run |

No zero values substitute for missing measurements. There are no claims of
smoothness, lower memory, better freshness or ANR improvement.

- **Verified by test:** six Python launch-script tests, including background-crash
  failure/cleanup and rejection of production/debuggable APKs, physical devices,
  phone images and existing benchmark installs. YAML parsed successfully. Source
  inventory was generated from committed blobs. `git diff --check` passes.
  The full host tooling suite also passed: **108 tests, one skipped**, with
  `PYTHONPATH=scripts python -m unittest discover -s scripts/tests -p 'test_*.py'`.
  An initial invocation without the repository's required PYTHONPATH failed
  module imports; setting that path resolved it without changing existing tests.
- **Needs device validation:** the launch script itself, the workflow build/task
  names and actual instrumentation outcomes. The workflow is manual-only,
  read-only in GitHub permissions, separate from publication and has not run.
- `scripts/stability_smoke.py` requires a fresh TV emulator, installs only the
  non-debuggable `.debug` benchmark package, waits at least 60 seconds, checks
  process survival, captures logcat and before/after activity ANR records, fails
  on Java/native crashes or ANRs, records meminfo every 30 seconds, then stops
  and uninstalls its own package. It deliberately cannot operate on Onn.
- CI matrix requests 2,048/4,096 MB RAM with API 34 Android TV x86. Heap class is
  not thereby guaranteed to match Onn. It uses synthetic service inputs and the
  existing local fixture server, not repository service secrets. Account-live
  tests remain opt-in and should skip; inspect their reports rather than calling
  skipped tests passed. No accounts may be configured for this programme.
- Full JVM suite/lint were not rerun: no app code changed. No APK was built or
  installed. This is host-tool validation only.
- The existing logcat-gated `NuvioCwTrace` is partial CW tracing. It is **not** the
  requested complete hidden diagnostic toggle, per-show final reason ledger,
  gate transition durations or pool-saturation counters. Those are pending.

## Onn read-only before/after record

No test package was installed, launched, stopped or uninstalled. No maintainer
app was launched, stopped, updated or cleared; no app files, accounts or settings
were read. Package version inspection was limited to the programme's permitted
version check. No power, screensaver, volume, display or developer setting was
changed, so there is nothing to restore.

| Property | Before | After |
| --- | --- | --- |
| Model | onn 4K Pro Streaming Device | Same |
| Android | 14 | Same |
| Fingerprint | `onn/jarvis2/jarvis2:14/URO3.260203.035.A1/15640252:user/release-keys` | Same |
| RAM | MemTotal 2,998,840 kB; MemAvailable 1,252,576 kB | Availability is time-dependent; no app measurement |
| Heap properties | heapgrowthlimit 256m; heapsize 384m | Same; no test-app ActivityManager memoryClass measured |
| Power/display | Awake, powered, stay-on true; Android display state ON | Same power state; display not reread; physical TV power state unknown |
| stay_on_while_plugged_in | 3 | 3 |
| screensaver_enabled | 1 | 1 |
| screensaver_activate_on_sleep | 1 | 1 |
| screen_off_timeout | 600000 | 600000 |
| Maintainer package | versionCode 10051; versionName 0.1.0-beta.9.6.1 | Same |

Final read-only inspection: 2026-11-11 09:38:34 UTC. Values above are property
observations, not a smoke/soak result.

The device is approximately a 3-GB physical-RAM model, not the assumed 2-GB box.
Android reports a display ON even though the programme anticipates the TV being
off. Do not relabel this inspection "measured on Onn (display off)": there was no
application measurement and physical TV state was not established. Future runs
must record these properties again, inspect the test APK's package, use only
fixture data, mute playback and restore every changed setting in a finally path.

## Provisional ranking by user impact

This ranking is a static risk assessment, not a measured attribution of incidents.

1. Release validation misses execution of minified code and long-lived timing
   failures: every shipped area is exposed and regressions can bypass JVM tests.
2. CW has multiple removal/invalidation owners and depends on visible-Home
   lifecycle: stale or absent continuation cards directly break resuming shows.
3. Shared-pool thread blocking and inconsistent playback gates: imports can
   compete with or delay matching/reminders, despite apparently protective gates.
4. Separate resource limits and large resident data: potential memory pressure
   across playback, images and Sports; occurrence/magnitude still unmeasured.
5. Cross-area release bundling/high churn increases isolation and verification
   cost, making the other problems harder to diagnose and roll back.

New details for design after Phase 1: keep the already-fixed CW fallbacks; include
off-Home snapshot consumers and deletion writers; account for paused trailers;
retain the targeted TV Now path until a shared scheduler preserves its freshness;
distinguish nominal cap from actual residency; match heap class rather than RAM
alone; avoid assuming profile fixture APKs are safe for the everyday device.

## Remaining Phase 1 work and approval boundary

Resolve the base first. Then complete the fixture-only soak and cricket workload,
fake tracker/TVDB and stalled NNTP scenarios, launcher timeline test and release-safe
diagnostics. Run the launch/instrumentation workflow, measure both emulator RAM
tiers and the permitted Onn subset, preserve artefacts and record restored device
state. Replace every pending entry with evidence or an explicit environment
limitation. Only then request Phase 1 approval for solution design. No DESIGN.md
or Phase 3 fixes have been started.

No version/release change is recommended in this checkpoint. If later required,
remember that ReleaseSelector uses GitHub's single latest release and
VersionUtils.isRemoteNewer compares version names: reusing a name can strand
devices already running it. Publication decisions stay with the maintainer.
