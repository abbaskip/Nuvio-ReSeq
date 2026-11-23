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
| `LiveTv` | Partial | Guide with group selection, synchronized time scrolling, visible-channel EPG loading and programme details. Search, locked-group PIN entry, catch-up and editing remain. |
| `LiveTvPlayer` | Partial | Shared player surface, tap controls, swipe channel switching, channel list and in-player mini-guide. Tracks and advanced controls remain. |
| `LiveTvSettings`, `Settings` | Partial | Settings opens account, profiles, sources, extensions, playback, tracking, Drive and IPTV. Stalker and recording setup remain. |
| `Calendar`, `CalendarGame` | Partial | Day, week, month, profile filters, followed-sport shortcuts, fixture details, channel tuning and release sheets. Full title navigation and extended fixture statistics remain. |
| `Sports` | Partial | My Sports, All Events and On TV Now, team follows and matched-channel tuning. Tables, extended game details and alerts remain. |
| `AdvancedDiscover`, `TmdbSettings` | Partial | Discover filters, saved searches, results, TMDB key entry and TV Mix creation. Additional filters and full TMDB settings remain. |
| `TV_MIX_ROUTE` | Partial | Add shows, create mixes, pick unwatched episodes and play a selection. Continuous remixing remains. |
| `LicensesAttributions` | Partial | Mobile credits and upstream source in Setup. Full attribution browser remains. |
| `Home`, `Detail`, `Stream`, `Player` | Partial | Featured titles, catalogue rows, Continue Watching, episode selection, TVDB order picker, ratings, long-press download, manual source selection, local playback, seek, audio and subtitles. Trailers, cast and extended player tools remain. |
| `Library`, `Search`, `Discover`, `PlaybackSettings` | Partial | Search installed catalogues, browse Discover, save titles, filter and sort the library, choose an engine and configure automatic source selection. Cloud files and extended settings remain. |
| `FlickPick` | Pending | Random title discovery. |
| `CalendarTeams`, `Multiview` | Pending | Team management and multiple live streams. |
| `OnDemand`, `OnDemandSearch` | Pending | Provider movie and series browsing. |
| `AddonManager` | Partial | Home can install a catalogue or stream source by manifest URL. Full source management remains. |
| `Plugins` | Partial | Install and refresh repositories and enable individual scrapers. |
| `CatalogOrder`, `CatalogSeeAll` | Pending | Catalogue ordering and dedicated full-catalogue pages. |
| `Collections`, `CollectionEditor`, `FolderDetail`, `CastDetail`, `TmdbEntityBrowse` | Pending | Collection, folder, cast and entity navigation. |
| `Account`, `ManageProfiles`, `ProfileSelection`, `AuthSignIn`, `AuthQrSignIn`, `SyncCodeGenerate`, `SyncCodeClaim`, `Tracking` | Partial | Browser sign-in, custom-server email sign-in, link codes, profile creation and PIN-protected switching, Trakt and Simkl device sign-in. |
| `WatchParty` | Pending | Shared viewing. |
| `ThemeSettings` | Partial | Appearance selects the active profile’s ReSeq colour palette. Custom palette editing remains. |
| `About`, `SupportersContributors` | Pending | Information and contributors. |
| `LayoutSettings`, `LayoutSelection`, `ExperienceModeSelection` | TV only | Existing TV shell and remote layout choices. |

**Offline video.** Long-press a Home poster, the title on Details, or an episode and choose Download. Play manually lets you choose a particular source and select Download this file. Home lists completed videos under Downloads, including after an offline restart. The player looks up a completed file for the active profile and exact movie or episode before asking providers for sources. Android stores the original video container and a completion record in the app-specific Movies/ReSeq folder. Interrupted copies are excluded. Select Manage to remove a saved file. Downloads support progressive video files, including HTTP file links resolved by ReSeq. Adaptive playlists and raw torrent sources require a downloadable file alternative.

**Google Drive.** In Settings, open Google Drive backup and restore and connect the same Google account as the TV. Connecting or reconnecting makes no backup and leaves automatic sync off on this device. Choose Restore to load your setup before making a backup. The apps share the Drive OAuth client and backup format. The standard backup includes IPTV logins, playlists, guide settings, Sports preferences, TVDB keys and orders, mixes and shuffle choices. Profile-specific settings require matching profile IDs and names. Google sign-in is per device.

**Provider keys.** Back up keys saves the active profile’s TMDB, Gemini, debrid, MDBList and AnimeSkip keys in a separate passphrase-encrypted Drive file. On another device choose Restore keys and enter the same passphrase. Automatic sync never transfers this file. Both builds need the provider-key controls. TV RC 3.1 can supply the standard backup, including IPTV and TVDB, and retains its previous sign-in behaviour.

**Embedded features.** TV Mix supports adding Discover series to existing or new mixes, unwatched-only selection and episode previews. TV Mix continuous playback and extended player controls remain pending.

**Reference.** The mobile layout adaptations and their source revision are listed in [NOTICE-MOBILE.md](../NOTICE-MOBILE.md).
