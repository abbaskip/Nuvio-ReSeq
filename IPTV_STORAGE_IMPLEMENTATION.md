# IPTV storage implementation

Base: origin/dev `2584a5534` (ReSeq 7.5). Branch: `codex/iptv-storage`.

Authorized: implement storage proposals and English suffix source-matching fix, unit/device
test including Onn. Excluded: shared TV guide schedules. Preserve all matching candidates
unless the user explicitly excludes an import type/category. Do not wipe production data.

## Work

- [x] Language decoration cleanup and persistent lookup-key migration; identity regression tests.
- [x] Atomic APK download, orphan/expiry sweep, successful-install cleanup, on-demand downloads.
- [x] EPG staging abort/startup cleanup; measured, free-space-aware DB maintenance.
- [x] On Demand import types/category selection; roundtrip through Google backup payload.
- [x] Compact catalogue schema, safe migration; source-only bounded artwork metadata mode.
- [x] Derived record/source temporary-file cleanup and guide detail retention options.
- [x] Release-compatible size-only report and storage maintenance UI.
- [x] Unit/instrumentation regression tests, synthetic fixture provider and measured savings.
- [x] Actual APK service verification; exclude personal TMDB build inputs.
- [x] Final combined Onn run and pairing-code verification.
- [x] Release optimization check.

## Constraints

Use project service configuration privately; never print or commit values. Validate finished
APKs before any Onn installation. The Onn is `jarvis2`, previously `192.168.1.121:5555`;
`.102` is a different TV. Preserve production package/account and recordings. Recheck active
playback before device interaction. Record exact tests, measurable savings and limitations.

## Findings

7.5 title cleanup removes uppercase bracket tags but `(en)` survives. Imported `norm` keys
also use a different cleanup path from final parent verification. Both must agree, including
existing records, while preserving real titles and rejecting contrary IDs/years.

Storage investigation and SQL reproduction live at `../../investigations/storage` from this
worktree (in the original workspace). Existing 7.5 EPG orphan and APK cleanup findings apply.

## Behavior

Catalogue version 11 interns provider/kind identifiers and shared artwork URL origins. It
preserves title IDs, variants, years, extensions, sorting and matching indexes. Low-space
devices retain the old readable/writable schema until migration can finish safely; English
lookup works during deferral. Matching-only imports omit icon/genre metadata, while browsing
fetches artwork addresses for nearby cards with two concurrent requests and a 500-address
cache per provider. Artwork updates preserve loaded rows, order and focus. Actual images
continue using the existing shared image cache; initial import does not download every poster.

Movie/series switches and category selections default to retaining every candidate. Only
explicit exclusions remove sources. These device-wide choices survive backup payload
validation, export and restore, including legacy backups. Explicit trailing language tags
are cleaned consistently in indexed lookup and final identity verification, while source
labels remain intact and contradictory IDs/years/episodes are rejected.

Pending APKs expire after 24 hours, with a two-hour active-install grace period. Successful
callbacks delete only their exact installer, with package-replacement/startup fallback.
Banner-only checks do not predownload. Downloaded guide/playlist sources migrate into
Android-reclaimable cache storage with a readable legacy fallback. Cleanup removes abandoned
stages/temporaries and expired derived records, preserving guide programmes, manual poster
corrections, provider logins, selections and recordings. Description retention is optional;
guide titles/times/assignments remain independent. No shared guide schedules were introduced.

Settings → Live TV → IPTV storage and cleanup reports DB/WAL/SHM groups, source downloads,
installers, image caches and recordings separately. Compaction checks transaction/checkpoint
status and free space, reporting measured bytes reclaimed rather than estimated free pages.

## Validation

- Complete unit suite: **3,126 tests, zero failures/errors, two skipped**.
- Full debug app and harness assembled; Play Store debug Kotlin compiled. Full release
  compilation and R8 optimization passed, with uploads disabled (no production install).
- Onn database cases cover real v10 migration, preserved variants/URLs/indexed lookup,
  50,000-row interrupted staging cleanup, descriptions off, artwork bounds, category pruning,
  low-space deferral/recovery and preservation of manual corrections/recordings.
- Synthetic Xtream integration runs on the Onn. English Friends deliberately lacks an
  import-time TMDB ID, requiring suffix-cleaned lookup. Checks cover full/source-only candidate
  parity, lazy artwork, explicit exclusions, provider failure, backup-payload clear/restore/
  reimport and decoding generated video through the matched English episode URL.
- Final combined Onn run: **11/11 passed in 79.332 seconds**, including both D-pad tests:
  storage actions, import switches, category selection and saving.
  Fixture screenshots were retrieved and visually inspected.
- Onn 30,000-title fixture: **12,943,360 → 7,385,088 bytes**, about **43% smaller** after
  actual maintenance, retaining every title. Synthetic result, not a measurement or prediction
  of the friend's 451 MB data. Their actual directory/database distribution remains unknown.
- Approved project backend and Google Drive app configuration were used privately. No personal
  TMDB values were reused. Actual app/harness APKs were verified before every installation;
  the actual debug BuildConfig backend is configured and its TMDB field is empty. Credentials
  and sign-in codes are excluded from logs/commits; private local properties remain ignored.
- Isolated Onn package: `io.github.abbaskip.nuvioreseq.iptvdebug`. Production package/account/
  data were preserved. No real IPTV provider or Google account was used in fixture tests.
- After the final install/test run, a fresh UI snapshot confirmed that both QR and pairing
  codes were generated. Codes were withheld; temporary device dumps were removed and the Onn
  returned to its launcher.
- Android lint completed with existing project debt: **4,109 errors, 1,953 warnings, 48 hints**.
  The only error in a modified file is an unchanged locale-reading line in
  `LiveTvSettingsContent.kt`. No new storage-code lint errors were reported. Conservative
  `usableSpace` checks and transaction-style suggestions produce nonblocking warnings.

Release R8 optimization passed in 5m 38s. R8 emitted only its startup-class distribution
warning; no optimization errors occurred.

Tested app: `app/build/outputs/apk/full/debug/app-full-armeabi-v7a-debug.apk`.
SHA-256: `4bb7b0f77fe850ad1cc59dd0f645f735a2c0d93dc937e7008744e75412171e25`.

## Reproduction

```powershell
.\gradlew.bat :app:testFullDebugUnitTest :app:assembleFullDebug :app:assembleFullDebugAndroidTest :app:compilePlaystoreDebugKotlin --offline --console=plain
python scripts/verify_debug_apk_config.py --properties local.properties --apk app/build/outputs/apk/full/debug/app-full-armeabi-v7a-debug.apk
```

Validate each actual APK before installation. Run the device classes under
`com.nuvio.tv.livetv.storage` plus `com.nuvio.tv.PackagedServicesTvTest` against the isolated
debug package. Build/test logs, aggregate benchmark JSON and synthetic UI screenshots are
under ignored `build/`. The new storage report can attribute the friend's data without
clearing their account or exporting private rows.
