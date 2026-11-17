# Multiview M1

Base: `dev` at `a4d01d7de5c5bdd49899907b6af949919382e029`, including `8bd4f83b2` and published 9.10. This is an isolated M1 branch and draft PR. Nothing is merged or published.

Source: [homelessbrian/NuvioPlusIPTV](https://github.com/homelessbrian/NuvioPlusIPTV/tree/e3fbccb05563a5456669373ee6713e24be87d00b), pinned to `e3fbccb05563a5456669373ee6713e24be87d00b`. Upstream file headers are retained. The port includes the session core, per-pane player, media-source builder, pane design, layout/focus policy, Channels picker and upstream JVM tests. ReSeq's guide projection and visibility rules supply the picker.

## Behavior

- Entry is the **Multiview** button beside the guide gear. Back returns to the guide. Layouts are filtered to the device limit, which is shown in the layout picker. Selecting a pane selects its sound; holding OK zooms it while the others keep playing silently.
- The single Live TV player releases synchronously before the session is created. Exiting releases the session before navigation can resume guide preview. **Stop playback** clears and releases every pane.
- A session owns its contribution to `BackgroundWork.liveChannelOpen` while open, including while backgrounded. A delayed single-player detach cannot clear it. The Sports bulk gate and ambient/screensaver eligibility consume the same flag. Pause/stop lifecycle events release every player. Foreground resumes the in-memory channel choices at the live edge. Profile/visibility changes are checked again at playback boundaries.
- The route needs an in-memory entry intent. A persisted boolean guide-return marker handles a cold process restart too; it contains no playback information and is cleared on normal exit. Neither pane choices nor stream links are saved. Layout and collapsed playlist preferences alone may survive.
- Pane cap is `min(4, AVC/HEVC preferred-decoder instances - 1, memory tier)`. Failed capability reads assume two instances. Software fallback counts cannot inflate a hardware decoder's limit. Low-RAM, small-heap and 2 GB-class devices cap at two; the measured 3 GB-class Onn with a 384 MiB heap caps at three; larger tiers can reach four. Buffer allocation remains heap-based. A device with no spare decoder cannot start a session.
- Every initial start, retry, foreground recreation and backup replacement goes through the core's synchronous provider gate before player allocation or Stalker link resolution. Duplicate Xtream playlists and pasted Xtream M3U links share an account identity; Stalker URL aliases share one portal allowance. Known M3U account maxima are respected too. The gate counts current pane reservations against `maxConnections`; adding cached `activeConnections` would double-count these panes or the released guide preview. Current capacity used elsewhere is handled by provider rejections, since the saved activity snapshot cannot reserve that capacity. Unknown maxima use normal playback error handling. A blocked pane explains the allowance and offers another playlist copy through the picker/Backup action.
- The upstream shared buffer allocation (at most 96 MiB across all four slots) is below the existing single-player allowance for the same heap. Empty or zoom-covered slots do not increase the allocation.

## Replacements and exclusions

The HTTP factory, headers and User-Agent follow ReSeq's existing single Live TV player (`DefaultHttpDataSource`); no upstream VPN transport or scheme restriction is present. ReSeq's `StalkerPortalClient` resolves a separate link per pane. DRM scheme/header handling, captions, reconnect/format fallbacks and masked error reports remain. ReSeq's existing single-player format logic is untouched; the small upstream format helper serves Multiview panes.

Upstream `LiveTvPlayerHooks`, `StalkerGateway`/`StalkerMediaPolicy`, remote-button catalogue and Sports watch models are not imported. Only the D-pad/Menu/OK handling required by M1 is retained. The Sports tab, queue, Add to Multiview menus, Sports Hub entry and reminder offer remain M2. Calendar and optional Maximum panes settings are excluded. No production CW, tracker, TVDB, source-tier, NNTP or Sports matching code changes.

## Validation

Initial emulator/fixture acceptance on 17 November 2026 (before the authorized Onn follow-up):

| Check | Result |
| --- | --- |
| Full production JVM suite | **PASS:** 3,797 tests, 0 failures/errors, 2 existing opt-in skips; 572 suites. All 57 Multiview tests passed. |
| Minified builds | **PASS:** `assembleFullBenchmark -PstabilityFixtures=true` and signed `assembleFullRelease -PlocalBetaNumber=1`, including R8 and release-vital lint. |
| Multiview emulator | **PASS:** two distinct players decoded the fixture playlist; production Channels picker filled both panes; background released the panes; Stop cleared them; the shared playback signal was checked. Screenshots are under `build/m1-acceptance-core/`. |
| `a4d01d7de`: completed rewatch | **PASS:** remote OK on the actual episode options action removed only history ID 2; older ID 1 and unrelated ID 3 survived. Both split parts retained their watched state and CW membership. |
| `a4d01d7de`: paused rewatch | **PASS:** the same Details action deleted only playback ID 4321. Completed IDs 1 and 3 survived; both previously watched split parts and CW were preserved. |
| `8bd4f83b2` | **PASS:** an intentionally stale durable Delivery Part 1 card rebuilt as Part 2 from the current 49.75% native pause; stale enrichment could not replace it. |
| 9.8 tracker contract | **PASS:** JVM exact-contract tests and emulator request capture for normal play, watched and unwatched. |
| Existing CW flows | **PASS:** cold start at 0/60/120/300 seconds, completion away from Home, background/return, shuffle, retained-resume unwatch, remaining-history unwatch and tracker-backed unwatch. |
| 2 GB emulator limit | **2 panes:** 1,974 MiB reported physical RAM; 512 MiB effective app heap; not Android low-RAM. |
| 3 GB emulator limit | **3 panes:** 576 MiB effective app heap; not Android low-RAM. Two panes were played on this emulator. |

The two skipped tests are the existing opt-in live TVDB integration and local-provider NNTP profiling checks. An earlier production JVM run hit a Windows temporary-file sharing violation in `LiveTvFirstRunTest`; both isolated cases and the subsequent complete suite passed without production changes. Emulator harness issues were corrected by extending the four-second video-only fixture to sixteen seconds without changing its compressed H.264 data, using live-style playlist URLs, isolating scenario state and activating the TV options with remote OK. Passed cold-start/CW checks were retained; only affected or remaining phases were rerun.

The fixture scripts are `scripts/multiview_fixtures.py` and `scripts/multiview_acceptance.py`. Host ports default to 48791–48793 and are reversed to the fixture app's 38791–38793. They extend the existing synthetic tracker and reject physical-device serials. `--cw-tail`, `--remaining`, `--regressions-only` and `--pause-rewatch-only` allow a failed harness phase to resume without repeating unrelated checks. The paused-rewatch server uses `M1_REWATCH_PAUSE=true`.

Evidence is retained locally under `build/m1-acceptance*`, `build/m1-device-2048/`, `build/m1-production-final-jvm-summary.json` and `build/m1-handoff.json`.

All emulator installs in that acceptance used the separate fixture package. The signed local.1 beta was built without fixture sources and was not installed; neither physical TV was contacted during that phase. The later authorized Onn trial and local.11/.12 updates are recorded in [MULTIVIEW_ONN_TRIAL.md](MULTIVIEW_ONN_TRIAL.md). The bedroom TV remains excluded.

The two local APKs are `app/build/outputs/apk/full/release/app-full-arm64-v8a-release.apk` and `app-full-armeabi-v7a-release.apk`. Both are version `0.1.0-beta.9.10.local.1`, versionCode **10057**, and contain only their named ARM ABI. Their signing certificate SHA-256 is `3e4b06eb03be89c12f50b21a809b857a3f3c90f8687664f5efc5ee0b910fc7fe`, matching published 9.10. The reference APK was checked against GitHub's published asset digest `ab33255194a14bcb8bb13d27e256076e667ce8c65f7f546be0ea8e9ce5142d07` before comparing certificates. Fixture activities are absent from the beta manifests.

## Maintainer TV trial

When a TV is free, check the reported decoder/pane cap and guide-button focus first. Then try two panes, and three/four only when offered, with the channels/codecs normally watched. Check audio switching, zoom/back, layout reduction, Stop, Home/background/return and process restart. Verify real-provider allowance messages and an available backup from another playlist. Check reconnects, protected streams/captions and guide-update smoothness with the normal playlist. Emulator results cannot establish hardware decoder concurrency or real DRM/provider behavior. Keep the PR draft until that trial is complete.
