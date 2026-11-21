# NuvioTV 1.2.0-beta.3 in ReSeq

This integration starts from ReSeq 1.0.0-rc.2 (`019d636b3`) and compares NuvioTV 1.2.0-beta.2 with 1.2.0-beta.3 (`552cacf56`). Individual upstream commits retain their original references in Git. [Upstream release notes](https://github.com/NuvioMedia/NuvioTV/releases/tag/1.2.0-beta.3)

## Trailers and Home

Enable **Continue Trailer in Details** in the focused-poster autoplay settings. A trailer that is already playing can carry its player, position and video into Details. The handoff expires if the destination never claims it. Opening a cast or network page pauses the trailer. ReSeq keeps its separate IPTV On Demand preview pool.

The beta also adds trailer captions, a collapsed-poster hero option, season posters in the season tabs and longer reading time for scrolling descriptions. Home keeps remote focus in the open sidebar while loading and on a Continue Watching card that moves after playback. Classic layouts and the profile editor pick up the RTL fixes.

## Media servers

| Provider | ReSeq RC2 | Integrated behavior |
| --- | --- | --- |
| Jellyfin | Native provider | Existing libraries and playback, with a separate sign-in device identity per ReSeq profile |
| Emby | Native provider | Same profile sign-in fix as Jellyfin |
| Plex | Absent from the native provider list | Sign in at plex.tv/link and select a server |
| Silo | Absent from the native provider list | Enter the server address and use its code or password flow |

Plex and Silo use upstream's provider implementations for search, external-ID matching, playback, transcoding and progress reporting. All four are under **Settings → Media Servers**. [Provider implementations](../app/src/main/java/com/nuvio/tv/data/mediaserver)

## Collections, tracking and playback

- Catalog and collection rows support TV-remote drag reordering, including pinned collections. ReSeq keeps TV Mix rows and IPTV-generated sources in their respective storage paths.
- Ratings retain ReSeq's multi-service choice and add explicit Simkl or MDBList selection. MDBList can also rate an episode when ReSeq has an unambiguous episode identity.
- Subtitle menus show the add-on's label. ReSeq already cached language resolution. Audio-based subtitle timing is optional. The default playback path bypasses the audio tap.
- Catalog images use bounded request sizes. Hardware bitmaps remain opt-in on ReSeq. Player changes include low-memory Dolby Vision profile 7 back-buffer handling and a single HTTP/2 retry for eligible 403 responses.
- Other changes cover profile sync for ambient settings, movie/series filters and talk-show guest appearances in filmography, sorting upcoming episodes by release time, plugin repository deletion confirmation and updated translations.

## Cloudstream comparison

Beta.3 carries the same Cloudstream loader, runner and extractor registry as beta.2. Both ReSeq and upstream consume `.cs3` providers. The extractor registry files match at these revisions. The integration adopts two checks from upstream's compatibility tests. They verify that compiled plugins can call the Kotlin default-argument toast method and can safely check the type of the host activity. The beta's confirmation before removing a plugin repository is included. [Upstream runner](https://github.com/NuvioMedia/NuvioTV/blob/1.2.0-beta.3/app/src/full/java/com/nuvio/tv/core/plugin/cloudstream/ExternalExtensionRunner.kt)

| Area | ReSeq behavior worth retaining |
| --- | --- |
| Updates | Stages and validates the replacement, including its published SHA-256 when present, before replacing the installed extension. A failed update leaves the installed copy available. |
| Provider execution | Normal playback and diagnostics share the loader and runner. Multi-provider searches use up to three concurrent providers and a time budget. |
| Repository compatibility | Handles relative repository links and duplicate provider entries. ReSeq also exposes DNS selection and extension settings for TV. |

Upstream's diagnostic runner can report the class that implements search, HTTP status codes and missing extractor domains. Its network trace temporarily replaces the shared HTTP client and records full request URLs. A future port could collect those details for just the extension under test and strip query parameters and credentials. The shared provider contract and its ABI tests are the best places to compare on subsequent upstream releases. [ReSeq loader](../app/src/full/java/com/nuvio/tv/core/plugin/cloudstream/ExternalExtensionLoader.kt) · [ReSeq runner](../app/src/full/java/com/nuvio/tv/core/plugin/cloudstream/ExternalExtensionRunner.kt) · [Package validation](../app/src/full/java/com/nuvio/tv/core/plugin/cloudstream/ExtensionPackage.kt)

## Verification on Android TV

The full debug JVM suite passed 4,424 tests and skipped two opt-in tests. The 60 Plex and Silo cases used fixture servers. On an Android 14 x86 TV emulator, 25 instrumentation tests exercised both trailer handoff paths, collection and settings focus, the four server entries, the Silo address form and Cloudstream preferences. Each handoff loaded a single media item and advanced its playhead after navigation. The downloaded English speech model matched its pinned hashes on the device, and a separate test ran it through the native recognizer.

The signed-out startup comparison used eight process-cold launches per build, in alternating blocks on the same emulator. Median time to the first activity frame was 5.14 seconds for RC2 and 4.04 seconds for the integration. A clean ARM64 debug APK grew from 100.77 MiB to 112.67 MiB. The optional speech model is a separate download. Live media-server accounts and physical-device Dolby Vision playback still require testing.
