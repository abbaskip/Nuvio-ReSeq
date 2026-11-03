# TV Mix

Implemented on `codex/tv-mix`, based on ReSeq beta 6.3 (`7fd96a2fb`), in `.worktrees/tv-mix`.

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

## Validation

Latest local run: Kotlin build and Android TV test compilation succeeded; 247 unit tests passed,
one existing test was skipped, and `git diff --check` passed.

Run with JDK 21 and the Android SDK configured:

```powershell
./gradlew.bat :app:testFullDebugUnitTest --tests '*TvMix*' --tests '*EpisodeShuffle*' --tests '*Tvdb*' --tests '*NaturalPlaybackCompletionRulesTest' --tests '*PlayerScrobblePolicyTest' --tests '*DriveBackupPayloadTest' :app:compileFullDebugAndroidTestKotlin --offline --console=plain
```

The suite covers persistence/recreation, profile isolation, backup/restore and conflicts,
equal show probability, lazy selection, release/availability filtering, repeat avoidance,
autoplay on/off, Remix, manual exit, stale external results, Home row placement and stable
focus keys, ordinary-next-episode suppression, and TVDB source/tracker identity handoff.
`TvMixHomeTvTest` additionally checks card focus, navigation, click, and long-press semantics;
it is compiled but requires an Android device/emulator to execute.

Device acceptance still needs D-pad traversal and focus restoration across all Home layouts,
text entry and management dialogs, real stream selection/failure recovery, internal and
external player natural completion with autoplay on/off, and live tracker/Continue Watching
updates. No APK was installed on the Onn box as part of this implementation. Before any Onn
installation, validate the actual APK's backend URL/API key and absence of personal TMDB
credentials, then verify Nuvio sign-in produces a pairing/QR code.
