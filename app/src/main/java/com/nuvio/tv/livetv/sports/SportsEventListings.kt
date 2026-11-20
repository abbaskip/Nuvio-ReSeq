package com.nuvio.tv.livetv.sports

internal fun isCollegeBasketball(leagueId: String): Boolean =
    leagueId == "basketball/mens-college-basketball" || leagueId == "basketball/womens-college-basketball"

/** ESPN can still report a scheduled tip-off while a college scoreboard is catching up. */
internal fun SportsEvent.awaitingCollegeStatus(now: Long): Boolean =
    source == EventSource.ESPN && state == GameState.PRE && hasTime && now - startMs >= 60_000L &&
        isCollegeBasketball(leagueId)

/** Fixtures and TV airings have separate timelines; channel matching never sets event order. */
internal object SportsEventListings {
    fun guideAiring(event: SportsEvent, now: Long): SportsEvent? {
        val end = event.endMs ?: event.guideHits.maxOfOrNull { it.second.stopMs }
        if (end != null && now >= end) return null
        return event.copy(state = if (now >= event.startMs) GameState.LIVE else GameState.PRE,
            status = null, awaitingUpdate = false)
    }

    fun display(events: List<SportsEvent>, now: Long): List<SportsEvent> = events.mapNotNull {
        if (it.source == EventSource.GUIDE) guideAiring(it, now) else it
    }

    fun onMyChannels(events: List<SportsEvent>, matches: Map<String, List<ChannelMatch>>,
        requested: Boolean, ready: Boolean, isMine: (SportsEvent) -> Boolean): List<SportsEvent> {
        if (!requested || !ready || matches.isEmpty()) return events
        return events.filter { it.state == GameState.POST || isMine(it) || matches[it.id]?.isNotEmpty() == true }
    }

    /** Enter the timeline at live sport, leaving earlier results available above it. */
    fun entryIndex(events: List<SportsEvent>): Int =
        events.indexOfFirst { it.state == GameState.LIVE }.takeIf { it >= 0 }
            ?: events.indexOfFirst { it.state == GameState.PRE }.coerceAtLeast(0)

    fun allEvents(events: List<SportsEvent>, dayStart: Long, dayEnd: Long, now: Long): List<SportsEvent> =
        events.filter { ev ->
            ev.source != EventSource.GUIDE && when {
                ev.state == GameState.POST -> {
                    val finished = if (ev.hasTime) ev.startMs else minOf(ev.endMs ?: ev.startMs, now)
                    finished in dayStart until dayEnd
                }
                // Keep a game that started yesterday visible while it is still live today.
                ev.state == GameState.LIVE -> ev.startMs < dayEnd &&
                    (ev.startMs >= dayStart || (now in dayStart until dayEnd && (ev.endMs ?: Long.MAX_VALUE) > now))
                else -> ev.startMs in dayStart until dayEnd ||
                    (SportsCatalog.league(ev.leagueId)?.sport == Sport.CRICKET &&
                        ev.startMs < dayEnd && (ev.endMs ?: ev.startMs) > dayStart)
            }
        }.sortedWith(compareBy<SportsEvent> {
            // Today's continuous feed follows status, even when a live event spans days.
            // Recompute from each provider update so a newly finished game moves above live sport.
            if (now in dayStart until dayEnd) when (it.state) {
                GameState.POST -> 0
                GameState.LIVE -> 1
                GameState.PRE -> if (it.awaitingCollegeStatus(now)) {
                    val expectedEnd = it.endMs ?: SportsCatalog.league(it.leagueId)?.let { league ->
                        it.startMs + league.minutes * 60_000L
                    }
                    if (expectedEnd != null && now >= expectedEnd) 0 else 1
                } else 2
                GameState.OFF -> 2
            } else 0
        }.thenBy { it.startMs }.thenBy { it.id })

    /** Use programme boundaries, so cached LIVE flags cannot keep an ended show on TV Now. */
    fun tvNow(events: List<SportsEvent>, now: Long): List<SportsEvent> =
        events.filter { ev ->
            ev.source == EventSource.GUIDE && if (ev.guideHits.isNotEmpty()) {
                ev.guideHits.any { (_, program) -> now >= program.startMs && now < program.stopMs }
            } else {
                ev.startMs <= now && ev.endMs?.let { now < it } == true
            }
        }.sortedWith(compareBy<SportsEvent> { it.startMs }.thenBy { it.id })
}
