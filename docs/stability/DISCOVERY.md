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
The file is identical to the Phase 1 branch version. It has only
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
(PLAYBACK_CRITICAL). `StabilityVodActivity.lifecycleScope` samples the real player
once per second and mutes it. All cancel on activity destruction. Fixture
servers retain synthetic catalogue/feed data on the host, never in production.
No new production cache, pause signal or loop is introduced.

The host runner refuses pre-existing test packages, validates the minified APK
identity, filters logs by the new process, and samples package memory. Its
finally path independently restores original power/screensaver/timeout values,
media volume and only its newly created ADB reverse mappings, stops/uninstalls
its test package, and verifies unchanged production version. Automated guard
checks inject workload, install and uninstall failures. Onn selection is limited
to the already-authorised `192.168.1.121:5555`; its permitted run is smoke, soak
and five cricket opens only. Display/power are observed before and after each
run; physical TV state is not inferred from the Android display flag.

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

The executable timeline now records raw progress, snapshotVersion, snapshot episode
identities and this test package's actual TvProvider rows before completion,
after a fake player closes onto a non-Home screen, while backgrounded and after
returning Home. Runtime results are pending the full measurement run.

### Cricket opening memory: full measurement pending

The large synthetic fixture has 2,000 sports channels, 96,000 guide programmes,
20,000 On Demand movies, 2,000 basketball feed events and one live Test cricket
match. The runner requires the actual cricket-detail diagnostic on each of five
opens, and samples package memory every second during that sequence.

## Measurements and tooling status

Results are pending the full runs on API 34 Android TV at 2,048/3,072/4,096 MB
RAM and the permitted Onn subset. No zero values substitute for missing data.
There are no claims of improvement; these are baseline measurements.

**Verified by test:** host suite 113 tests, one skipped, including failure-path
restoration guards. Minified and debug fixture APK builds pass. A disposable
3,072 MB TV emulator preflight loaded all channels/catalogue entries and showed
all three fixture series in CW. Preflight is harness validation, not the soak
baseline. Actual emulator memoryClass/largeMemoryClass is 192/576 MB despite
requested 256m/384m boot properties. The image ignores those overrides; this
mismatch must accompany the measurement tables. Full JVM/lint and the final
workload builds are running.

## Onn environment and restoration

The initial read-only inspection observed Android display ON, power Awake,
physical TV power unknown; heap properties 256m/384m; MemTotal 2,998,840 kB;
production build 10051 / 0.1.0-beta.9.6.1. No Onn test has run yet. Each run will
record its own before/after state and test-app memoryClass/largeMemoryClass.

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
