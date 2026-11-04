# Recurring upstream sync

The native Codex task runs Monday and Thursday at 02:00 Australia/Sydney on the
Windows development PC. It uses this runbook and `scripts/upstream_sync.py`.
This is an agent-assisted compatibility review, not a blind merge bot. No new CI
service, self-hosted Actions runner, system wake task or personal API credential
is needed. The existing `reseq-release.yml` remains the only publication path.

## Repository and branch policy

- ReSeq: `abbaskip/Nuvio-ReSeq`, development branch `dev`.
- Canonical upstream: `NuvioMedia/NuvioTV`, branch `dev`.
  `abbaskip/NuvioTV` redirects to ReSeq and must never be used as upstream.
- Releases currently use short-lived `codex/beta*-release` branches. At setup,
  published beta 7.1 was 20 commits ahead of `dev`. Always choose the descendant
  of current remote `dev` and the latest published release tag. Stop if they
  diverge; never start from stale local `dev` or an active feature worktree.
- Prefer a normal merge only when every upstream change is accepted. With held
  changes, use dependency-aware `git cherry-pick -x` of compatible commits and
  retain original SHAs in the ledger. Never merge a held implementation merely
  to advance an upstream watermark. Never squash away upstream provenance.
- `git cherry` detects patch-equivalent commits. It cannot detect semantic
  reimplementations. Inspect source, tests, prior sync reports, commit messages
  and `git log --all --grep=<sha>` before deciding a change is new. Review merge
  commits too: conflict resolutions can contain changes absent from their sides.

## Each run

1. In the dedicated automation checkout, run `python scripts/upstream_sync.py
   begin`. For a rehearsal append `--dry-run`. This obtains a shared exclusive
   lock, checks active releases, fetches both repositories, selects a safe base,
   inventories unmerged non-merge commits and creates a unique branch/worktree.
   The printed JSON names the run, inventory and worktree. Never modify another
   worktree, reset the user's branch or reuse someone else's build outputs.
2. Read the run JSON and `automation/upstream-ledger.json`. Review new commits,
   changed paths, merge resolutions and dependencies. Classify fixes, security,
   performance, new features, dependency/API and architecture changes. Record
   each decision with `review --run ID --commit FULL_SHA --outcome integrated|
   equivalent|irrelevant|held --reason "specific evidence"`. The tracked ledger
   preserves reviewed decisions across runs. Do not repeatedly analyze held
   commits unless their dependencies, ReSeq implementation or user decision
   changed; surface their existing hold and continue independent work. Retain
   held branches/reports. For a run with no code changes, persist ledger changes
   in a review-only branch (`preserve --run ID` pushes only that run's branch).
   `finish` also saves non-integrated decisions in shared local state; `begin`
   carries them into subsequent worktrees. Never claim review-only work released.
3. Compare functionality, not just textual conflicts. ReSeq TVDB uses selected
   numbering directly in provider searches, then maps canonical tracker identity
   separately, including split/combined/extended/cross-series episodes. Upstream
   addon/TMDB remapping must never remap an already canonical reference twice.
   Compare Trakt/Simkl/MDBList scrobbles, watched/Continue Watching projections and
   default metadata paths. For Live TV compare M3U/Stalker/Xtream, EPG, playlists,
   HLS/MPEG-TS and retained player surfaces; for Sports compare event identity,
   categories and playback; for TV Mix compare random selection, source tiers,
   autoplay deadlines, remix, Continue Mix and tracking. Also compare Drive
   backup schema/conflict rules, encrypted profile-local settings, authentication,
   addon management, update channel, TV remote focus and tablet touch gestures.
4. Integrate compatible independent changes, preserving all released features.
   Resolve clear conflicts and add focused regression tests for adaptations.
   Isolate ambiguous changes. For real design choices, report differences,
   advantages, coexistence risks, options and a recommendation. Do not replace
   ReSeq, duplicate controls or silently omit useful upstream features.
5. Prepare ignored local build properties from approved project configuration
   only. Use `configure --run ID --properties PATH --sdk PATH`; the existing
   `.worktrees/beta7-release/local.properties` has approved project service
   settings. This helper excludes TMDB and signing properties from both files.
   Never read attachments for credentials or copy a personal TMDB key. Debug
   defaults for login endpoints come from `local.dev.properties`; explicitly
   set approved endpoints there if needed. Never print property values. Missing
   service configuration blocks device installation. CI uses existing secrets.
6. For a publishable integration, bump `versionCode` by one and increment the
   final beta maintenance component (e.g. beta.7.1 -> beta.7.2). Do not change
   the release line or overwrite a version already on GitHub. Use the latest
   release code as well as the selected base when checking version monotonicity.
   Write concise notes from `.github/RELEASE_TEMPLATE.md` under `releases/`.
   Commit source, tests, ledger, notes and version before validation.
7. Run `validate --run ID`. This requires a clean commit and runs the complete
   Full Debug unit suite, debug APK build, Playstore Kotlin compilation, Android
   lint, Python tooling tests and whitespace checks. Logs stay under the run
   worktree's ignored `build/upstream-sync/`. Do not exclude failures or weaken
   lint to get a pass. Investigate, fix and repeat after every code change.
   Execute affected integration tests as appropriate in addition. Account-live
   tests can mutate remote watched/history data: do not run them blindly.
8. Test affected risks in depth. Use TVDB mapping/identity/progress tests,
   tracking/provider tests, resume/source paging/autoplay tests, Live TV/EPG/
   Sports tests, TV Mix tests, Drive payload/conflict tests and updater tests.
   The full unit suite covers these but is not proof of device behaviour or live
   account compatibility. Add regression tests for newly identified risks.
9. Run `probe-device --run ID` for read-only ADB availability. Target the actual Onn 4K Pro (`jarvis2`,
   currently `192.168.1.121:5555`); `.102` is another TV. Read power, foreground
   activity and audio/media playback state. Awake, active playback, another app,
   a developer test session or uncertain state means potentially in use. Skip
   installation/launch/input; never wake, stop, log out, clear data or uninstall.
   Recheck immediately before each disruptive operation. Only test when idle is
   established. Keep backend/pairing codes, screenshots and account logs private.
10. Before any installation, use `verify_apk_services.py` on the actual APKs
    and approved properties (use `--backend-only` for an individual debug APK;
    full release service verification checks all five variants),
    not generated BuildConfig or property presence. Verify no personal TMDB
    credential is included, preserve signature and package/upgrade identity, and
    use the existing signed release workflow in draft mode for release APKs when
    needed. Do not install an unsigned/debug-signed replacement over production.
    After installing, verify Nuvio sign-in actually generates a QR/pairing code
    without logging out the user's production account. Don't claim sign-in ready
    without this evidence. Exercise relevant focus, remote/touch navigation,
    preview/fullscreen, playback/progress, persistence and update behaviour.
11. Unavailable/busy ADB is not a failed integration. For UI, player, navigation,
    native decoder, source/autoplay or identity changes, hold publication if
    automated coverage cannot establish safe behaviour. Prepare configured APKs,
    hashes, focused instructions and exact commands under the run's build folder.
    For changes adequately covered by automated tests, explain specifically why
    a device test is unnecessary. Never convert a skipped test into a pass.
12. Write a compatibility/test report, then `attest --run ID --report PATH
    --device passed|not-required|held --reason "specific evidence"`. Attestation
    is Codex's evidence-based judgement, not user approval. It must explain all
    functional overlaps, prove preserved features and describe device evidence
    or why no device test is required. Required test gaps mean `held`.
13. `gate --run ID` checks exact tested HEAD, clean tree, review completeness,
    held ancestry, preserved base, device evidence, new version, unchanged remote
    dev/latest release and no active release workflow. `publish --run ID --notes
    releases/VERSION.md` repeats the gate, atomically fast-forward pushes the
    integration branch and remote `dev`, then dispatches `reseq-release.yml`.
    Never force-push, bypass branch protection, alter GitHub permissions or use
    direct `gh release create` as a fallback. If dev/latest release advanced,
    integrate that new base in a fresh run and repeat validation.
14. Hold the lock until the workflow is terminal and publication verified. Use
    `gh run list --repo abbaskip/Nuvio-ReSeq --branch BRANCH` to identify its run;
    inspect failures and fix clear issues. The workflow retains permanent signing,
    package/version/native/service checks, all five APKs and regular-release
    beta labels (ReSeq's single update channel). Verify published tag resolves to
    tested HEAD, latest channel is correct, asset sizes are nonzero, all universal,
    ARM32, ARM64, x86 and x86_64 APKs plus SHA256SUMS/DOWNLOADS exist. Download and
    compare hashes, verify packaged services and upgrade identity. Do not report
    publication success from dispatch alone. If dispatch fails after push, record
    that state and resume the existing commit rather than incrementing again.
15. Save the morning report under the shared `.upstream-sync/reports/` and return
    it in the native task conversation. `finish --run ID --report PATH` saves
    outcome and releases the lock. It does not delete branches or worktrees.
    Push review-only/held branches where useful; never commit secrets/private logs.

## Reliability and recovery

The shared lock is `<main project>/.upstream-sync/lock.json`, independent of the
calling worktree. It is never stolen on a timer. After interruption, read its run
JSON and check associated processes, worktree and GitHub workflows. Resume the
same run when safe. Only after proving it abandoned should an agent finalize a
failure/held report and call `finish`; then start a new run. Don't remove a lock
just because it is old. Existing release workflow concurrency serializes release
jobs; the gate also defers while manual releases are active. Recheck for a newly
published newer version immediately before completing any resumed release.
New worktrees require at least 3 GiB free at preflight. Remove only this task's
completed, unneeded build outputs after retaining reports/evidence and needed
APKs. Never delete another worktree or another developer's outputs to make room.

Catch up by running the same native task with **Run now**, or asking this chat to
catch up. Inventory is ancestry/ledger based, not a date window, so missed runs
cannot lose commits. No separate catch-up scheduler competes with normal runs.
Read prior reports and review-only branches before reprocessing a held backlog.
The task is enabled after the initial dry run, while publication remains gated
by the shared `publication-enabled.json` marker. Enable only using `enable --run
DRY_RUN_ID --report PATH` after a complete passing dry run. A dry run cannot push
changes to `dev` through the coordinator or dispatch a release. Review branches
can be preserved without publishing.

Local scheduled tasks need the PC on, the desktop app running and project files
available. Windows was on Sydney's DST-aware timezone at setup. Its High
performance plan had AC sleep disabled, DC sleep 10 minutes and hibernation
15 minutes on AC/DC. No power settings were changed. If it hibernates, keep the
PC awake overnight or explicitly adjust hibernation yourself. Native scheduling
does not guarantee waking a hibernated computer. Use Run now after a missed run.

Scheduled Codex uses default sandbox permissions. At setup, GitHub/ADB and builds
worked only outside the sandbox; managed `.git` is read-only inside it. Existing
GitHub authentication had repo access and the release workflow was operational.
Unattended availability must be demonstrated, not assumed from an interactive
escalation. If the scheduled policy denies network/Git/build/device access,
record Failed with the exact blocked operation and preserve work. Never change
global permissions or bypass sandbox controls to get an unattended release.
Use the exact approved absolute `python <automation-checkout>/scripts/upstream_sync.py`
entry point for scheduled commands. For worktree Git operations use `git --run ID
-- cherry-pick -x SHA` (or add/commit/show/diff) through that coordinator; it pins
operations to the isolated run worktree, forbids forced operations and does not
permit direct pushes. The `publish` action is the guarded push path. Source edits
remain ordinary workspace file edits. Never broaden the command allowance.
The narrow user rule is `~/.codex/rules/nuvio-reseq-upstream.rules`; its matching
behaviour was checked with `codex execpolicy check`. Restart Codex once before
the first scheduled run to load the new rule; do not restart active work for it.
An actual unattended run has not yet been observed. The task must report any
remaining permission failure candidly rather than treating interactive access
as proof that scheduled execution works.

## Morning report

Use this format, omitting Decisions required when there is no genuine decision:

    Upstream sync: [Sydney date]
    Status: Published / Changes held / No relevant updates / Failed

    Upstream changes
    - Relevant changes.
    Integrated changes
    - Incorporated changes and compatibility adjustments.
    Overlapping functionality
    - Differences, safe integration or decision required.
    Testing
    - Build, automated regressions, ADB result/reason skipped, verification limits.
    Publication
    - Version, commit/release links, confirmed APK assets or why held.
    Decisions required
    - Real options and recommendation only.
    Next actions
    - Outstanding checks/fixes/reviews.
