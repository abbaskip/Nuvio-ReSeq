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
| `LiveTvSettings`, `Settings` | Partial | M3U, Xtream live accounts, XMLTV, source management and Drive backup. Stalker, recordings and other settings remain. |
| `Calendar`, `CalendarGame` | Partial | Day, week, month, profile filters, followed-sport shortcuts, fixture details, channel tuning and release sheets. Full title navigation and extended fixture statistics remain. |
| `Sports` | Partial | My Sports, All Events and On TV Now, team follows and matched-channel tuning. Tables, extended game details and alerts remain. |
| `AdvancedDiscover`, `TmdbSettings` | Partial | Discover filters, saved searches, results, TMDB key entry and TV Mix creation. Additional filters and full TMDB settings remain. |
| `TV_MIX_ROUTE` | Partial | Add shows, create mixes, select unwatched episodes and preview the next pick. Source selection and playback remain. |
| `LicensesAttributions` | Partial | Mobile credits and upstream source in Setup. Full attribution browser remains. |
| `Home`, `Library`, `Search`, `Discover`, `FlickPick` | Pending | Catalogue and personal library navigation. |
| `Detail`, `Stream`, `Player`, `PlaybackSettings` | Pending | Title detail, TVDB ordering, source selection, playback and ratings. |
| `CalendarTeams`, `Multiview` | Pending | Team management and multiple live streams. |
| `OnDemand`, `OnDemandSearch` | Pending | Provider movie and series browsing. |
| `AddonManager`, `Plugins`, `CatalogOrder`, `CatalogSeeAll` | Pending | Extension and catalogue management. |
| `Collections`, `CollectionEditor`, `FolderDetail`, `CastDetail`, `TmdbEntityBrowse` | Pending | Collection, folder, cast and entity navigation. |
| `Account`, `ManageProfiles`, `ProfileSelection`, `AuthSignIn`, `AuthQrSignIn`, `SyncCodeGenerate`, `SyncCodeClaim`, `Tracking`, `WatchParty` | Pending | Account flows, profile selection, tracking controls and shared viewing. |
| `About`, `SupportersContributors`, `ThemeSettings` | Pending | Information and theme selection. The mobile theme reads the active profile palette. |
| `LayoutSettings`, `LayoutSelection`, `ExperienceModeSelection` | TV only | Existing TV shell and remote layout choices. |

**Embedded features.** TV Mix supports adding Discover series to existing or new mixes, unwatched-only selection and episode previews. VOD playback and continuous remixing remain pending. TVDB tags and order selection, ratings, Usenet configuration and playback, and tracking actions need mobile controls. Drive supports connection, automatic sync, backup, restore and disconnect. A credentialed build and account are required to exercise the Drive round trip.

**Reference.** The mobile layout adaptations and their source revision are listed in [NOTICE-MOBILE.md](../NOTICE-MOBILE.md).
