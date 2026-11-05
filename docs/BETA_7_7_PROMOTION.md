# Beta 7.7 release promotion

Maintainer approval on 5 November 2026: "Looks like this is running perfectly. Let's push the beta to public/published. Think it would be 7.7?"

Accepted source: `84675344e68b5f760741db53dcb7d66878aa83ab`, comprising all combined development changes through `fe92fafa093719049cf295622101b4a72389b6b7` plus Sports navigation and finished/live/upcoming ordering. Signed artifact run [41966444986](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/41966444986) passed native-library, signing, upgrade-identity and packaged-service verification. Build 10031 was installed over the existing Onn beta with the same release certificate and retained app-data directory; the maintainer accepted its behaviour.

Beta 7.7 advances metadata to version code 10032. Application source is unchanged from the accepted build. Existing combined validation and its limits remain recorded in [the consolidation report](CONSOLIDATION_2026-11-05.md). The maintainer requested no additional test runs; the publication build verifies finished artifacts without rerunning the test suites.

## Protected overlap reconciliation

The 13 functional records below concern the ReSeq adaptations already in the accepted combined source. Canonical TVDB identity, profile/account boundaries, enabled ReSeq source families and complete fallback remain requirements. Approval promotes those adaptations; it does not authorise substituting the raw upstream implementations. Original ledger outcomes and historical protection contexts are retained.

| Upstream record | Accepted adaptation and retained requirement |
| --- | --- |
| `facf25ec382026f7aa27477a81f578176e1ac5c7` | Shared raw provider metadata only, scoped caches by profile, guarded canonical TVDB writes and selected orders, and preserved the runtime TMDB credential path. New canonical-reference and Trakt metadata regressions. |
| `b71ccb1f6f805d6b0b778f4370aa58e38435dbe3` | Kept Add to Mix, propagated verified TVDB component sets through Next Up cache and completion, and guarded uncertain coverage. |
| `7242ff8b63afdd011570647ed48403b4bba219c7` | Saved upstream stream metadata inside the ReSeq cache-key wrapper so TVDB and source identity survive link reuse. |
| `2b4b640a85de7d6fd554f9ec0e5187529d88c8dc` | Applied both follow-ups to reused stream matching and observer state after adapting the parent change. |
| `cac9fea5805bf1b75bb98c3bf4d674a7d7a2bc4e` | Integrated the final plugin, cache, playback and grouping changes together in 76ffd5b01. Preferred variants remain subject to enabled ReSeq source kinds; full searches retain fallback, IPTV revision and TVDB context. |
| `665f19260b7a4949e6ebe0a5e002ac01303af10b` | Integrated the final plugin, cache, playback and grouping changes together in 76ffd5b01. Preferred variants remain subject to enabled ReSeq source kinds; full searches retain fallback, IPTV revision and TVDB context. |
| `5601777bd58aeae240b528827f4e7f7d32997dea` | Integrated the final plugin, cache, playback and grouping changes together in 76ffd5b01. Preferred variants remain subject to enabled ReSeq source kinds; full searches retain fallback, IPTV revision and TVDB context. |
| `fdf8d3f023b68c6cc3a7ae2af97b8e1f0c553594` | Integrated the final plugin, cache, playback and grouping changes together in 76ffd5b01. Preferred variants remain subject to enabled ReSeq source kinds; full searches retain fallback, IPTV revision and TVDB context. |
| `fcc0f38baab6ebe28c06f89b7c50fd05cf70e32c` | Integrated the final plugin, cache, playback and grouping changes together in 76ffd5b01. Preferred variants remain subject to enabled ReSeq source kinds; full searches retain fallback, IPTV revision and TVDB context. |
| `665c3c14da2c4b0c380443a394c54959289f9310` | Integrated the final plugin, cache, playback and grouping changes together in 76ffd5b01. Preferred variants remain subject to enabled ReSeq source kinds; full searches retain fallback, IPTV revision and TVDB context. |
| `cc4e60e4f887cafa31a20a0c59224b753ed6100f` | Integrated the final plugin, cache, playback and grouping changes together in 76ffd5b01. Preferred variants remain subject to enabled ReSeq source kinds; full searches retain fallback, IPTV revision and TVDB context. |
| `4f1e27b79454ab697070c16f2f491aebeeacee52` | Integrated the final plugin, cache, playback and grouping changes together in 76ffd5b01. Preferred variants remain subject to enabled ReSeq source kinds; full searches retain fallback, IPTV revision and TVDB context. |
| `ed4bc05bf0e7d0b7d97747da6d190d4012c8baa1` | Integrated the final plugin, cache, playback and grouping changes together in 76ffd5b01. Preferred variants remain subject to enabled ReSeq source kinds; full searches retain fallback, IPTV revision and TVDB context. |

The other seven historical protected records are merge-topology entries with no independent patch. Their existing irrelevant/remerge dispositions are retained and their approval flags are reconciled against the already reviewed parent changes:

- `a605df2812adc36a1a0c2d0f5c8dc250e8f23e36`: Merge topology only: combined diff has no additional resolution delta. Functional side commits are separately recorded; do not cherry-pick this merge as an independent feature.
- `7a99d62aae1c42699ecc1ab2293d9147e5ea0f44`: Merge topology only: combined diff has no additional resolution delta. Functional side commits are separately recorded; do not cherry-pick this merge as an independent feature.
- `441e12eec73ad223848ad828539d7fe3285fb99f`: Automatic remerge exactly reproduces this upstream merge. All parent changes have separate ledger entries, with feature ports on codex/backlog-functional-nov4.
- `7b0904c150bb5cc59a85a44bb6dfcae6f4211482`: Automatic remerge exactly reproduces this upstream merge. All parent changes have separate ledger entries, with feature ports on codex/backlog-functional-nov4.
- `bb17891ae115c58658f4ce862079b6755ebd5978`: Automatic remerge exactly reproduces this upstream merge. All parent changes have separate ledger entries, with feature ports on codex/backlog-functional-nov4.
- `9c1b9a192cf8dd08b6342432e7caec36f0261334`: Automatic remerge exactly reproduces this upstream merge. All parent changes have separate ledger entries, with feature ports on codex/backlog-functional-nov4.
- `a102f6bf57b839db329d72a0e88d76ff67dc1f03`: Merge topology only: combined diff has no additional resolution delta. Functional side commits are separately recorded; do not cherry-pick this merge as an independent feature.

The global publication hold is cleared by the explicit maintainer approval above. The 42 included consolidation records now reference this release promotion. Unimported inventory entries and all other ledger dispositions remain unchanged. No additional upstream changes are imported into this release.
