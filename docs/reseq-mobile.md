# ReSeq Mobile

Select the **mobileDebug** build variant in Android Studio or run `./gradlew :app:assembleMobileDebug`. The phone and tablet launcher installs as `io.github.abbaskip.nuvioreseq.mobile`. The existing TV targets are `fullDebug` and `fullRelease`. The two installs have independent Android storage. Drive backup uses the same ReSeq backup format.

## Shared engine and touch layouts

Both apps compile the same Kotlin engine, Hilt graph, ViewModels, repositories, Media3 and MPV players, Usenet and extension runtime. The `mobile` source set supplies the phone and tablet launcher, touch navigation, gesture controls and adaptive screen layouts. Mobile preserves the selected TVDB episode identity through details, source selection, playback, downloads and tracking.

When adding a shared feature, expose its actions from the existing ViewModel and add the touch entry point under `app/src/mobile`. Record the destination below. CI checks the inventory, separate package and launcher contracts, TV unit suite and mobile emulator journeys. To exercise an assembled mobile debug build on a disposable emulator, use `python scripts/mobile_smoke.py --extended --serial emulator-5554`.

## Feature coverage

The table tracks every named TV navigation destination. Run `python scripts/mobile_parity.py --missing-screens` to list supported destinations awaiting a touch entry point, or `--gaps` to include unfinished work and verification on existing screens. A new unclassified TV destination fails CI. Pass `--merged --tv` after assembling the debug variants to check both packaged launchers.

| TV destinations | Mobile status | Touch path or outstanding work |
| --- | --- | --- |
| `LiveTv` | Partial | Touch timeline, search, programme sheets, channel and group editing, PINs, catch-up, reminders and recordings. Live tuning and channel switching have emulator coverage. Timed reminders and provider replays need qualification. |
| `LiveTvPlayer` | Partial | Tap controls, swipe channels, browse the channel panel or mini-guide, change audio and captions, seek replays and reconnect. Device playback qualification continues. |
| `LiveTvSettings`, `Settings` | Partial | M3U, Xtream, Stalker, XMLTV, Drive, guide retention, recording storage and timing, parental controls and category visibility. The touch tests import M3U, XMLTV, Xtream and Stalker fixtures. Cross-device restore with real accounts remains a separate check. |
| `Calendar`, `CalendarGame` | Partial | Day, week and month, filters, sport shortcuts, fixture channels, linked movie and series details and team management. Cross-screen verification is in progress. |
| `Sports` | Partial | My Sports, All Events, On TV Now, teams, standings, alerts, fixture statistics, replay, recording, manual channel matches and optional Gemini lookup. The touch test covers alerts settings. Score delivery and real broadcaster matching need device verification. |
| `AdvancedDiscover`, `TmdbSettings` | Partial | Includes and excludes, text matching, language, certification, date, runtime, votes, sorting, saved-search updates, random matches and TMDB metadata preferences. Device verification is in progress. |
| `TV_MIX_ROUTE` | Partial | Create and manage mixes, change unwatched-only selection, choose episodes, continue or automatically advance through the shared session. Multi-episode device verification is in progress. |
| `LicensesAttributions` | Partial | A touch attribution browser displays source links, GPL attribution and the shared playback, data and UI notices. Device verification is in progress. |
| `Home`, `Detail`, `Stream`, `Player` | Partial | Home highlights, Continue Watching, folders, downloads, manual sources, TVDB identity, personal ratings, cast, related titles and collections. The touch test exercises episode playback, speed and subtitle controls, both download paths and offline restart. Trailers, external players, recommendations and tracker rating submission need separate verification. |
| `Library`, `Search`, `Discover`, `PlaybackSettings` | Partial | Library filters, search history and suggestions, Discover, cloud files, media server sign-in and shelves. Playback settings include audio, subtitles, codecs, caching, source fallback, next episode and post-play. External server integration needs provider qualification. |
| `FlickPick` | Partial | Search opens the mobile picker for library and discovery recommendations, filtering and playback. Device verification is in progress. |
| `CalendarTeams`, `Multiview` | Partial | Teams are reachable from Calendar and Sports. Multiview has touch panes, focus, fullscreen and channel selection. Multi-decoder hardware qualification remains. |
| `OnDemand`, `OnDemandSearch` | Partial | Library opens provider movies and series with categories, parental PINs, search, season and version selection. The provider test checks Stalker film import, Xtream film details and playback of a selected Xtream episode. Category and version-selection checks remain. |
| `AddonManager` | Partial | Install, sync, enable, disable, reorder and remove sources using the shared manager. Protected profile and device verification is in progress. |
| `Plugins` | Partial | Settings → Extensions opens repositories, source languages, grouping, DNS, per-extension settings and test diagnostics. External extension integration needs device verification. |
| `CatalogOrder`, `CatalogSeeAll` | Partial | See all pages, source ordering and individual catalogue visibility. Device pagination and ordering verification are in progress. |
| `Collections`, `CollectionEditor`, `FolderDetail`, `CastDetail`, `TmdbEntityBrowse` | Partial | Collection management, import and export, filters, cast filmographies and company or network catalogs. The touch test creates a collection and folder, adds a catalogue, saves it and opens a real metadata result. Trakt and TMDB builders need provider verification. |
| `Account`, `ManageProfiles`, `ProfileSelection`, `AuthSignIn`, `AuthQrSignIn`, `SyncCodeGenerate`, `SyncCodeClaim`, `Tracking` | Partial | The emulator verifies Nuvio browser approval, interrupted polling, activity recreation and automatic catalogue sync against a local account fixture. Tracking exposes Trakt, Simkl and MDBList sign-in, library selection and ratings. Multi-account device verification remains. |
| `WatchParty` | Partial | Join or host, manage members, open the shared title and play a shared source. Two-device playback and reconnection verification remain. |
| `ThemeSettings` | Partial | Profile palettes, AMOLED and a touch solid or gradient editor. Device verification is in progress. |
| `About`, `SupportersContributors` | Partial | Version, source and issues, supporters, sponsors, contributors and licenses. Device verification is in progress. |
| `LayoutSettings` | Partial | Settings → Home and title details controls highlights and their sources, catalogue labels, Continue watching and upcoming episodes, trailers, metadata and shuffle. |
| `LayoutSelection`, `ExperienceModeSelection` | TV only | TV shell and remote layout choices. |

**Offline video.** Long-press a Home poster, the title on Details, or an episode and choose Download. Play manually lets you choose a particular source and select Download this file. Home lists completed videos under Downloads, including after an offline restart. The player looks up a completed file for the active profile and exact movie or episode before asking providers for sources. Android stores the original video container and a completion record in the app-specific Movies/ReSeq folder. Interrupted copies are excluded. Select Manage to remove a saved file. Downloads support progressive video files, including HTTP file links resolved by ReSeq. Adaptive playlists and raw torrent sources require a downloadable file alternative.

**Google Drive.** In Settings, open Google Drive backup and restore and connect the same Google account as the TV. Connecting or reconnecting makes no backup and leaves automatic sync off on this device. Choose Restore to load your setup before making a backup. The apps share the Drive OAuth client and backup format. The standard backup includes IPTV logins, playlists, guide settings, Sports preferences, TVDB keys and orders, mixes and shuffle choices. Profile-specific settings require matching profile IDs and names. Google sign-in is per device.

**Provider keys.** Back up keys saves the active profile’s TMDB, Gemini, debrid, MDBList and AnimeSkip keys in a separate passphrase-encrypted Drive file. On another device choose Restore keys and enter the same passphrase. Automatic sync never transfers this file. Both builds need the provider-key controls. TV RC 3.1 can supply the standard backup, including IPTV and TVDB, and retains its previous sign-in behaviour.

**Tracking and account sync.** Open Settings → Tracking and TVDB → MDBList to connect an account, choose its library lists or configure ratings. Open Settings → Nuvio account to link the app. After you approve in the browser, return to ReSeq. It checks the existing code immediately and starts the account sync when signed in. Check approval repeats the status check if needed.

**Build availability.** Alpha 4 adds profile editing, post-play settings, home preferences, channel group management, sports reminders and tracker settings. [Alpha 4 downloads and release notes](https://github.com/abbaskip/Nuvio-ReSeq/releases/tag/mobile-v0.1.0-alpha.4)

**Reference.** The mobile layout adaptations and their source revision are listed in [NOTICE-MOBILE.md](../NOTICE-MOBILE.md).
