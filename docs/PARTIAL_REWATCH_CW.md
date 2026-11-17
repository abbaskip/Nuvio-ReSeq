# Completed-show partial rewatches

Local.22 follow-up: [selected-source progress and settled startup cards](CW_LOCAL22.md).

Base: `dev` at `a4d01d7de`, including `8bd4f83b2`. Comparison reference: published `v0.1.0-beta.9.10` at `75472cff2`.

## Causes

Trakt's snapshot merge, completed-playback cleanup and watched-playback suppression used `isInProgress()`, whose start threshold is 2%. A positive short rewatch below that threshold could be removed as though completed history made its playback redundant. Details' canonical episode records still contained that pause. This supplied different inputs to Details and CW, rather than a new episode-order mapping or a lease/revision failure. A few percent into a selected split part can still be below 2% of the tracker's longer native episode.

The selected-order repository also deduplicated Simkl/MDBList completed rows and live pauses by show/season/episode before projection. That could discard a distinct playback event at the same coordinates. The shared TVDB projection needs both records to apply recency and retain completion evidence for other segments.

MDBList's default episode read omitted completed rows unless addon numbering was remapped, and its completed-row conversion omitted the supplied TVDB episode ID. After clearing a pause, the selected-order read could therefore lack the completed-show evidence needed to remove the CW offer.

`hasRemovableEpisodePause()` additionally required an empty watched-ID set. A partial rewatch can legitimately carry completed coverage for an already-watched segment; that coverage is not proof that the new playback finished. Pause deletion did not acknowledge the event in durable CW, allowing a stale playback snapshot to replay it.

## Fixes

- Retain positive incomplete playback in the three Trakt snapshot decisions, including pauses measured in seconds. Outgoing normal-play scrobble payloads are unchanged.
- Keep distinct history and pause events until the existing selected-order projection resolves them. CW and Details retain the same verified episode identities and timeline math. No episode arithmetic or alternate resolver is added.
- Include MDBList completed episode rows in its default read and retain their verified TVDB IDs. A live pause still wins its coordinate; clearing it exposes the retained history.
- Permit removal of an incomplete pause regardless of older watched coverage. The existing episode-options menu consumes that predicate. Native local/cloud pause records are checked through the same projection when their coordinates differ from the selected episode.
- Continue using each connected provider's pause-only removal operation. Preserve completed history, other episode pauses and the existing Remove last watch action. Retain local progress if a provider removal fails.
- Acknowledge only the removed pause in profile/source-scoped CW state, preserve completion records, reject stale playback replays, and allow a later new pause. After positive selected-catalogue/history evidence confirms the whole show is watched, retain the history seed without a visible CW offer.

The existing enrichment leases, history revisions, completed-vs-stale-pause rules and the stale-card rebuild from `8bd4f83b2` remain enforced.

## Initial PR29 verification (before local.22)

All account data is synthetic. No app is installed, no emulator or TV is contacted, and no real tracking account is accessed. The minified release APK is a local CI/debug-signed validation artifact, not a distributed beta.

The recorded Office alternate-order fixtures cover all episodes watched, then Trakt/Simkl/MDBList pauses at 0.02%, 1% and 3% of the selected playable episode: Delivery Part 1, Delivery Part 2, Shareholder Meeting, and a combined Lecture Circuit episode. The combined case retains watched coverage for the other native segment. These are 36 provider/episode/progress scenarios.

The fake-clock Details→Home test schedules delayed metadata results across repeated provider/Home refreshes and checks every published CW frame. It then removes the pause, replays stale snapshots, verifies no visible CW card and unchanged completion records, and confirms a later new pause can resume. The same scenarios run for all three tracker sources.

Repository/provider fixtures verify all-tracker fan-out, mapped local cleanup, sibling preservation, failure/retry behavior, Trakt playback-ID deletion, Simkl playback-ID deletion, and MDBList episode pause clear without history removal. Existing 9.8 normal-play contract tests and the `a4d01d7de`/`8bd4f83b2` regressions run in the full suite.

Final build and test counts are recorded in the PR description and local `build/rewatch-evidence/` summaries.

Final JVM result: **3,750 tests across 567 suites, zero failures/errors, two existing opt-in skips**. The skipped tests are live TVDB integration and local-provider NNTP profiling. The 9.8 outgoing golden contract, both earlier dev regressions, all new rewatch cases and the Windows persistence fixtures pass. The minified release validation uses `:app:assembleFullRelease` with local CI/debug signing; no artifact is installed or published.

Published 9.10 was tested in a separate checkout with reproduction tests only. It failed short-playback retention after watched-history load and watched-coverage pause eligibility. With the pause supplied consistently, its normalized Details/Home fake-clock guard passed; the combined-episode removal predicate failed. This distinguishes the tracker-input disagreement from the lease/revision protection that must remain intact.

The full run exposed existing Windows sharing violations in the two Live TV preference fixture classes. A test-only rule reruns these isolated fixtures with fresh temporary folders, at most five attempts with 25ms gaps, only for `FileNotFoundException` containing the host's “being used by another process” message. Ordinary assertion failures and other errors are never retried; persistent sharing failures still fail. No Live TV production code changed.
