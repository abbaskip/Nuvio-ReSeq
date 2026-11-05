# Three-package consolidation — unreleased candidate

Maintainer instruction: back up storage, consolidate functional upstream, storage/VOD and IPTV favourite-group/reordering packages onto current dev, validate together, and do not publish a beta yet. Source preparation and backup pushes are authorised. No version increment, tag or release publication is part of this rehearsal.

Base: dev / beta 7.6 dd3cbe18f7c8cb5dfa17be1a7464b4cc3045b141.

Inputs:
- Functional upstream: codex/backlog-functional-nov4, a4f1a08124bdf840d5bfb74b32277d3d4af28cb9.
- Storage / English VOD: codex/iptv-storage, b6e8df653bb8ea20df8682fa358ecfa5bae0e2c4, now backed up to GitHub.
- Favourite groups and scoped group/channel ordering: snapshot 89c40c2ad6ccaa4b2765754d28aaf3a2124a8a86 on codex/iptv-ordering-snapshot-20261105. Original uncommitted worktree preserved unchanged.

The merges retain all three input ancestries. Shared logo conflict keeps the released beta 7.6 resource reader and five tests. Ledger combines implementation evidence with existing released dispositions. The immutable lint seed is unchanged; both branches' permanent allowance reductions are retained. Beta 7.5 player-session/background-work protections and tests are unchanged. App version remains 7.6 / 10029; these are candidate test builds, not a new beta release.

Added integration regressions cover storage import policy, favourite groups, scoped channel/group order and profile isolation through one validated backup/restore. The synthetic Onn import/migration/source-only/English-video/restore fixture also verifies ordering state after deliberate mutation and restore.

Validation required before promotion: full JVM/tooling suites, both app variants, complete shrinking-budget lint guard, actual APK backend/project services, configured instrumentation APK, focused Onn tests on the isolated package, benchmark compilation and final release optimization/signing rehearsal. Never run live tracker/account mutation tests blindly. The maintainer made the Onn available for isolated testing; recheck it immediately before installation/input. Preserve production package/data. Every installed APK must pass actual-APK backend checks; personal TMDB build inputs must remain excluded, and sign-in readiness requires actual QR/pairing generation.

Functional protection flags remain release safeguards pending this combined acceptance and explicit metadata reconciliation. No new upstream commits arriving after these package inputs are included in this consolidation. Retain the existing ReSeq ART profile until new profile collection is separately completed.

Status: source integration complete; combined validation in progress. Publication prohibited by the maintainer's current instruction and this coordinator run's dry-run mode.

Integration correction: new favourite-group and ordering writes use stable playlist-specific preference keys across the single/multiple-live-playlist ID transition. Legacy custom orders remain readable; un-favouriting removes a legacy alias. Built-in/custom scopes are unchanged, and same-named groups from different playlists remain independent. Added regression cases for this transition and compatibility.
