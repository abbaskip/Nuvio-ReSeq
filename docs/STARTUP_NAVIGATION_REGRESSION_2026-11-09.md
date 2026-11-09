# Startup navigation regression follow-up

Reported: menu navigation feels clunky, especially on startup, with a later crash.
Baseline: published beta 9, `150883f1f44ec436bd377d70dc94f5b47842ef4c`.
The installed version and crash context have been requested; no new crash trace is
available. The older November 5 ANR evidence is not evidence of this crash.

## Confirmed blocking path

`OnDemandDatabase.replace` held the SQLiteOpenHelper instance monitor throughout
an import, including network waits and the playback-idle checkpoint added to
trailer handling. Android's `getReadableDatabase` also acquires that monitor,
even for an already-open database. WAL and small transactions could not help a
reader waiting outside SQLite. See the [Android helper implementation](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/database/sqlite/SQLiteOpenHelper.java).
Two concurrent Robolectric tests on unchanged
beta 9 both timed out: one while waiting for a network page, one while yielding
to a trailer. Original failing XML and logs are retained in `build/validation`.

Use a separate catalog writer monitor for the same four serialized operations.
Imports, compaction, import policy and browse-artwork updates remain serialized;
readers can use the committed catalog during pauses. Import batching, partial
response retention, removal rules, network behaviour and trailer policy remain.
An additional concurrency test checks that two imports still serialize and retain
both playlists' data.

## Bounded guide priority work

The guide priority observer previously mapped all channel keys on the UI thread,
then used only the first 400. Bound the mapping before allocation and execute the
observer transformations on Default. A 100,000-channel fixture now reads exactly
400 entries, preserving the same keys and order. Favorites and Home priorities,
the 1.5-second debounce and guide loading behaviour are unchanged.

These are confirmed internal problems and fixes, not proof of the cause of all
reported menu lag or the crash. No physical devices, ADB or live accounts are used.
No settings, navigation controls, feature visibility, freshness intervals or
background service availability are reduced. The fresh-install defaults branch
is separate and has not been merged here.

## Manual follow-up

Once a combined candidate is approved, compare cold startup and side-menu focus
while the catalog refreshes and Home trailers start. Open On Demand, scroll,
return Home and switch sections; verify trailers, Live TV/EPG and Sports remain
available. Record the installed version, time and navigation action if the crash
recurs, and submit the app's diagnostic report if available.

## Validation

- Full host suite: 3,334 tests, zero failures/errors, two existing skips.
- All five new tests passed, including concurrent reader/writer tests and bounded
  priority-channel access. The paused-import tests complete in 0.270 s and 0.175 s
  after the fix, versus a two-second reader timeout each before it. These are host
  fixture timings including setup, not Android TV navigation measurements.
- Full debug APK build succeeded. No installation or hardware test performed.
- Complete lint report and released-source regression guard passed. 4,100 inherited
  errors remain; no new, worsened or returning errors. Immutable baseline unchanged.
  External available-version advisories were verified using the existing approved
  advisory handling. No dependency or build-tool upgrades were made.
