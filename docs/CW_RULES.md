# Continue Watching: the rules

These rules define how Continue Watching (CW) must behave in Nuvio ReSeq. Every CW change is measured against them. If the existing code does something different, the code is wrong, not the rules. Any case where following a rule changes today's behaviour must be listed for the maintainer to decide; never silently keep an old special case.

## 1. Where progress comes from
- CW reads only the user's selected watch-progress source (one tracker), plus this device's own very recent plays until that tracker confirms them.
- Other connected trackers are never read or merged for CW or for Details' next/resume offer.
- Scrobbling still goes to every connected tracker, so they stay in sync. "Remove partial watch" still clears every connected tracker. Tracker output never changes because of CW work.

## 2. Which episode to offer (one rule, one place)
For each show, using the selected source's watch events:
1. Take the most recent watch event. Ignore partials under 1%.
2. Translate it to the user's selected TVDB order, including split and combined episodes. Each part has its own "count as watched" threshold.
3. If it was a partial watch: offer to resume that episode.
4. If it was a completed watch: offer the next episode.
5. "Up Next From Furthest Episode" on: use the furthest completed episode instead of the most recent.
6. The next episode hasn't aired: show it as upcoming, per the existing setting. The show is finished: no card.
7. Existing user rules then apply: removed items, days cap, dismissed next-up.
CW and Details use this same rule from the same code. They can never disagree.

## 3. Unwatch and removals
- Unwatch, "Remove partial watch" and "Remove last watch" delete watch events. The offer is then simply worked out again with the same rule.
- Removing a show from CW hides it until new watch activity happens.

## 4. What the user sees (no flicker)
- On app start and Home entry, show the cached CW exactly as it was when the app was last closed.
- Do all refreshing and lookups in the background without changing what's on screen.
- When background work finishes for a show, update its card once, and only if the answer differs.
- Never show intermediate results. Never replace a resolved episode with the "Open series to choose an episode" placeholder while work is in progress.
- The placeholder is only for a show whose next episode genuinely can't be determined. It's replaced as soon as the episode can be.

## 5. When progress refreshes
- Refresh only on: app start, return to the app, entering Home from another screen, after playback ends (Nuvio's player or an external player), and on manual refresh.
- No background timers.
- A refresh that finds no changed watch history changes nothing on screen.

## 6. What must never happen
- A show the user is watching disappears from CW because of a missing or invalid TVDB key, a timeout, slow metadata or a failed refresh.
- CW offers an episode the user has already finished as "next".
- A card flicks between states.
- CW work changes what is sent to any tracker.
- Testing uses the maintainer's real tracker accounts. Use fixtures, or the "Test" profile with no trackers connected.
