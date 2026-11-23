# ReSeq Mobile

Select the **mobileDebug** build variant in Android Studio or run `./gradlew :app:assembleMobileDebug`. The phone and tablet launcher installs as `io.github.abbaskip.nuvioreseq.mobile`. The existing TV targets are `fullDebug` and `fullRelease`. The two installs have independent Android storage. Drive backup uses the same ReSeq backup format.

## Architecture and development sequence

1. Build the phone and tablet shell, EPG, channel panel, Calendar, Sports Hub and setup against the current engine. The touch layouts live in `app/src/mobile`. Both variants compile the existing Kotlin sources, Hilt graph, player and extension runtime. The first increment also exposes discovery filters and TV Mix selection.
2. Port the mobile reference's Home, Search, Library, metadata, episode and stream screens. Wire the episode order picker, ratings and TV Mix playback into the existing identity, source-selection and tracking paths. Preserve the TVDB context through detail → sources → player → history.
3. Adapt the reference's on-demand player controls, horizontal seek and vertical volume/brightness gestures. Exercise Media3, MPV, Usenet retries, subtitle selection, resume and scrobbling on phones and tablets.
4. Finish IPTV source types, catch-up, recordings and Multiview, then Sports tables, Calendar detail navigation and remaining settings. Qualify mobile release signing, updates and service credentials before distribution.

Use the shared ViewModels for new actions. UI code in `src/main` still contains TV screens and some presentation-independent models. The initial mobile APK compiles those sources to keep internal engine types accessible. As boundaries become clearer, move shared models out of screen files with behavior-preserving changes. Mobile owns its launcher and touch controls. TV launcher publishing, Ambient mode and AFR have no mobile entry points.

## Feature coverage

The table tracks every named TV navigation destination. The rows following it cover features embedded in screens. Update both when adding an entry point. `python scripts/mobile_parity.py` flags unclassified or removed named destinations. Pass `--merged` after a mobile debug build to check the packaged launcher inputs and provider authorities.

| TV destinations | Mobile status | Touch path or outstanding work |
| --- | --- | --- |
| `LiveTv` | Partial | Guide, channel search, profile PIN unlock, catch-up, reminders, recordings and long-press channel editing. Group management is in progress. |
| `LiveTvPlayer` | Partial | Tap controls, swipe channels, browse the channel panel or mini-guide, change audio and captions, seek replays and reconnect. Device playback qualification continues. |
| `LiveTvSettings`, `Settings` | Partial | M3U, Xtream, Stalker, XMLTV, Drive, guide retention, recording storage and timing. Per-provider VOD category selection and more playback settings remain. |
| `Calendar`, `CalendarGame` | Partial | Day, week and month, filters, sport shortcuts, fixture channels, linked movie and series details and team management. Cross-screen verification is in progress. |
| `Sports` | Partial | My Sports, All Events, On TV Now, teams, standings, alerts, fixture statistics, related channels and recording. Detailed channel matching overrides remain. |
| `AdvancedDiscover`, `TmdbSettings` | Partial | Includes and excludes, text matching, language, certification, date, runtime, votes, sorting, saved-search updates, random matches and TMDB metadata preferences. Device verification is in progress. |
| `TV_MIX_ROUTE` | Partial | Create and manage mixes, change unwatched-only selection, choose episodes, continue or automatically advance through the shared session. Multi-episode device verification is in progress. |
| `LicensesAttributions` | Partial | A touch attribution browser displays source links, GPL attribution and the shared playback, data and UI notices. Device verification is in progress. |
| `Home`, `Detail`, `Stream`, `Player` | Partial | Home highlights and rows, Continue Watching, folders, offline-first play, long-press downloads, manual sources, TVDB identity, ratings, cast, related titles and collections. Player gestures, chapters, episode switching, subtitle controls and external player support are in development. Trailer and post-play validation remain. |
| `Library`, `Search`, `Discover`, `PlaybackSettings` | Partial | Library filters, catalog search, Discover and cloud files. Media server sign-in, library shelves and expanded player settings are in development. |
| `FlickPick` | Partial | Search opens the mobile picker for library and discovery recommendations, filtering and playback. Device verification is in progress. |
| `CalendarTeams`, `Multiview` | Partial | Teams are reachable from Calendar and Sports. Multiview has touch panes, focus, fullscreen and channel selection. Multi-decoder hardware qualification remains. |
| `OnDemand`, `OnDemandSearch` | Partial | Library opens provider movies and series with categories, parental PINs, search, season and version selection, and recording playback. Provider device qualification remains. |
| `AddonManager` | Partial | Install, sync, enable, disable, reorder and remove sources using the shared manager. Protected profile and device verification is in progress. |
| `Plugins` | Partial | Install and refresh repositories and enable individual scrapers. |
| `CatalogOrder`, `CatalogSeeAll` | Partial | See all pages, source ordering and individual catalogue visibility. Device pagination and ordering verification are in progress. |
| `Collections`, `CollectionEditor`, `FolderDetail`, `CastDetail`, `TmdbEntityBrowse` | Partial | Collection management, import and export, editing, folder browsing, cast filmographies and company or network catalogs. Collection builder and device verification are in progress. |
| `Account`, `ManageProfiles`, `ProfileSelection`, `AuthSignIn`, `AuthQrSignIn`, `SyncCodeGenerate`, `SyncCodeClaim`, `Tracking` | Partial | Browser sign-in, custom-server email sign-in, link codes, profile creation and PIN-protected switching, Trakt and Simkl device sign-in. |
| `WatchParty` | Partial | Join or host, manage members, open the shared title and play a shared source. Two-device playback and reconnection verification remain. |
| `ThemeSettings` | Partial | Profile palettes, AMOLED and a touch solid or gradient editor. Device verification is in progress. |
| `About`, `SupportersContributors` | Partial | Version, source and issues, supporters, sponsors, contributors and licenses. Device verification is in progress. |
| `LayoutSettings`, `LayoutSelection`, `ExperienceModeSelection` | TV only | Existing TV shell and remote layout choices. |

**Offline video.** Long-press a Home poster, the title on Details, or an episode and choose Download. Play manually lets you choose a particular source and select Download this file. Home lists completed videos under Downloads, including after an offline restart. The player looks up a completed file for the active profile and exact movie or episode before asking providers for sources. Android stores the original video container and a completion record in the app-specific Movies/ReSeq folder. Interrupted copies are excluded. Select Manage to remove a saved file. Downloads support progressive video files, including HTTP file links resolved by ReSeq. Adaptive playlists and raw torrent sources require a downloadable file alternative.

**Google Drive.** In Settings, open Google Drive backup and restore and connect the same Google account as the TV. Connecting or reconnecting makes no backup and leaves automatic sync off on this device. Choose Restore to load your setup before making a backup. The apps share the Drive OAuth client and backup format. The standard backup includes IPTV logins, playlists, guide settings, Sports preferences, TVDB keys and orders, mixes and shuffle choices. Profile-specific settings require matching profile IDs and names. Google sign-in is per device.

**Provider keys.** Back up keys saves the active profile’s TMDB, Gemini, debrid, MDBList and AnimeSkip keys in a separate passphrase-encrypted Drive file. On another device choose Restore keys and enter the same passphrase. Automatic sync never transfers this file. Both builds need the provider-key controls. TV RC 3.1 can supply the standard backup, including IPTV and TVDB, and retains its previous sign-in behaviour.

**Build availability.** This ledger describes the mobile development branch. The signed Alpha 2 release predates the collections, expanded player, Sports tables, media server and Multiview work. Each GitHub release lists its included changes.

**Reference.** The mobile layout adaptations and their source revision are listed in [NOTICE-MOBILE.md](../NOTICE-MOBILE.md).
