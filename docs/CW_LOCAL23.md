# PR #29: local.23 watch-event removal

`CW_RULES.md` is the maintainer's specification. This change targets removal of a
split-part rewatch and immediate removal of a device watch awaiting confirmation.

## Causes

- Details exposed last-watch removal only for records carrying Trakt evidence.
  A newly completed device play had no removable event identity.
- The old removal path deleted a Trakt pause or history event, but matched device
  progress using an identical timestamp. The player completion and server pause
  can have different timestamps. Its recent-device receipt could rebuild CW.
- Removing a playback event intentionally retained compact completion evidence.
  For a completed selected Part 1 represented by a native 50% pause, this also
  retained today's Part 1 completion and could keep the Part 2 offer alive.
- Full-episode history translation deliberately excludes a selected part that
  covers only half of a native episode. That gate cannot resolve an individual
  paused watch for removal.

## Fix

A profile-local device watch journal assigns an identity before confirmation and
retains pre-play local state and provider-specific undo baselines. This metadata
is not included in tracker request DTOs. Imported IDs from another device do not
create an own-device removal offer.

Last-watch removal persists an undo, revokes the matching device record, receipt,
pending upload and compact CW evidence, and restores captured prior local state.
The native targets are resolved by content coverage for the dated play, including
a selected half of a combined episode. A subsequent play clips the old event's
time window so removing Part 1 cannot delete a later Part 2 play.

The undo remains pending for each connected tracker until its matching record is
known. It survives restart and is retried only during existing refresh triggers.
It filters stale matching echoes before CW reduction and TVDB projection. Other
trackers' progress is never used as a CW or Details selection input. The existing
presentation boundary holds the old card until the recomputed result is settled.
A positive selected-source catalogue/history check makes a finished show absent.

Provider removal is specific to the play:

- Trakt: delete the dated playback ID or individual history ID. A split-part pause
  does not authorize deletion of completed history.
- Simkl: delete the dated playback session. The unchanged normal scrobble/history
  output does not opt into separate rewatch sessions, so an older canonical
  completion is preserved. Whole-episode deletion is permitted only for a confirmed
  first watch with a captured absent baseline.
- MDBList: clear the dated playback ID. For a completed rewatch, restore the
  captured prior watched timestamp; remove a completion only with a captured
  absent baseline. Never substitute unwatch-all for missing prior information.

The legacy local.22 path correlates a retained, proven device receipt with the
selected part, including a delayed server timestamp. It does not fabricate a
historical baseline for older installations.

## Explicit behavior change from the supplied rules

Rule 2.1 replaces local.22's positive-but-under-1% exception. A native partial
below 1% is ignored before TVDB translation, consistently for CW and Details.
Earlier completed history remains eligible. This affects short accidental
rewatches that local.22 would resume. A native combined-episode pause above 1%
can still translate to a near-zero resume position in its second selected part.
Precise local positions with no known duration are not assumed to be below 1%.
The threshold applies to offers, not deletion: the underlying pause remains in
the episode progress map so "Remove partial watch" is still available. Projection
retains its native percentage to apply the minimum before translation.

## Fixtures and validation

- `DeviceWatchEventStoreTest`: normal and split rewatches; each selected tracker
  with the other two connected; removal before and after confirmation; restart,
  late echoes, prior history, profile isolation, sibling part protection and a
  local.22 receipt with a 20-second server timestamp difference.
- Same-episode successive rewatches retain their own identities when the older
  STOP finishes late; device IDs cannot bypass existing partial-removal evidence.
- `TvdbProportionalScrobbleTest`: recorded fully watched Office `alttwo` catalogue,
  Delivery Part 1 -> native combined pause -> Part 2 -> undo; exact prior history
  and Details offer restored; positive fully-watched proof; unchanged 9.8 output
  golden; native under-1% boundary.
- `ContinueWatchingRepositoryTest`: fake-clock normal/split undo, settled absence,
  delayed Details callbacks, no placeholder, durable restart.
- Existing 144-case cold-start reply-order tests retain cached-first publication.
- Trakt, Simkl and MDBList protocol fixtures verify single-event deletion and
  preservation/restoration of earlier history, including unknown-baseline refusal.

Commands (JDK 21; no full lint):

Full JVM result: 3,913 tests across 570 suites, zero failures/errors, two skips.

```powershell
./gradlew.bat :app:testFullDebugUnitTest --init-script build/local22-jvm.init.gradle --console=plain
./gradlew.bat :app:assembleFullRelease -PlocalBetaNumber=23 --console=plain
```

Development uses synthetic fixtures only. The signed beta installation is a
separate Onn-only `adb install -r` operation, with no app operation.

## Provider limitation

MDBList exposes a canonical watched timestamp and an episode-unwatch endpoint,
not a dated rewatch deletion endpoint. If a pre-play timestamp was never captured
(for example a play from an older build), the app cannot reconstruct that older
timestamp safely. It preserves completed history and retains the pending undo
rather than erasing the episode. A failed/unknown baseline is never treated as an
absent baseline.

Primary API references:
[MDBList API specification](https://raw.githubusercontent.com/linaspurinis/api.mdblist.com/main/apiary.apib),
[Simkl history behavior](https://api.simkl.org/api-reference/simkl/add-to-history),
[Simkl rewatch opt-in](https://api.simkl.org/guides/rewatches).
