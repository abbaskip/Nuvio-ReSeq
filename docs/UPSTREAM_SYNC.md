# Recurring upstream sync

The native Codex task runs Monday and Thursday at 02:00 Australia/Sydney on the
Windows development PC. It uses this runbook and `scripts/upstream_sync.py`.
This is an agent-assisted compatibility review, not a blind merge bot. No new CI
service, self-hosted Actions runner, system wake task or personal API credential
is needed. The existing `reseq-release.yml` remains the only publication path.

The maintained checkout is `C:/Users/abbac/StudioProjects/NuvioTV/sync-host`.
Run its coordinator from that directory. Its ignored .upstream-sync junction
points to the sibling project-level .upstream-sync directory containing the
recovered ledger, runs, reports, raw lint reference and recovery manifest.
If rebuilding the checkout, restore that connection before running begin.
The private 9 November recovery snapshot is upstream-sync-recovery-20261109.zip
in the project root. Preserve branches and per-run decisions across catch-ups.
Read the last morning report and reconcile any held integration branch with dev
before replaying work. The coordinator verifies the saved remote branch and starts from that head until dev contains it. Merge a newer development/release base into the isolated candidate before validation. Preserve the review branch before finishing a held run. Never treat a review-branch integration as released.

For hosted emulator validation, preserve the candidate and put its exact SHA in
automation/emulator-candidate.txt and the fetched base SHA in automation/emulator-base.txt on the coordinator branch. Push that branch and
verify the terminal Upstream candidate emulator job and the five fixture suites.
The GitHub-hosted runner uses a fresh virtual device and no application secrets.
Each job uses the Android TV profile and fixture-only backend settings. A changed candidate must be validated again.

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
   inventories unmerged commits including merge resolutions and creates a unique branch/worktree.
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
   `sync-host/local.properties` contains the approved project
   service settings retained by the dedicated bootstrap checkout. This helper
   excludes TMDB and signing properties from both files.
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
   lint, packaged-service verification, Python tooling tests and whitespace checks.
   Lint runs with the explicit report-only init script, then `lint_guard.py`
   enforces the approved released-source budget below. Normal lint behaviour is
   unchanged. Logs stay under ignored `build/upstream-sync/`. Never skip the
   guard, suppress findings globally, widen the budget or mass-clean old debt.
   If findings improve, validation automatically shrinks the tracked budget and
   stops: commit that reduction and repeat validation on the exact new commit.
   Investigate, fix and repeat after every source change.
   Execute affected integration tests as appropriate in addition. Account-live
   tests can mutate remote watched/history data: do not run them blindly.
8. Test affected risks in depth. Use TVDB mapping/identity/progress tests,
   tracking/provider tests, resume/source paging/autoplay tests, Live TV/EPG/
   Sports tests, TV Mix tests, Drive payload/conflict tests and updater tests.
   The full unit suite covers these but is not proof of device behaviour or live
   account compatibility. Add regression tests for newly identified risks.
9. Use host fixtures, CI and network-isolated emulators only. Never discover,
   inspect or access physical Android devices or use live accounts. The coordinator
   rejects the legacy probe command before any external command is run.
10. Verify actual APK project service configuration and signatures. Keep personal
    TMDB credentials out of builds. Never claim emulator or hardware results from
    unit tests. Hold publication for missing required acceptance.
11. Read the tracked beta 9 queue at `docs/NEXT_BETA_FIXES.md`. After upstream work
    select current working NNTP, then recheck it at publication. Validate all five
    required inputs on the exact candidate with the existing release gates.
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
New runs/publication check all active release jobs. Finalization waits only for
the completed run's own release commit, so an unrelated manual release cannot
strand its cleanup lock. Other jobs are never cancelled to release that lock.
Windows worker-held cache JARs are left in place and recorded for later cleanup;
they never strand a completed run's lock. The next run retries only finished,
unchanged, clean automation worktrees. Shared daemons and reused developer
checkouts are never stopped or cleaned to force those files free.
Runs and validation require at least 6 GiB free, covering the measured debug/
Playstore/lint peak with a safety reserve. `finish` archives this task's unit/lint
reports and retains a held ARM32 debug APK before removing only its own generated
`app/build`. It verifies the resolved path belongs to a known automation worktree.
Source, branches, reports and other developers' worktrees are preserved. Do not
automatically clear shared Gradle caches, SDKs or other projects to make room.
During setup only, 5.15 GiB of disposable compiler intermediates were removed from
the clean, idle beta 5.1 and November 3 release worktrees, both already incorporated
in published beta 7.1; their source, APKs and reports were retained. Beta 6 contained
source edits and was explicitly skipped. Future routine cleanup is task-owned only.

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
15 minutes on AC/DC. This is the configured timeout after sleep, not evidence
that an awake PC hibernates after 15 minutes. AC sleep is already disabled; keep
the PC plugged in, awake and Codex running. No power settings were changed. Native scheduling
does not guarantee waking a hibernated computer. Use Run now after a missed run.
See [OpenAI scheduled tasks](https://learn.chatgpt.com/docs/automations) and
[Microsoft's hibernate timeout definition](https://learn.microsoft.com/en-us/windows-hardware/customize/power-settings/sleep-settings-hibernate-idle-timeout).

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

## Approved lint policy (4 November 2026)

The maintainer approved baselining inherited lint debt only. This does not clear
MDBList/TVDB identity, autoplay/source-tier or other functional design holds.
Twenty known overlapping commits/merges carry `requires_decision` in the ledger.
The review helper refuses routine promotion of those records. The publication
gate also detects held ancestry, cherry-pick trailers and patch equivalents;
adapting an implementation still requires functional comparison and attestation.

`automation/lint-baseline.json` was captured from unchanged released tag
`v0.1.0-beta.7.1` (`289a93d49`), using a detached reference worktree. It records
the release commit/tree and raw report SHA256. The complete report is retained
privately in `.upstream-sync/evidence/lint-reference-beta7.1/`. Its released-source
findings include 4,109 errors (the earlier 4,110 count also included a local SDK
properties escaping error, which is not released source).

The immutable seed stores relative paths and hashes, not source snippets or
service values. Active allowances can only shrink. Complete reports are compared
by detector, source context/declaration and message; line-number shifts do not
change identity. New errors, increased counts, higher severity, changed error
details and returns of retired findings fail. Missing-translation language sets
can shrink but never expand or regain a fixed language. New warnings are reported
without gaining an error exemption. The guard compares budget changes against
both Git history and the run's fetched base, preventing intermediate candidate
commits from resurrecting an exemption. Previously resolved findings must stay fixed.

Capture is a one-time operation requiring clean tracked source at the explicit
released tag. Do not recapture from integrated code or overwrite the seed. Keep
the full report: neither the diagnostic init script nor this budget suppresses
lint output. The normal standalone lint task retains its default error behaviour.

On 5 November the maintainer approved verified external version-advisory handling.
Validation and ratcheting pass the retained beta 7.1 raw XML with `--released-report`.
Its SHA256 must match the immutable seed. Only an existing Warning from
`GradleDependency` or `NewerVersionAvailable`, with unchanged artifact and declared
version, may change its recommended available version. Complete warnings remain
visible and the verdict records those advisory changes. Counts, severity, other
details, retired findings and all error budgets retain their existing protections.
Missing or modified reference evidence fails closed; never recapture it to pass.

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
