package com.nuvio.tv.livetv.sports

internal fun nextSportsReminder(events: List<SportsEvent>, f: SportsFollows, s: SportsSettings,
    alreadyShown: Set<String>, now: Long, lead: Long): SportsEvent? {
    val manual = f.reminders.mapNotNull { r -> events.firstOrNull { it.id == r.eventId } }
    val auto = if (s.remindMyTeams) events.filter { ev ->
        ev.state == GameState.PRE && ev.hasTime && ev.source != EventSource.GUIDE &&
            (ev.leagueId in f.leagues || ev.sides.any { f.followsTeam(it.team) } ||
                (ev.leagueId in f.pins && SportsCatalog.league(ev.leagueId)?.kind == LeagueKind.EVENT))
    } else emptyList()
    return (manual + auto).distinctBy { it.id }.firstOrNull { ev ->
        ev.id !in alreadyShown && ev.hasTime && now >= ev.startMs - lead - 30_000 && now < ev.startMs + 10 * 60_000L
    } ?: run {
        // A reminder whose game isn't in today's feed any more: still remind at its time.
        f.reminders.firstOrNull { r -> r.eventId !in alreadyShown && events.none { it.id == r.eventId } && now >= r.startMs - lead && now < r.startMs + 10 * 60_000L }
            ?.let { r ->
                SportsEvent(r.eventId, SportsMatcher.OTHER_ID, r.startMs, true, GameState.PRE, r.title, null, null, null, null)
            }
    }
}
