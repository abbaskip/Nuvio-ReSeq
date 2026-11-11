package com.nuvio.tv.livetv.sports

/** Reuse TV Now's small current-airing snapshot for live fixtures, including during guide imports. */
internal object SportsCurrentGuide {
    data class FixtureKey(val id: String, val leagueId: String, val startMs: Long, val endMs: Long?,
        val title: String, val teams: List<TeamInfo>)

    private fun hasCurrentStatus(event: SportsEvent): Boolean = event.source != EventSource.GUIDE &&
        (event.state == GameState.LIVE || (event.state == GameState.OFF &&
            SportsCatalog.league(event.leagueId)?.sport == Sport.CRICKET &&
            event.status in setOf("Stumps", "Awaiting update", "In progress", "Rain Delay", "RainDelay", "Suspended")))

    fun fixtures(events: List<SportsEvent>): List<SportsEvent> =
        events.filter(::hasCurrentStatus)

    /** Score/clock updates redraw from the fixture; they do not trigger another programme read. */
    fun fixtureKeys(events: List<SportsEvent>): List<FixtureKey> = fixtures(events).map {
        FixtureKey(it.id, it.leagueId, it.startMs, it.endMs, it.title, it.sides.map { side -> side.team })
    }

    /** A fixture can identify a sports airing even when its title contains only the participants. */
    fun additionalAirings(airings: List<SportsEvent>, fixtures: List<SportsEvent>,
        matches: Map<String, List<ChannelMatch>>, now: Long): List<SportsEvent> {
        val existing = airings.flatMap { it.guideHits }.toSet()
        val proven = fixtures.flatMap { fixture -> matches[fixture.id].orEmpty().mapNotNull { match ->
            val p = match.program ?: return@mapNotNull null
            val hit = match.channel.key to p
            if (!match.sure || p.startMs > now || p.stopMs <= now || hit in existing) null else Triple(fixture, hit, match.score)
        } }.groupBy { it.second }.values.mapNotNull { candidates ->
            val best = candidates.maxOf { it.third }
            candidates.filter { it.third == best }.distinctBy { it.first.id }.singleOrNull()
        }
        return proven.groupBy { (fixture, hit, _) -> listOf(fixture.id, hit.second.startMs, hit.second.stopMs, hit.second.title) }
            .values.map { same ->
                val fixture = same.first().first
                val p = same.first().second.second
                fixture.copy(id = "guide:fixture:${fixture.id}:${p.startMs}:${p.title.hashCode()}",
                    source = EventSource.GUIDE, startMs = p.startMs, endMs = p.stopMs, hasTime = true,
                    title = p.title, subtitle = p.description, status = null, home = null, away = null, espnId = null,
                    guideHits = same.map { it.second }, standings = emptyList())
            }
    }

    fun mergeMatches(previous: Map<String, List<ChannelMatch>>, owned: Map<String, List<ChannelMatch>>,
        fresh: Map<String, List<ChannelMatch>>, settings: SportsSettings): Map<String, List<ChannelMatch>> {
        val result = previous.toMutableMap()
        for ((id, old) in owned) {
            val remaining = result[id].orEmpty().filterNot { match ->
                match.score <= 100 && old.any { it.channel.key == match.channel.key && it.program == match.program }
            }
            if (remaining.isEmpty()) result.remove(id) else result[id] = remaining
        }
        val rank = SportsMatcher.preferenceRank(settings)
        for ((id, matches) in fresh) {
            val byChannel = result[id].orEmpty().associateBy { it.channel.key }.toMutableMap()
            for (match in matches) {
                val old = byChannel[match.channel.key]
                byChannel[match.channel.key] = if (old != null && old.score > match.score)
                    old.copy(program = match.program ?: old.program) else match
            }
            result[id] = SportsMatcher.sortMatches(byChannel.values.toList(), settings.bestChannelFirst, rank)
        }
        return result
    }

    /** A programme match is evidence; sharing only a broadcaster is not. Keep ambiguous airings unlinked. */
    fun withFixtures(airings: List<SportsEvent>, fixtures: List<SportsEvent>,
        matches: Map<String, List<ChannelMatch>>, now: Long): List<SportsEvent> {
        val byProgramme = fixtures.asSequence().filter(::hasCurrentStatus)
            .flatMap { fixture -> matches[fixture.id].orEmpty().asSequence().filter { match ->
                match.sure && match.program?.let { it.startMs <= now && now < it.stopMs } == true
            }.map { match -> (match.channel.key to match.program!!) to (fixture to match.score) } }
            .groupBy({ it.first }, { it.second })
        return airings.map { airing ->
            if (airing.isReplay) return@map airing
            val candidates = airing.guideHits.flatMap { byProgramme[it].orEmpty() }
                .groupBy { it.first.id }.map { (_, same) -> same.maxBy { it.second } }
            val strongest = candidates.maxOfOrNull { it.second }
            val fixture = candidates.filter { it.second == strongest }.singleOrNull()?.first ?: return@map airing
            // Programme IDs/times and GUIDE provenance remain stable for TV Now, picks and reminders.
            airing.copy(leagueId = fixture.leagueId, title = fixture.title, subtitle = fixture.subtitle,
                status = fixture.status, away = fixture.away, home = fixture.home, espnId = fixture.espnId,
                progress = fixture.progress, standings = fixture.standings, note = fixture.note,
                updatedAt = fixture.updatedAt, state = fixture.state)
        }
    }
}
