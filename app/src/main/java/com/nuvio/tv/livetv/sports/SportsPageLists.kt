package com.nuvio.tv.livetv.sports

internal data class SportsPageRequest(val day: Int, val chip: String?, val openId: String?)

internal data class SportsPageFilters(
    val settings: SportsSettings,
    val follows: SportsFollows,
    val active: Set<String>,
    val followedSports: List<Sport>
) {
    fun chip(saved: String?): String {
        val default = if (follows.isEmpty) CHIP_ALL else CHIP_MINE
        if (saved == null || saved == CHIP_LIVE || saved.startsWith(CHIP_LEAGUE)) return default
        if (saved.startsWith(CHIP_SPORT) && followedSports.none { CHIP_SPORT + it.name == saved }) return default
        return if (Sport.entries.any { it.name == saved }) default else saved
    }
}

internal class SportsPageLists(
    val request: SportsPageRequest,
    val filters: SportsPageFilters,
    val events: List<SportsEvent>,
    val known: List<SportsEvent>,
    val matches: Map<String, List<ChannelMatch>>,
    val dayEvents: List<SportsEvent>,
    val shown: List<SportsEvent>,
    val liveCount: Int,
    val entryIndex: Int,
    val teams: MyTeamsPage?,
    val opened: SportsEvent?,
    val playing: SportsEvent?,
    val playingKey: String?
) {
    companion object {
        fun build(request: SportsPageRequest, filters: SportsPageFilters,
            rawEvents: List<SportsEvent>, rawKnown: List<SportsEvent>, matches: Map<String, List<ChannelMatch>>,
            now: Long, start: Long, end: Long, hasChannels: Boolean, matched: Boolean, playingKey: String?): SportsPageLists {
            val events = SportsEventListings.display(rawEvents, now)
            val known = SportsEventListings.display(rawKnown, now)
            val (settings, follows, active, sports) = filters
            val chip = filters.chip(request.chip)
            val mine = { event: SportsEvent -> isMine(event, follows) }
            val fixtures = SportsEventListings.allEvents(known, start, end, now)
                .filter { it.leagueId in active || mine(it) }
            val dayEvents = SportsEventListings.onMyChannels(fixtures, matches, settings.onlyOnMyChannels && hasChannels, matched, now, mine)
            val shown = if (chip == CHIP_TV_NOW) SportsCurrentGuide.withFixtures(SportsEventListings.tvNow(events, now),
                SportsCurrentGuide.fixtures(events).filter { it.leagueId in active }, matches, now).filter { it.leagueId in active }
                else dayEvents
            val sport = sports.firstOrNull { CHIP_SPORT + it.name == chip }
            val teams = if (chip == CHIP_MINE || sport != null) SportsRows.build(known, follows, now, sport, mine) else null
            val opened = request.openId?.let { id -> known.firstOrNull { it.id == id } }?.let { event ->
                if (event.source == EventSource.GUIDE)
                    SportsCurrentGuide.withFixtures(listOf(event), SportsCurrentGuide.fixtures(known), matches, now).single()
                else event
            }
            val playing = if (playingKey == null) null else events.asSequence()
                .filter { event -> matches[event.id]?.any { it.channel.key == playingKey } == true }
                .minByOrNull { if (it.state == GameState.LIVE) 0 else if (it.state == GameState.PRE) 1 else 2 }
            return SportsPageLists(request, filters, events, known, matches, dayEvents, shown,
                dayEvents.count { it.state == GameState.LIVE }, SportsEventListings.entryIndex(shown, now), teams, opened, playing, playingKey)
        }
    }
}
