# Release documentation

Start each future release's reviewed notes from [.github/RELEASE_TEMPLATE.md](../.github/RELEASE_TEMPLATE.md). Keep its short copyright/content-source section and upstream credits, and recheck the default-service statement against the version being released. Describe Stremio-compatible addons, TiviMate-style guide functionality, Xtream, M3U, Stalker, Sports Hub, IPTV On Demand and tracking integrations as capabilities, not supplied subscriptions, broadcast access or official partnerships.

The template is manual: GitHub does not automatically apply this file. Save completed notes as `releases/<version>.md`. The existing `reseq-release.yml` workflow accepts that path through its `notes` input and appends download guidance. The local `scripts/release_beta.py` path accepts it via `--custom-notes-file`; include the disclaimer in that file because custom notes replace generated prose. Commit-based note generators do not automatically add the disclaimer. Review their output against the template before publication. This review changes no release automation and publishes no release.

## Historical descriptions reviewed on 2 November 2026

Reviewed all eight descriptions returned by the repository's releases API, including the withdrawn Beta 3 draft. No historical release body, asset, tag or checked-in historical notes were edited. The following are recommendations only; any historical edit needs maintainer approval. Preserve each release's original feature scope and describe defaults at its own tag rather than retrospectively claiming newer capabilities.

| Release | Finding and recommended change if approved |
| --- | --- |
| [Beta 1](https://github.com/abbaskip/Nuvio-ReSeq/releases/tag/v0.1.0-beta.1) | Already identifies an independent fork, credits Nuvio and says it does not provide media. Add explicit user copyright/licensing responsibility, default metadata/subtitle endpoints and service non-affiliation. |
| [Beta 2](https://github.com/abbaskip/Nuvio-ReSeq/releases/tag/v0.1.0-beta.2) | Maintenance description lacks a content-source notice and standalone upstream credit. Add the short notice and Nuvio credit appropriate to this version. Do not claim later IPTV incorporation. |
| Beta 3 (withdrawn, currently a draft) | Credits incorporated Nuvio + IPTV code and denies upstream endorsement. Add the short source-responsibility notice if the draft is ever reused. “Australian Seven/7mate coverage” should say “matching against users' configured channels/EPG”; it does not mean broadcasts are supplied or licensed. Do not republish the withdrawn release as part of a documentation correction. |
| [Beta 3.1](https://github.com/abbaskip/Nuvio-ReSeq/releases/tag/v0.1.0-beta.3.1) | Preserves the same useful attribution and the same potentially ambiguous “Seven/7mate coverage” wording. Clarify it as matching and add the short notice. |
| [Beta 3.2](https://github.com/abbaskip/Nuvio-ReSeq/releases/tag/v0.1.0-beta.3.2) | Already says no media, subscriptions or playlists are supplied, credits both upstreams, and warns channel suggestions do not prove event availability. Add user licensing responsibility, default endpoints and service non-affiliation. |
| [Beta 4](https://github.com/abbaskip/Nuvio-ReSeq/releases/tag/v0.1.0-beta.4) | Good provider-feature qualifications and Nuvio, Nuvio + IPTV and IPTVnator credit. Add the short content-source notice; retain Stalker and On Demand descriptions. |
| [Beta 5](https://github.com/abbaskip/Nuvio-ReSeq/releases/tag/v0.1.0-beta.5) | Mentions provider dependencies and GPLv3, but lacks the consistent notice. Add it and name/link both upstream projects explicitly. Device-test references to a channel describe validation, not bundled access. |
| [Beta 5.1](https://github.com/abbaskip/Nuvio-ReSeq/releases/tag/v0.1.0-beta.5.1) | Focused fixes/testing/download guidance omit source responsibility and upstream credit. Add the short notice and credits appropriate to this tag. |

## Evidence and follow-up

Documentation/source baseline: `dev` commit `27cc17b5f`, reviewed on 2 November 2026.

- `app/src/main/java/com/nuvio/tv/data/local/AddonPreferences.kt`, `getDefaultAddons`: predefined Cinemeta and OpenSubtitles v3 endpoints. A blanket “no addons included” statement would be inaccurate.
- `livetv/data/LiveTvPreferences.kt`, playlist/EPG decoding: missing configurations resolve to empty lists. Users add Xtream, M3U/XMLTV or Stalker connections. Paths in this and the next item are relative to `app/src/main/java/com/nuvio/tv/`.
- `livetv/sports/SportsFeeds.kt`, `FreeSportsDb.kt` and the shared feed implementation: predefined sporting metadata services; `data/trailer/TrailerService.kt` and `InAppYouTubeExtractor.kt`: external trailer playback. Metadata, artwork, subtitles and trailers remain third-party material even though full movie/series/live-channel sources are user-configured.
- README, CONTRIBUTING, issue/PR templates, public homepage/privacy/terms and release tooling were reviewed. The repository description already says “Independent Nuvio Android TV fork”; no affiliation claim was found there. The public site's editable text lives in `tools/build-public-site.py`; its generated HTML must be committed together with text changes.
- Existing LICENSE, NOTICE-IPTV.md, NOTICE-IPTVNATOR.md, FFmpeg notices and in-app licence files are preserved. Original Nuvio and incorporated Nuvio + IPTV attribution remains prominent. No additional software-licence restriction is created by the source-responsibility notice.

Manual/professional follow-up: confirm permissions and applicable terms for redistribution of sports metadata, logos/artwork, subtitles and any trailer integration; public accessibility alone does not establish redistribution permission. Verify corresponding source, build instructions and required notices for bundled native/AAR dependencies against each distributed APK. Existing notices and credits are not a complete dependency/licence audit. Review reused service/OAuth registrations and branding permissions with their operators, and obtain jurisdiction-specific legal review of the website's existing warranty/liability terms and any concrete rights complaint. This documentation review makes no determination of legal immunity or comprehensive compliance.

The public [rights reporting route](RIGHTS.md) uses GitHub Issues without publishing private maintainer information. A dedicated, monitored project mailbox or private intake could be added later if the maintainer establishes one; none is invented or advertised as available here. GitHub's own copyright reporting route is separate.

The rights form uses the existing `documentation` label so the scheduled unlabeled-issue closure does not close it automatically. Existing bug-form triage applies only to bug-style reports. Maintainers should monitor this route and avoid applying bug-report reproduction requirements to rights concerns.
