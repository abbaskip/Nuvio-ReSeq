package com.nuvio.tv.livetv.sports

/** Fixtures and TV airings have separate timelines; channel matching never sets event order. */
internal object SportsEventListings {
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
                else -> ev.startMs in dayStart until dayEnd
            }
        }.sortedWith(compareBy<SportsEvent> { it.startMs }.thenBy { it.id })

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
