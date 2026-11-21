package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.sports.*

/** A timetable covers its own competition, including airings well outside the live window. */
internal class CalendarFixtureBroadcasts(
    fixtures: List<SportsEvent>,
    private val league: (SportsEvent) -> League?,
    schedule: List<SportsEvent> = fixtures
) {
    private val byCompetition = fixtures.groupBy { competition(it) }
    private val covered = schedule.asSequence().filter { it.source != EventSource.GUIDE }.map(::competition).toSet()
    private val phases = fixtures.filter { league(it)?.sport == Sport.RACING }
        .associate { it.id to CalendarLiveBroadcasts.phase(it.title) }
    private val providerCoverage = mutableMapOf<Pair<String, String>, Boolean>()

    private fun competition(event: SportsEvent): String {
        val l = league(event)
        if (l?.sport == Sport.RACING) {
            // Some guides file F1 Academy under F1. The series named in the title wins.
            val series = CalendarLiveBroadcasts.seriesId(event.title) ?: CalendarLiveBroadcasts.seriesId(l.name)
            if (series != null) return "racing/" + series
        }
        return SportsCompetitions.canonicalId(event.leagueId)
    }

    fun fixturesFor(air: SportsEvent) = byCompetition[competition(air)].orEmpty()
    fun covers(air: SportsEvent): Boolean {
        val key = competition(air)
        if (key in covered) return true
        // A failed or still-loading ESPN request must not turn reruns into a second timetable.
        // Catalogue-only TheSportsDB leagues can have no fixtures at all, so use actual
        // schedule evidence there. A support series can also share its parent's guide league ID.
        return providerCoverage.getOrPut(air.leagueId to key) {
            SportsCompetitions.sources(air.leagueId).any { source ->
                source.espnPath != null && (source.sport != Sport.RACING ||
                    key == (CalendarLiveBroadcasts.seriesId(source.name)?.let { "racing/" + it }
                        ?: SportsCompetitions.canonicalId(source.id)))
            }
        }
    }

    fun match(air: SportsEvent, programme: EpgProgram, fixtures: List<SportsEvent>): SportsEvent? {
        val near = fixtures.filter { fixture ->
            fixture.hasTime && programme.startMs - fixture.startMs in -EARLY_COVERAGE_MS..15 * MINUTE &&
                programme.stopMs > fixture.startMs
        }
        if (near.isEmpty()) return null
        if (league(air)?.sport == Sport.RACING) {
            val phase = CalendarLiveBroadcasts.phase(programme.title) ?: CalendarLiveBroadcasts.phase(programme.description.orEmpty())
            return near.singleOrNull { fixture ->
                phase == null || phases[fixture.id]?.compatible(phase) == true
            }
        }
        val listing = air.copy(startMs = programme.startMs, title = programme.title, subtitle = programme.description)
        return near.singleOrNull { fixture ->
            val l = league(fixture) ?: return@singleOrNull false
            if (fixture.sides.size == 2) SportsEventIdentity.guideCoversFixture(listing, fixture, l)
            else SportsCatalog.norm(fixture.title).trim().let { name ->
                name.length >= 4 && SportsCatalog.hasWord(SportsCatalog.norm(programme.title + " " + programme.description.orEmpty()), name)
            }
        }
    }

    companion object {
        private const val MINUTE = 60_000L
        const val EARLY_COVERAGE_MS = 90 * MINUTE

        fun withoutRaceDuplicates(fixtures: List<SportsEvent>, league: (SportsEvent) -> League?): List<SportsEvent> {
            val sessions = fixtures.filter { it.source == EventSource.ESPN && ":session:" in it.id }
            if (sessions.isEmpty()) return fixtures
            val parents = sessions.mapTo(HashSet()) { it.id.substringBeforeLast(":session:") }
            val index = CalendarFixtureBroadcasts(sessions, league)
            return fixtures.filterNot { event -> event.id in parents ||
                event.source == EventSource.THESPORTSDB && league(event)?.sport == Sport.RACING &&
                event.hasTime && index.fixturesFor(event).any { session ->
                    val phase = CalendarLiveBroadcasts.phase(event.title)
                    kotlin.math.abs(event.startMs - session.startMs) <= 5 * MINUTE &&
                        (phase == null || index.phases[session.id]?.compatible(phase) == true)
                }
            }
        }
    }
}
