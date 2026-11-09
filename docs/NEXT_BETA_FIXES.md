# Pending after beta 9

- Profile avatars: integrate local branch `fix/profile-avatar-url` from
  `.worktrees/profile-avatar-fix` before the next build. The user requested this
  fix on 9 November: the backend returns 42 relative avatar paths, but the default
  configuration leaves their base URL blank when `AVATAR_PUBLIC_BASE_URL` is
  omitted. Derive `/storage/v1/object/public/avatars` from the configured backend
  in that case, preserving explicit overrides. Do not install while the user's
  current playback continues. This fix has not been released or device-validated.

# Delivered in published beta 9

All five queued inputs below shipped at 150883f1f44ec436bd377d70dc94f5b47842ef4c. Public release and all five signed APKs verified on 9 November 2026. Preserve this history and check for later owner changes without replaying the delivered snapshots.

# Required changes for the next upstream beta (beta 9)

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

