# Selected-source CW and settled startup cards

PR #29 follow-up on `codex/partial-rewatch-cw`, based on dev `a4d01d7de` (including `8bd4f83b2`). This is the local.22 change; the original partial-rewatch work is recorded in [PARTIAL_REWATCH_CW.md](PARTIAL_REWATCH_CW.md).

## Causes and changes

The selected provider was already the main progress source, but its repository still admitted indefinitely retained local/cloud TVDB pauses and ordinary local watched rows. Those records can have been imported or mirrored from a different tracker. A disconnected/loading selected provider could also temporarily become the local source. Details read local watched timestamps separately from the selected episode projection.

CW then published several working answers: tracker membership reduction, native pause resolution, lightweight/partial Home enrichment, and a separate TVDB reinterpretation in Home's display/shuffle layer. Trakt's refresh method queued work rather than awaiting history and playback. First-value episode reads could precede their lookup. The earlier normalized fake-clock fixture did not exercise these independent publication paths.

The follow-up makes the existing repository boundaries enforce two rules:

- CW and Details read the persisted selected progress provider directly. The other providers' authentication/progress flows are not subscribed for this decision. Selected Nuvio sync remains its own source.
- The only local overlay for a selected tracker is a device-only playback receipt. Imports and mirrored rows cannot create one. It lasts at most ten minutes and retires for a tracker when its confirmed records cover that play. An optimistic Trakt echo is not confirmation. Deleted local rows cannot be restored by a receipt. Split/combined episode confirmation uses verified content coverage.
- Keep the last presented member map in the existing atomic CW document. Loading and identity validation affect working membership, never the saved first frame. An interrupted refresh therefore cannot replace the next startup's cached cards with an unfinished revision.
- Await the selected provider's completed refresh, stage membership, resolve TVDB pauses, then let the existing Home enrichment worker commit each finished show once. Loading, failed lookup, incompatible lease and series-choice results cannot overwrite a resolved presentation. A semantic history fingerprint suppresses later metadata-only updates for the same history.
- Sequential Home decoration cannot synthesize a placeholder from a completed seed; it displays the committed identity; it cannot resolve it again from a temporarily missing catalogue. Shuffle retains its existing picker. Rich enrichment commits per show, without waiting for other shows.
- Explicit playback/history edits still invalidate the appropriate member. Completed shows remain absent after partial removal and identical refreshes. Completed native coverage still marks an episode watched during a partial rewatch.

No scrobble writer, history mutation payload, source tier, NNTP path, Sports matching or tracking-account write operation was changed. Remove partial watch still fans out to every connected tracker, preserving completed history and the existing Remove last watch action.

## Cases where selected-only changes the answer

These are fixture/code comparisons, not an inspection of real accounts:

| Existing input | Selected-only answer |
| --- | --- |
| Another tracker has a newer pause, a different episode, or a different completion | Only the selected provider can choose the resume/next episode or remove it. |
| A TVDB pause was imported from cloud or mirrored from another tracker | It cannot override the selected provider, including for a completed-show rewatch. |
| Local watched rows exist without matching selected-provider history | They cannot advance Details/CW or suppress an unwatched episode. |
| An old local record or unsupported provider ID used to survive indefinitely | It contributes only with a recent device receipt while awaiting confirmation, for at most ten minutes. |
| Selected-provider sign-in/readiness is temporarily unavailable | Keep that source's cached CW; do not switch to local/cloud history. |
| All providers agree and no foreign/local fallback was winning | The episode/progress answer is unchanged; startup presentation is steadier. |

## Verification

All functional tests use synthetic JVM fixtures. No real tracking accounts are read or operated for validation.

`CwSettledStartupTest` has 144 fake-clock cases: Trakt/Simkl/MDBList selected in turn, all 24 completion orders of provider replies/catalogue work, two delay/cache variants. Every observed repository frame is checked: exact cached first card, zero or one settled change, no intervening placeholder, late Details/metadata replay ignored, and the last settled card survives disk reload. Strict mocks fail if another provider's progress/authentication is read.

Repository fixtures check selected-source CW/Details, disconnected-source behavior, native history plus rewatch pauses, recent device receipts, per-tracker acknowledgement, expiry, split coverage and deletion. Existing Office scenarios, a4d01d7de individual rewatch/split history, 8bd4f83b2 stale-card rebuild, CW cold start/return/unwatch/shuffle and the 9.8 outgoing contract are included in the full suite.

Two old sequential Home assertions now explicitly expect the cached episode to survive metadata/order work. The dropped-show failure fixture uses the awaited refresh API, avoiding a queued signal before the startup signal collector subscribes. Parameterized fixtures release their MockK answers after each case. The full run uses one JVM test worker with a 1 GB heap through a local init script; the original 512 MB worker exhausted its heap.

Final JVM and minified/signing results are recorded in the PR description and local build evidence. The only authorized physical-device action is installation of the signed local.22 APK on the model-confirmed Onn with `adb install -r`; the app is not opened or operated.
