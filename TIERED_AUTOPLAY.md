# Tiered source autoplay

Configure these options in **Playback → Stream Selection** with automatic stream selection enabled.

1. **Immediate addon autoplay** uses a separate list of **Immediate addons**. Once a playable result arrives from a selected addon, ReSeq waits 0.5 seconds and plays the first playable result in that addon's latest returned order. Normal source switches, approved-addon selections, and regex rules do not restrict this tier. An empty immediate-addon selection selects nothing.
2. **Approved-source wait** retains the normal autoplay source selections and regex rules. ReSeq picks from results received within this wait, or sooner when the whole search finishes. The existing Instant option still picks an approved result as soon as one arrives.
3. **Any-source fallback** is optional and off by default. If the first two tiers do not start playback, the ordinary source picker appears after the approved-source wait. A progress bar shows the remaining fallback wait; when it expires, ReSeq uses any playable source, ignoring the normal source switches, approval lists, and regex rules.

Both waits are measured from the start of the same source search. For example, a 10-second approved-source wait and a 30-second fallback give you another 20 seconds in the picker. The fallback must be greater than the approved-source wait. Raising the approved wait adjusts a shorter fallback automatically; selecting Unlimited disables the timed fallback.

Choose a source manually, press **Cancel autoplay**, or leave the source screen to cancel pending automatic selection. Search results remain available after cancelling autoplay. This policy also applies to next-episode searches, TV Mix continuation, and automatic episode selection inside the player. Explicit manual source selection remains manual.

The existing **Reuse Last Link** option retains its priority when enabled. Playback-engine choice, P2P consent, and source playability checks still apply. External error-page links and torrents awaiting cache verification are not automatically selected.
