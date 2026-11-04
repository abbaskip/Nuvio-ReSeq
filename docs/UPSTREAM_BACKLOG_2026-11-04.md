# Upstream backlog review, 4 November 2026

Canonical Nuvio dev was `99fbd65d8`. The 53 entries were held at ReSeq `f2b84c31a`, after beta 7.3. The two VPN fixes released in 7.2 are outside these counts.

| Disposition | Count | Result |
| --- | ---: | --- |
| Direct integration | 24 | Applied to the candidate, including four translation commits in the beta 7.4 source. |
| Adaptation with implementation complete | 16 | Includes the Portuguese resource replacement in beta 7.4, the raw-provider tracking boundary, Continue Watching, stream reuse, plugin and source-tier changes, benchmark targeting and the shared logo reader. |
| Adaptation with on-device work outstanding | 2 | Ported the benchmark journeys and recipe from `c8b855b46` and `d9746a0ba`. Regenerating ART rules for ReSeq needs a dedicated idle device. |
| Superseded or unnecessary | 11 | Ten held merges have empty Git remerge diffs. The Portuguese file deletion was superseded by its replacement. |
| Product decision needed | 0 | Existing identity, playback and source eligibility requirements permit the improvements to coexist. |

The ledger's `backlog_review` on each original SHA identifies the port and its status. For the 37 entries outside beta 7.4, the coordinator's held disposition continues to protect release promotion while runtime acceptance is outstanding. The upstream merge commits and generated upstream ART profile were excluded. The previous ReSeq profile is retained.

## Compatibility

* Native TVDB tracking writes bypass the addon-numbering transform. The shared Trakt and MDBList episode list uses original provider metadata and separates profile caches. MDBList maps default-addon anime using TMDB when available. A scope check prevents writing a delayed result to a different account.
* Continue Watching keeps the Add to Mix action. Mark watched carries the verified component set for combined episodes. A legacy cached TVDB Next Up card without verified coverage cannot write history until it has been enriched.
* The player can prefer the previous plugin and variant within eligible automatic sources. A full next-episode search also retrieves ReSeq's other sources for fallback. The cache key preserves profile, source configuration, IPTV revision and TVDB context. The picker still searches every eligible source.
* The final low-memory sequence keeps the user's buffer target and the real heap measurement in diagnostics. The budget and image cache use the reduced tier only for the upstream low-memory hardware case.
* The benchmark tooling targets recognized ReSeq package IDs. The previous generated rules remain installed until real ReSeq journeys can be collected.

## Validation and release boundary

The full functional run passed 3,176 tests with two skips and zero failures or errors. It included TVDB, Trakt, Simkl, MDBList, Continue Watching, TV Mix, source tiers, Live TV/Stalker/EPG, Sports, Drive and navigation suites. Full Debug and Play Store compilation passed, as did both benchmark Kotlin variants. A regression test verifies canonical MDBList writes even when a conflicting anime mapping is cached. Another covers the full combined-episode component set and uncertain legacy coverage. The initial signed artifact rehearsal passed on `4367d4af6`. Full lint identified a drawable/raw-resource mismatch in the shared web logo reader. After correction on `066ad43a9`, the full unit run, both app compilations and lint passed. The shrinking budget recorded 4,105 existing errors, zero new or worsened errors and 20 new warnings against the released-source budget.

No application was installed or exercised on the Onn. Remote focus on source chips, touch and RTL controls, transitions out of a trailer, reused-link playback, end-of-episode fallback and a real-device benchmark/profile recording remain outstanding. These checks are deferred until the maintainer explicitly makes a test device available. Live tracker writes and real-account backup/restore were not exercised. Beta 7.4 contains the localized resources and ledger cleanup. The additional functional source on this branch is a candidate for later promotion.
