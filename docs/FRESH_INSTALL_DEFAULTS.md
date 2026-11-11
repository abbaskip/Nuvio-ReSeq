# Fresh installation defaults and default-metadata Trakt adaptation

Installed Stremio add-ons and IPTV are enabled for the approved autoplay tier at 8 seconds. Plugins are off in that tier but can be selected by the any-source fallback at 25 seconds, as confirmed by the maintainer. Home trailers use the hero media surface after 2 seconds with mute disabled. Focused-poster expansion and trailer autoplay are enabled; fullscreen hero backdrop is disabled. Shuffle is enabled and episode ratings are shown by default. Details trailers autoplay after 5 seconds in fullscreen. All controls remain editable.

The preference factory records a fresh-install policy using Android installation/update timestamps, then seeds only new, empty feature stores through DataStore migrations. Existing installations, existing files, restored preferences and subsequent user edits retain their values. Legacy fallback values are unchanged. The marker carries no credentials.

Trakt same-number reads and writes apply only when no TVDB order is selected for the series. Mapping cache keys include the active profile and selected order. Order changes cannot reuse mappings from default mode. Unique title matches remain authoritative before the write-side same-number fallback. Alternate-order mapping retains the existing policy; canonical references retain the existing bypass. Tests cover missing episodes, title precedence, order changes and TVDB metadata boundaries.

Raw upstream commits remain held as broad replacements. The ledger records the validated default-metadata adaptation separately and preserves original review history. The maintainer declined forced completion and chose to retain existing Continue Watching and subtitle defaults.

Validation: 3336 JVM tests passed, two existing live-service skips. All five debug APKs built and Playstore Kotlin compiled. A test-fixture context error was corrected. An unchanged full-suite retry passed after Windows aborted one existing localhost data-source test connection. The failed connection result is retained in build/validation/loopback-abort-attempt.xml. No physical device or live-account testing occurred.
