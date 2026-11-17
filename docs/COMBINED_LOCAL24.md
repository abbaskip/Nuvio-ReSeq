# Combined Stage 2 and Continue Watching candidate

Temporary branch: `codex/combined-stage2-cw-local24`.

Sources merged without conflicts:

- `dev` at `a4d01d7de5c5bdd49899907b6af949919382e029`.
- PR #28 at `cd32a4c962fe4e79f0acb34c236df27cb1259543` (includes `c6a643cf2`).
- PR #29 at `c7afea87c0f31d669792595e19970749af798e81`.

## Database compatibility

Keep the Stage 2 schemas: guide 7, poster lookup 3, On Demand 11.
The guide and poster lookup databases contain rebuildable caches. If their
on-disk version is newer, rebuild all cache objects within SQLiteOpenHelper's
schema transaction. This includes unknown future objects and FTS shadow tables.
A failed rebuild rolls back both the old contents and version.

The On Demand database contains manual `poster_fix` corrections. It is therefore
preserved in full, even though many of its other rows could be downloaded again.
Read its version before starting the feature UI. If it is newer or cannot be
opened safely, show a compatibility notice and leave its contents untouched.
Its own downgrade callback also refuses the open. Do not clear app data.

Media3's `exoplayer_internal.db` is a playback cache index; its existing
StandaloneDatabaseProvider already rebuilds newer versions. A regression test
covers this behavior without modifying the library.

Eight fixture database tests cover schema versions, future guide and poster
rebuilds, unknown FTS/table/view/trigger objects, rollback on rebuild failure,
manual correction preservation, refusal of user-data cache rebuilding, normal
opens and Media3's cache handling.

## Device status and remaining validation

The bedroom Smart TV Pro was restored with `adb install -r` to its original
signed `0.1.0-beta.9.10.local.2`, versionCode 10057. Both firstInstallTime entries
were unchanged and Home was confirmed. No playback or tracker mutation occurred
during the stopped local.23 retest. Provider snapshots for The Office and Friends
were identical before and afterwards. The TV was disconnected.

The final local.24 build and bedroom retest are held for the separate CW
card/source-resolution fix. After integrating that fix, run the full JVM suite
and signed minified build before installing. The safe rollback predecessor is
the archived bedroom local.2 ARMv7 APK, which uses the same database versions.
