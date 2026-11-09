# Stream-cache approval reconciliation

Explicit maintainer authorisation on 9 November 2026 covers acknowledgement of two previously shipped ReSeq adaptations, not raw upstream replacements. The records 7242ff8b and 2b4b640a appear in the beta 7.7 promotion approval and are patch-equivalent in beta 7.7, current dev and beta 9. The ledger retains their full historical decisions and now records integrated status for the shipped adaptation with a separate raw-upstream hold. Other ledger entries are unchanged.

The stream-matching implementation and its tests are byte-identical to beta 7.7. The cache payload and ReSeq episode/profile wrapper remain intact. The only cache-file addition since beta 7.7 rejects ephemeral NNTP session URLs, already included in the tested beta 9 feature.

No application code is reverted or replaced. The protected blob IDs are taken from the current beta 9 candidate, including its NNTP improvements. Beta 7.7 serves only as historical evidence. The gate still rejects raw ancestors, new cherry-picks, unresolved decisions, missing shipped ancestry and modifications of the protected current implementation. Safety tests cover these cases.
