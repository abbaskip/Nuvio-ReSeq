# Pending after beta 9

- Startup navigation follow-up: isolated branch `fix/startup-navigation-regression`,
  backed up at `4961a804c`, in `.worktrees/startup-navigation-regression`.
  Fixes a reproduced catalog-reader stall while an import waits for network or
  trailer playback; keeps the original four writer operations serialized using
  a separate monitor. Bounds guide priority key extraction to the same 400 keys
  and moves its observer transformations off the UI thread. No settings, UI flow,
  update interval or feature availability changes. Full 3,334-test suite passed
  (zero failures/errors, two existing skips), all five new tests passed, debug
  APKs built and lint guard passed with 4,100 inherited errors. Before/after
  evidence is durable in `.upstream-sync/evidence/startup-navigation-20261109-4961a804c`.
  This is not proof of the cause or resolution of the separately reported crash
  or all menu lag. A later one-off, explicitly authorised read-only Onn log check
  confirmed installed beta 9 / 10038: OOM at 18:38 in a background sorting path,
  then a KeyEvent ANR at 18:40 on 9 November. Private saved logs and a summary are
  in the evidence directory's `device-followup` folder. Device queries have ended;
  this exception does not authorise any future device access. Obtain the exact
  beta 9 R8 mapping and investigate the saved crash locally. Preserve
  that distinction in release notes. Validate the combined exact candidate before
  any future publication; no separate release was authorised by this follow-up.
  No physical-device/ADB access or live-account testing is permitted.

- Profile avatars: integrate local branch `fix/profile-avatar-url` from
  `.worktrees/profile-avatar-fix` before the next build. The user requested this
  fix on 9 November: the backend returns 42 relative avatar paths, but the default
  configuration leaves their base URL blank when `AVATAR_PUBLIC_BASE_URL` is
  omitted. Derive `/storage/v1/object/public/avatars` from the configured backend
  in that case, preserving explicit overrides. Do not install while the user's
  current playback continues. This fix has not been released or device-validated.

# Delivered in published beta 9

The five inputs under Historical beta 9 delivered scope shipped at 150883f1f44ec436bd377d70dc94f5b47842ef4c. Public release and all five signed APKs verified on 9 November 2026. Preserve this history and check for later owner changes without replaying the delivered snapshots.

## Pending follow-up after beta 9

This section is new work and remains pending despite the historical beta 9 delivered marker.

Maintainer request on 9 November 2026. Integrate the validated owner branch
feature/fresh-install-defaults from sync-host/.worktrees/fresh-install-defaults,
checking its latest committed head and tests before integration.

Fresh installs enable installed Stremio add-ons and IPTV at the 8-second approved
tier; plugins are off there but remain eligible at the 25-second any-source
fallback. Home trailers start after 2 seconds in hero media, with fullscreen
backdrop and mute disabled; focused-poster expansion and autoplay are enabled. Shuffle is enabled and episode ratings are shown by default. Details trailers start after 5 seconds in fullscreen.
Existing installs, restored preferences and user edits retain their settings.

The branch also adapts Trakt commits f07acf603 and b1bf4bd83 for default-metadata
series only, using profile/order-scoped mapping caches. Selected TVDB orders and
canonical references retain their existing paths. The raw broad upstream
implementations remain held. Preserve the scoped adaptation review evidence.

Apply the maintainer dispositions from the branch ledger before resurfacing old
decisions: 105490c2d forced completion was declined; ca8bdce45 Continue Watching
threshold and 7bc69ea42 subtitle/AutoSync defaults retain current ReSeq behaviour.
Their original records are preserved as history. These are resolved preferences.
Validate the combined exact release candidate as usual. No separate publication
was requested with this implementation task.

# Historical beta 9 delivered scope

Maintainer instruction, 8 November 2026: include these two locally implemented,
uncommitted and unreleased Beta 8.3 fixes with the next upstream integration beta.
Do not publish a separate beta now. The next combined beta is authorised only
after the existing compatibility, regression, device and publication gates pass.
This does not resolve or override any protected functional/design holds.

Additional maintainer instruction, 9 November 2026: include the most recent
version of our NNTP/Usenet implementation in this same combined beta.
The maintainer subsequently specified beta 9 for this NNTP-containing release
and requested the Advanced Discover feature below in the same beta. Do not ship
a separate beta for this feature or increment the feature branch version now.

## Live TV on Home

- Restore the recent-channels row, its guide and playback actions.
- Restore Up navigation from expanded posters in the Modern layout.
- Preserve the selected channel and group when opening the guide.
- Validate recent-channel population, guide context, playback actions and remote
  focus/navigation, including Modern expanded posters.

## Stalker playback recovery

- Selecting the same channel again must not cancel active recovery and replay a
  stale URL.
- Retain clearer timeout/cancellation diagnostics and the new regression test.
- Validate same-channel reselection during recovery, recovery completion,
  intentional channel switching, cancellation and timeout behaviour.

## Latest NNTP / Usenet implementation

- Include the latest complete implementation, not an earlier NNTP snapshot.
- Timing clarification, 9 November: finish the upstream review/integration work
  first, then re-discover the latest NNTP owner branch/worktree and integrate its
  current complete implementation, including relevant uncommitted changes.
  The maintainer reports NNTP is currently working and expects further changes
  before then. Preserve that working behaviour; this report is not a substitute
  for validation of the combined candidate. Do not pin selection to today's SHA
  or an initial-run snapshot, and do not replace our implementation merely
  because upstream has overlapping code.
- Record the selected source HEAD and a manifest/hash of relevant local changes
  without credentials or generated outputs. Validate the combined candidate on
  its exact committed source, then recheck the owning NNTP source immediately
  before the publication gate. If relevant changes advanced meanwhile, capture
  them safely in the isolated integration worktree and repeat affected checks
  and required exact-commit validation. If a changing source cannot be captured
  consistently, hold the beta and report it rather than shipping a stale or
  partly captured NNTP implementation. Never reset or modify its owner checkout.
- On 9 November the clean owning checkout was found at
  `C:/Users/abbac/StudioProjects/NuvioTV/.worktrees/usenet-playback`, with HEAD
  `acd552a11b9691cc79c49fca5768dd3c66ab97c6`
  (`Adapt upstream NNTP reliability and session ownership improvements`).
- Its Git metadata resolves under `.worktrees/cloudstream-issue12/.git`, rather
  than the missing main `.git`. Recheck the branch, HEAD, uncommitted changes and
  any later work at integration time; this SHA identifies the inspected version,
  not a restriction to an older version if the implementation advances.
- Preserve the full feature ancestry, dependencies and upstream provenance;
  inspect integration differences and equivalently incorporated changes before
  choosing how to integrate it. Do not cherry-pick only the tip reliability fix
  and omit the underlying NNTP implementation.
- Run the NNTP/Usenet regression suites (including NNTP TLS, playback,
  reliability and pending playback), plus affected player/settings/storage
  integration and required device checks. Review session ownership, cancellation,
  retries, seek/range serving and cleanup where implemented. Do not expose NNTP
  credentials or claim live-provider/device testing from compilation/unit tests.
- Include the NNTP implementation alongside both fixes above in the release
  notes. No publication or device action is requested by this queue update.

## Advanced Discover: Title contains

- Add a free-text `Title contains` field for both movies and TV shows.
- Match literal text within the displayed title, ignoring case and surrounding
  whitespace; combine with all existing filters and provider sort order.
- Empty text leaves existing discovery behaviour unchanged. Save/restore the
  field with profile-local saved discoveries, reset it with other filters, and
  preserve readability of older saved discoveries without this field.
- TMDB Discover has no title-text query parameter. Filter the returned pages
  locally, retain unfiltered cached pages and pagination, and let users load more
  when a page has no matching titles. Explain that more titles are checked as
  results load and label the visible matching count without claiming a global
  title-match total. Never silently send an unsupported parameter to TMDB.
- Implementation worktree:
  `C:/Users/abbac/StudioProjects/NuvioTV/.worktrees/discover-title-contains`, branch
  `feature/discover-title-contains`, based on released beta 8.3. Recheck its final
  commit, tests and any later changes before integrating into the beta 9 candidate.
- Implemented and backed up to origin at
  `c8c55a79c36dfd3ed3fb79103dd42c518ecd912c`. All 38 targeted discovery unit tests
  passed (nine new tests), with zero failures/errors/skips. The app source compiled.
  This is feature validation, not full combined-release or device acceptance.
- All five Full Debug APKs built and actual packaged project/backend service
  checks passed. Packaging initially failed without a detailed cause; an unchanged
  diagnostic retry succeeded. Logs, test XML and `build/title-filter/validation.json`
  retain the evidence in this feature checkout. No lint guard, physical device
  acceptance or full combined-suite pass is claimed here; those remain required
  before the beta 9 release. No APK was installed and no release was published.
- Validate title matching, cache isolation, pagination through empty matches,
  saved discovery compatibility/profile isolation and view-model apply/reset.
  Reuse the existing TV-friendly text editor and require relevant focus/keyboard
  acceptance in the combined candidate. No Onn installation is requested now.

## Performance and stability fixes

Use the tested delta from `origin/review/performance-nov9` at
`203c5db99ed3e1dbb92cd33011f9c67399bd0339` (parent `d9297c6c83d009ec946a0cbb46b8ffd1f3ec7c42`).
Apply the delta with provenance or verify equivalent changes on current source.
Its parent contains an earlier NNTP snapshot. Select the latest working NNTP
after upstream integration as specified above, then reconcile the encrypted
RAR read changes against that version. Record coverage of every change.

Run the XMLTV parsing, guide search, Sports Hub, Continue Watching ordering,
TS/HLS recording and HLS init recovery, and encrypted NNTP range-read tests on
the combined candidate. For Sports Hub, exercise changed guide text, labels,
guide revisions during a scan and clock-driven event transitions. Reuse of
normalised text must preserve live updates and cancellation. The host review
passed 3,303 JVM tests with two live-service skips and 86 tooling tests.
Repeat combined release validation on the exact candidate commit, including
the full lint guard. The host results do not establish a fix for playback stutter.

## Validation environment

Use host fixtures, controlled services, CI or an isolated emulator. Never
discover, probe, inspect, connect to or control physical home devices,
including through ADB or a coordinator helper. Do not use live accounts for
automated testing. If physical acceptance is necessary, hold publication and
give the maintainer the candidate and a short manual checklist. Record only
evidence actually obtained. This restriction applies throughout the run.

## Integration instructions

Locate the actual owning checkout and capture its diff, base commit and tests
before integrating. These descriptions are the maintainer's implementation
summary, not evidence that the source or tests have been independently verified.
Check whether either fix has since been committed, merged or equivalently fixed;
avoid duplicate integration. Preserve owner worktrees and unrelated changes.
Snapshot only the relevant fixes into an isolated integration worktree with
provenance. Commit and validate all five queued items together with compatible
upstream changes. Include them in the beta 9 release notes. If source cannot be
found or validation is blocked, hold publication and report the missing evidence.

