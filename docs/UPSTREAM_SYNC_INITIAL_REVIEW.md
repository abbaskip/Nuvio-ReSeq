# Upstream sync: 4 November 2026

Status: Changes held

Initial setup and non-publishing rehearsal. No upstream integration was promoted,
no release was created, and no device APK was installed.

## Upstream changes

Canonical upstream `NuvioMedia/NuvioTV:dev` was inspected at
`99fbd65d8c16928d1fe11bf3f9812c4b5aaaca8f`. The requested old URL
`abbaskip/NuvioTV` redirects to ReSeq itself. The last common incorporated
upstream snapshot is `f362dfeea1d241a7d57bfb690c4bc24789839e40`.

There are 72 upstream commits outside published beta 7.1 ancestry, comprising
46 non-merge commits and 26 merges. None of the 46 were patch-equivalent under
`git cherry`; semantic equivalents still need source/test comparison.

Relevant groups: low-RAM image/player memory budgets; plugin source priority,
variant identity, caching and autoplay; reused stream information; addon focus;
Continue Watching watched actions; MDBList anime remapping; VPN network labels;
RTL value ordering; settings persistence/scrolling; trailer behaviour;
translations; benchmark/profile tooling. No dependency version/AAR update was
identified in this snapshot. Upstream's version bump is not ReSeq's version.

## Integrated changes

- Added the local Codex coordinator, isolated worktree policy, shared exclusive
  run lock, persistent per-commit review ledger and compatibility/publication
  gates on `codex/upstream-automation`.
- Preserved beta 7.1 as the setup base. Remote `dev` was 20 released commits
  behind it; starting from dev alone would omit TV Mix/source-autoplay changes.
- Fixed a Windows regression-test fixture that attempted to physically allocate
  a nearly 10 GB hole. It now requests sparse allocation while testing the same
  large file offsets. No playback implementation was changed.
- Preserved the existing signed release workflow and single update channel,
  including universal, ARM32, ARM64, x86 and x86_64 APKs.

## Overlapping functionality

The virtual full-merge rehearsal found nine textual conflicts: app versioning,
StreamAutoPlaySelector, StreamRepositoryImpl, TraktEpisodeMappingService,
StreamRepository, ContinueWatchingSection, PlayerRuntimeControllerStreams,
StreamScreenViewModel and Portuguese strings. The source/index were untouched.

| Area | Upstream implementation and benefit | ReSeq implementation and compatibility requirement |
| --- | --- | --- |
| MDBList episode identity (`facf25ec3`) | Maps anime between addon season layouts and TMDB using titles/position, shares addon-episode cache with Trakt. Improves default-addon watched/progress accuracy. | TVDB resolves split/combined/cross-series episodes into provider-native identity before writes and marks references `providerDefault`. An unconditional second addon-to-TMDB transform could change that identity. Keep ReSeq's canonical path; add upstream mapping only to the default-addon path and use the profile-aware TMDB service rather than requiring a built-in personal key. Test both paths before integration. |
| Plugin autoplay (`bb17891ae` / #4385) | Prefers the previous plugin, retains variant tokens and source-cache priority, deduplicates provider playback-start records. | ReSeq has immediate/timed backup source tiers, bounded any-source fallback and TV Mix continuation. Preserve deadlines, remix and canonical episode searches; adopt provider/variant identity improvements as compatible additions. A clean merge would not establish these properties. |
| Continue Watching (`b71ccb1f6`) | Adds a mark-watched action. | Existing TVDB progress projections and TV Mix state must use the same tracker identity and covered component set; avoid a second inconsistent watched-action path. |
| Source cards/grouping/cache | Groups provider labels/cards and improves reuse/current-stream markers. | Preserve atomic source paging, addon filtering, tiered search cache isolation and focus. Adapt grouping before/after pagination only after testing the resulting navigation. |
| Live TV/Sports/TV Mix | This upstream snapshot does not add competing versions of these full features. | Shared player/search/memory/navigation changes can still affect their playback and focus. Existing M3U/Stalker/EPG, Sports matching and TV Mix coverage remain required. |
| Benchmark/baseline profile | More complete player journeys and stricter performance gates. | Generated profiles describe upstream's UI, not all ReSeq routes. Review tooling separately and regenerate a ReSeq profile on an idle test device rather than blindly replacing the generated profile. |

These are integration requirements, not a request to replace ReSeq's design.
The VPN-label pair and other independent fixes can be reviewed/integrated
separately while episode/autoplay work is held. No arbitrary implementation
precedence was chosen during setup.

## Testing

- Debug APK build and Playstore Debug Kotlin compilation succeeded.
- Full unit suite: 3,092 tests, 3,090 passed, two existing skips, zero failures
  after the sparse-fixture correction. Includes TVDB, trackers, Live TV/Sports,
  TV Mix, Drive payload/conflict, navigation and updater coverage.
- Python release/coordinator tooling: 56 tests passed, including stale-pass
  invalidation, exclusive run ownership, branch-base preservation, publication
  disabled by default, Git restrictions and personal-TMDB configuration exclusion.
- Full Android lint ran and failed on 4,110 existing errors, 2,010 warnings and
  48 hints. The largest categories are missing translations (2,632) and unsafe
  opt-in usage (1,262); remaining findings include API/foreground-service,
  resource, Compose and formatting errors. Findings predate upstream integration.
  No checks were excluded or suppressed to claim a pass. Publication stays gated
  until the lint policy/failures are resolved and exact-commit validation passes.
- Actual finished debug APKs passed packaged service verification; backend URL
  and API key were configured. Values were withheld. No personal TMDB credential
  was copied into build inputs. The verifier also checked the required project
  services. This is not a sign-in/device test.
- ADB over Wi-Fi was available outside the sandbox. Confirmed the real Onn 4K Pro
  (`jarvis2`, `.121`); `.102` is another TV. Device was awake and its foreground
  state changed during setup, so no install/launch/input was sent. QR/pairing and
  device regression tests were not performed and are not reported as passed.
- The existing GitHub workflow was dispatched in `artifacts_only=true`,
  `publish=false`, `device_tests=false` mode. Its final result is recorded in the
  private morning report; dispatch alone is not release validation.

## Publication

No new version/release/tag. Setup is preserved on the dedicated automation
branch. ReSeq's latest published release remained `v0.1.0-beta.7.1` at inspection.
Native task: `nuvio-reseq-upstream-sync`, Monday/Thursday at 02:00 on the
Windows host's Australia/Sydney timezone, following local daylight saving.
Review/testing can run while publication is disabled.

The shared run state and private reports are in `<main project>/.upstream-sync/`.
The initial dry-run id is `20261104T071715Z-378778`. Its gate explicitly rejected
publication because it is a dry run; failed validation cannot enable publication.

## Decisions required

Full lint was not an existing release-workflow gate. The newly exercised full
lint check found substantial inherited debt. Recommended policy: capture an
explicit baseline of findings on the unchanged released source and fail every
new lint error, keeping full automated regression and required device tests.
Alternative: keep automated publication disabled until the inherited errors are
fixed. This is a validation-policy decision; it is not resolved by weakening a
check silently. The choice was requested during setup.

## Next actions

- Resolve the lint policy, repeat the complete dry run, then enable publication
  only when it passes. Keep the native recurring review task operational.
- Review/adapt the new upstream groups in independent batches; preserve TVDB
  numbering/identity and ReSeq autoplay/source-tier precedence.
- Restart Codex before the first scheduled run to load the exact-coordinator
  command rule. Its rule match was verified; actual unattended operation has not
  yet been observed. The task must report any remaining permission failures.
- Keep the PC on and the desktop app running. Power settings were not changed:
  AC sleep was disabled, DC sleep was 10 minutes, and the AC/DC hibernate timeout
  after sleep was 15 minutes. Keep it plugged in and awake; a hibernated PC cannot
  be assumed to run the local task. No automatic wake mechanism was added.
- Disk space was about 1.9 GiB after the build. New worktrees require 3 GiB at
  preflight. Reclaim only this task's disposable build outputs after retaining
  evidence; don't remove active developers' files. Use the native task's Run now
  for a safe catch-up after downtime or a failed preflight.

This task retained a verified ARM32 debug APK, packaged-service evidence and
`DEVICE_TESTS.md` in `.upstream-sync/artifacts/initial/`, with unit/lint evidence
under `.upstream-sync/evidence/initial/`. Its own disposable `app/build` was then
removed, recovering free space to about 3.2 GiB. Other worktrees were untouched.
