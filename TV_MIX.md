# TV Mix

Introduced in ReSeq beta 7, based on ReSeq beta 6.3 (`7fd96a2fb`).

Long-press a series in Home, Search, Discovery, or the Library and choose **Add to mix**.
Choose an existing mix or create a named one. Duplicate shows, including known IMDb aliases,
are ignored. The **TV Mix** row appears last on Home in Classic, Modern, and Grid layouts.
Its cards reuse the existing focus/navigation paths and Coil artwork cache.

Click a mix to preview its next random episode and included show covers. **Play** uses the
existing source-selection screen and player preference. **Remix** changes the preview without
starting playback. Long-press the Home card, or choose **Manage mix** on its splash screen, to
rename it, toggle **unwatched only**, remove shows, or delete the mix.

Each selection randomizes the shows first, then uses the existing episode shuffle picker for
the chosen show's eligible episodes. Only the selected show's metadata is needed normally;
unavailable shows are tried at most once per selection, with a 20-second timeout per provider
lookup. Streams are resolved only after Play. Watched episodes are included by default.
The previous episode is excluded whenever another eligible episode exists, including Remix.

Mix playback suppresses ordinary next-episode sequencing and post-play recommendations.
Natural completion returns to the mix: autoplay selects and launches the next episode;
autoplay off displays **Continue mix?** with **Yes**, **No**, and **Remix**. Back ends playback
without the continuation prompt. Failed metadata selection requires an explicit Remix/retry;
missing streams can be left with Back, followed by Remix on the splash screen. There is no
automatic stream retry loop.

Mixes are profile-scoped DataStore records. Drive backup/sync includes a versioned `reseq_mix`
section, with one record per mix so independent edits merge and conflicting edits stop sync.
Legacy backups without this section leave local mixes alone. All incoming mix records are
validated before restore starts changing preferences.

The original metadata `Video` and encoded `TvdbPlaybackContext` pass through the ordinary
Stream route. Mixes do not implement new episode numbering, stream requests, watch-progress
storage, Continue Watching, or tracker/scrobble mappings. Mix sessions are temporary and are
not restored after process death. External players continue mixes only when their result
identifies a natural completion; missing or ambiguous results end the session safely.

Native TVDB, media-server, and non-TMDB addon show IDs stay authoritative even when they have
an IMDb alias. The alias is retained for duplicate detection. TMDB IDs retain the existing
Discovery-compatible runtime IMDb lookup to find addon-compatible metadata.

## Validation

The combined release has a passing full Debug unit suite (3,008 tests, two existing skips),
with focused reruns for subsequent TV Mix fixes and release-mode checks in CI.
See the beta 7 release validation report for the exact source and final observed totals.

Run with JDK 21 and the Android SDK configured:

```powershell
./gradlew.bat :app:testFullDebugUnitTest --tests '*TvMix*' --tests '*EpisodeShuffle*' --tests '*Tvdb*' --tests '*NaturalPlaybackCompletionRulesTest' --tests '*PlayerScrobblePolicyTest' --tests '*DriveBackupPayloadTest' :app:compileFullDebugAndroidTestKotlin --offline --console=plain
```

The suite covers persistence/recreation, profile isolation, backup/restore and conflicts,
equal show probability, lazy selection, release/availability filtering, repeat avoidance,
autoplay on/off, Remix, manual exit, stale external results, Home row placement and stable
focus keys, ordinary-next-episode suppression, and TVDB source/tracker identity handoff.
`TvMixHomeTvTest` additionally checks card focus, navigation, click, long-press semantics,
and stable composite cards when poster expansion is enabled. Classic Home return-focus timing
is checked through real Onn navigation. The Android TV regression suite runs on the Onn box.

Onn acceptance uses a separate temporary Android user and synthetic local media. It covers
mix creation and management, missing artwork, persistence across upgrades, unwatched filtering,
source selection, natural completion with autoplay on/off, repeated Remix without playback,
Yes/No, unavailable streams, and manual exit near completion. Normal Continue Watching updates
use the existing playback path. Final signed-build acceptance and performance observations
are recorded with the release; external-player variants and live third-party tracker/cloud
accounts remain dependent on their integrations and require separate testing.

Before any Onn installation, validate the actual APK's backend URL/API key and absence of
personal TMDB credentials, then verify Nuvio sign-in produces a pairing/QR code.
