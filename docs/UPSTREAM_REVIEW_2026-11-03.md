# Upstream review — 3 November 2026

Reviewed ReSeq `dev` at `e5d7db95ecaf86084572b51470a8f34d125adc04`
(beta 5.3) against NuvioMedia/NuvioTV `dev` at
`f362dfeea1d241a7d57bfb690c4bc24789839e40`. Upstream's default branch
is `dev`. The merge completed without conflicts.

## New merged upstream PRs

| PR | Change |
| --- | --- |
| [#4389](https://github.com/NuvioMedia/NuvioTV/pull/4389) | Preserve the selected playback engine when restarting the same stream. |
| [#4400](https://github.com/NuvioMedia/NuvioTV/pull/4400) | Prepare debrid links away from the main thread. |
| [#4402](https://github.com/NuvioMedia/NuvioTV/pull/4402) | Restore focus on Addons and Plugins configuration screens. |
| [#4366](https://github.com/NuvioMedia/NuvioTV/pull/4366) | Add optional audio night mode, off by default. |
| [#4367](https://github.com/NuvioMedia/NuvioTV/pull/4367) | Add optional LFE contribution to FFmpeg downmix, off by default; updated prebuilt decoder accompanies its Java/native sources. |
| [#4427](https://github.com/NuvioMedia/NuvioTV/pull/4427) | Detect HDR from decoder input and HLG signalled by HEVC alternative transfer SEI. |
| [#4436](https://github.com/NuvioMedia/NuvioTV/pull/4436) | Map Cantonese subtitle language aliases into Chinese. |
| [#4382](https://github.com/NuvioMedia/NuvioTV/pull/4382), [#4417](https://github.com/NuvioMedia/NuvioTV/pull/4417), [#4433](https://github.com/NuvioMedia/NuvioTV/pull/4433) | Vietnamese translations. |
| [#4386](https://github.com/NuvioMedia/NuvioTV/pull/4386), [#4387](https://github.com/NuvioMedia/NuvioTV/pull/4387) | Greek and Spanish translations. |
| [#4414](https://github.com/NuvioMedia/NuvioTV/pull/4414), [#4430](https://github.com/NuvioMedia/NuvioTV/pull/4430) | French audio/HDR translations. |
| [#4327](https://github.com/NuvioMedia/NuvioTV/pull/4327), [#4422](https://github.com/NuvioMedia/NuvioTV/pull/4422) | Hebrew buffer formatting and player translations. |

Only merged upstream changes are included. Open proposals such as Home performance
[#4372](https://github.com/NuvioMedia/NuvioTV/pull/4372), LAN config server guards
[#4428](https://github.com/NuvioMedia/NuvioTV/pull/4428), and episode selection delay
[#4406](https://github.com/NuvioMedia/NuvioTV/pull/4406) remain outside this sync.
#4372 reported merge conflicts at review time and concerns Home, rather than the
custom ReSeq Live TV guide.

## Regression validation

- Initial unfiltered Full Debug unit run: 2,868 tests, 2,865 passed, two skipped,
  one failed. The failure was `TraktAuthServiceTest.refresh token 400 clears
  credentials and prevents another refresh`.
- Ran that test separately on unchanged beta 5.3 and reproduced the identical
  `refreshToken ... was not called` failure. Local builds omit `TRAKT_CLIENT_ID`;
  production code returns before the API request when that field is empty.
  Neither the test nor the service is changed by this merge.
- Repeated the suite excluding only that confirmed baseline failure: 2,867 tests,
  2,865 passed, two skipped, zero failures or errors.
- This includes 245 Live TV/Sports/On Demand/updater tests and 28 focused tests
  covering debrid preparation, HEVC alternative transfer SEI, night-mode audio,
  and subtitle MIME/language handling.
- `:app:assembleFullDebug` and `:app:compilePlaystoreDebugKotlin` passed with
  `-PuseLocalFfmpegDecoder=false`, exercising the updated prebuilt decoder.
- `git diff --cached --check` passed. Live TV source files and TVDB identity
  implementation files have no changes in this upstream merge.

Validation logs and private device captures are in the review worktree's ignored
`build/validation` directory. The existing TVDB development checkout and its
uncommitted files were left intact. No release tag or APK publication is part of
this sync.

## Live TV smoothness: implemented work and remaining limits

The following changes are already in the beta 5.3 history:

- Commit `2ec3b6101` introduced the Live TV implementation with guide work moved
  away from the UI thread, retained guide data, and a single `SurfaceView` that
  resizes between preview and fullscreen instead of swapping video views.
- `LiveTvViewModel` retains shifted program lists by identity and avoids rebuilding
  channel/group lists when unrelated playback preferences change. Guide clock
  formatting and reminder lookups are cached to reduce redraw allocation.
- Commit `66b924174` cached Sports discovery across restarts, reduced sidebar
  redraw/semantics work, and made background workers yield during remote navigation.
  Guide reads and background discovery use dedicated lower-priority threads.
- `BackgroundWork.awaitIdle()` pauses automatic heavy jobs while Nuvio's
  movie/episode player is open. Live TV preview/fullscreen does not set that VOD
  playback flag; its guide/background work relies on the lower-priority workers.
- Beta 5 navigation changes (`c19f1d2b1`, `3d40985e4`) retain the player while
  navigating and prevent a stale playlist/group model from stealing focus.

These are implemented improvements, rather than proof that all stutter is solved.
The guide remains composed and subscribed beneath fullscreen playback. Removing
that work safely would require profiling and checking group/focus restoration,
guide freshness, dialogs, and the retained video surface.

The merged Full Debug APK installed successfully on the ARM32 onn TV and reached
the guest/login screen; its local build configuration has no account service keys.
The separate configured release app on that TV was still beta 5.2. In that release,
guide/group navigation and preview-to-fullscreen return were exercised with a live
stream. Playback also showed source/decoder retries, so this session cannot isolate
UI redraw cost from stream recovery or certify continuous video smoothness.
It is not a playback validation of the merged APK. The TV was returned to its
launcher after checking, allowing the app to stop background playback.
