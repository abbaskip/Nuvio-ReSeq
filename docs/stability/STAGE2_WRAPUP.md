# Stage 2 review checkpoint — 17 November 2026

Base: `a4d01d7de5c5bdd49899907b6af949919382e029` (dev after 9.10). Draft PR: https://github.com/abbaskip/Nuvio-ReSeq/pull/28.
## Delivery checks on the reviewed code

The 17 November follow-up tested app commit `c6a643cf2`. Both signed local.2 APKs matched the published 9.10 certificate. The bedroom Smart TV Pro (armeabi-v7a) upgraded from published 9.10 to 9.10.local.2, versionCode 10057, with its first-install timestamp preserved. The cold launch offered a single profile. The pass used Sports and Live TV and ended with ADB disconnected.

| Bedroom TV, one cold Sports Hub open with existing data | Observation |
| --- | --- |
| First fixtures, My Sports, first live-game channel | All visible by 6.51 s. The first populated screen showed Afghanistan v Zimbabwe with a Fox Sports channel. |
| TV Now discovery | 222 airings and 3/3 live fixtures matched. The first discovery took 34.52 s, roughly 35 s from opening Sports. |
| Opening the cricket game page | Two Fox Sports channel choices visible by 2.25 s. |
| Guide channel grid with a playing Fox Sports preview | 12 down presses, 55 frames, 36 missed deadlines (65.45%), p95 150 ms. A natural guide update was visible on the way into Live TV. Its state during the scroll itself was unverified. |

The screen times are upper bounds from timestamped captures. The TV Now duration and frame counts came from device diagnostics.

On the emulator, each playlist supplied 2,500 channels and 2,500 On Demand movies. The second import had 3,257 staged rows at process death. One restart cleared all four staging tables and the interruption marker and retained 120,000 guide listings. A separate download with 536 KiB free in the fixture guide cache produced a real ENOSPC error. Both previous guide files retained their SHA-256 hashes. After settling, both SQLite WAL files were zero bytes, the private work tables and partial files were gone, and both movie catalogues were intact. Files, cache and databases totalled 39,416 KiB against 39,384 KiB for an independent fresh load with both playlists (+0.08%).

Storage passed without a product change. The existing full JVM and minified checks apply to this code. The outstanding real-device concerns are guide scrolling and cold TV Now discovery. The earlier checkpoint below records what was known before these two checks.

## Earlier checkpoint

| Item | Status at wrap-up |
| --- | --- |
| Realistic fixture | Done: 50,001 channels, 2,400,048 varied listings, frozen public feed (4,133 raw events, 10,481,041 bytes). Original public feed bytes retained. |
| Sports index, incremental matching and TV Now | Implemented, with JVM parity/boundary coverage. Earlier realistic fixture matched 9.10 exactly: 1,514 events and 39,924 ordered channel matches. Final window/paging edits were not re-timed. |
| Bounded memory | Implemented: ranked result retention, bounded query pages and requested guide windows. Heap evidence identified about 1.1 million retained guide programmes; requested windows now retain 600 channels / 13,200 programmes in the observed scenario. |
| Sports display and feedback | Implemented: guide shows do not persist `awaitingUpdate`; first-pass Sports status joins the app status indicator; duplicate Choose Sports label removed; catalogue failure/retry and pending-match visibility covered by JVM tests. |
| Playback policy and imports | Shared playback signal and suspend/unwind checkpoints implemented; Home trailers do not block first loads in JVM tests. Sports pacing uses its own worker. Portal network bridge remains deferred. |
| Guide scrolling | Navigation pause/resume implemented and JVM-tested. Correct running-update frame comparison remains incomplete: the looping preview stayed playback-unstable. The permitted infrastructure retry was exhausted. |
| Low-RAM/storage | Partial: staging/temp recovery, atomic download replacement and storage-full preservation have JVM coverage. Device-level two-playlist recovery, fresh-size ±10%, and low-RAM Sports error/filter checks remain unverified. The earlier low-RAM attempt hit host disk exhaustion. |
| S2 matcher service consolidation | Not started. Separate bulk and targeted scorer contexts remain. |
| Bedroom TV | No contact or installation in this task. Its current installed version was not queried. |

## Recorded timings

These are existing development measurements, **before the final requested-window and paged-reader edits**, not a timing certification of the final commit. No new timings were run for wrap-up.

| 3 GB emulator, realistic fixture | 9.10 reference | Earlier candidate |
| --- | ---: | ---: |
| First fixtures available | 4.735 s | 4.291 s |
| Live-game channels | 46.096 s | 17.445 s |
| My Sports populated | By approximately 46.1 s (model observation) | Visible by 21.625 s (capture upper bound) |
| Full background pass | 177.547 s | 69.372 s |
| First TV Now discovery | 8.234 s | 4.974 s |
| Cached game-page channels | No isolated timing | Visible within 2.906 s |
| Cold first load, idle | 114.716 s | 124.025 s to stored/matched guide |
| Cold first load, trailer | 235.892 s including playback hold | Not completed |
| Guide download | 0.357 s | 0.221 s |
| Guide parse/stage | 68.452 s | 67.510 s including concurrent import/checkpoint work |
| Guide copy/index storage | Approximately 32 s | Approximately 43 s |
| Extended fixture matching | 262.754 s | 26.369 s; ordered results identical |

Guide searching/discovery dominated the reference Sports pass. Frozen-feed parsing was about 2.8 seconds, rather than the principal bottleneck. The corrected reference idle scroll sample had p95 81.862 ms; there is no valid running-update comparison. Earlier held-update samples are excluded.

## OOM evidence and cache decision

The accepted bounded-window samples were around 148 MB managed heap with no OOM beyond the initial ten-minute observer deadline. The last experimental run was stopped on request; its preserved log already contained a completed pass and no OOM. That run included the experimental disk cache and is not presented as proof of a completed final-code pathological pass.

The additional FTS **postings disk cache was removed**: no realistic-fixture or real-TV measurement demonstrated its benefit. Its schema, creation/eviction code and feature-specific tests were removed. The retained FTS search index, committed word dictionary, bounded direct paging and full-disk read coverage remain.

Portal `importPortal` still bridges suspending network calls from a synchronous SQLite writer. Converting it safely requires a resumable writer; it was explicitly deferred. No tests were quarantined or weakened. Final JVM/minified results are reported in PR #28 and `build/stage2/pass2/wrapup-validation.json`; logs and screenshots remain in that build folder.

No signing, TV install, merge, tag, publishing or release workflow is part of this wrap-up. The published rollback APK is retained at `build/stage2/published/Nuvio-ReSeq-v0.1.0-beta.9.10-armeabi-v7a.apk`.
