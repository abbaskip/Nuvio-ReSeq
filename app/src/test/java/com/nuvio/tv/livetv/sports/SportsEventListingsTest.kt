package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class SportsEventListingsTest {
    private val day = 10 * 86_400_000L
    private val hour = 3_600_000L
    private val now = day + 12 * hour

    private fun event(id: String, at: Long, state: GameState, source: EventSource = EventSource.ESPN) =
        SportsEvent(id, "basketball/nba", at, true, state, id, null, null, null, null, source = source)

    @Test fun `timeline opens on first live event and retains finished and delayed fixtures`() {
        val fixtures = listOf(
            event("result", now - 3 * hour, GameState.POST),
            event("delayed", now - 2 * hour, GameState.PRE),
            event("live", now - hour, GameState.LIVE),
            event("later-live", now, GameState.LIVE),
            event("upcoming", now + hour, GameState.PRE))
        val timeline = SportsEventListings.allEvents(fixtures.reversed(), day, day + 24 * hour, now)
        assertEquals(1, SportsEventListings.entryIndex(timeline))
        assertEquals(listOf(fixtures[0], fixtures[2], fixtures[3], fixtures[1], fixtures[4]), timeline)
    }

    @Test fun `timeline opens on upcoming event when none are live and handles past or empty days`() {
        val result = event("result", now - hour, GameState.POST)
        val postponed = event("postponed", now, GameState.OFF)
        val upcoming = event("upcoming", now + hour, GameState.PRE)
        assertEquals(2, SportsEventListings.entryIndex(listOf(result, postponed, upcoming)))
        assertEquals(0, SportsEventListings.entryIndex(listOf(result, postponed)))
        assertEquals(0, SportsEventListings.entryIndex(emptyList()))
    }

    @Test fun `only provider fixtures appear in All Events even when guide show names a match`() {
        val events = EventSource.entries.map { source -> event(source.name, now, GameState.LIVE, source) }
        val result = SportsEventListings.allEvents(events, day, day + 24 * hour, now)
        assertEquals(setOf(EventSource.ESPN, EventSource.FIXTURES, EventSource.THESPORTSDB), result.map { it.source }.toSet())
        assertFalse(result.any { it.source == EventSource.GUIDE })
    }

    @Test fun `today groups finished live and upcoming and moves newly finished games before long running live sport`() {
        val team = TeamInfo("basketball/nba", "1", "Team", "Team", "TM", null, null, null, null)
        val finished = event("result", day + hour, GameState.POST).copy(
            away = Side(team, "101", null, false, false),
            home = Side(team.copy(id = "2"), "105", null, true, true))
        val upcoming = event("upcoming", now + hour, GameState.PRE, EventSource.THESPORTSDB)
        val live = event("live", now - hour, GameState.LIVE)
        val allDayLive = event("test-cricket", day - 48 * hour, GameState.LIVE).copy(
            leagueId = "guide/cricket", hasTime = false, endMs = day + 18 * hour)
        // Start time determines order within each status, regardless of sport or provider.
        val laterLive = event("later-live", now, GameState.LIVE, EventSource.FIXTURES).copy(leagueId = "racing/f1")
        val fixtures = listOf(upcoming, laterLive, live, allDayLive, finished)
        val result = SportsEventListings.allEvents(fixtures, day, day + 24 * hour, now)
        assertEquals(listOf(finished, allDayLive, live, laterLive, upcoming), result)
        assertEquals(listOf("101", "105"), result.first().sides.map { it.score })
        assertTrue(result.first().hasScore)
        assertSame(finished, result.first()) // Original IDs/data still open the normal details page.
        val newlyFinished = live.copy(state = GameState.POST)
        val updated = fixtures.map { if (it.id == live.id) newlyFinished else it }
        assertEquals(listOf(finished, newlyFinished, allDayLive, laterLive, upcoming),
            SportsEventListings.allEvents(updated, day, day + 24 * hour, now))
    }

    @Test fun `date navigation excludes neighbouring fixtures but retains overnight live event today`() {
        val yesterday = event("yesterday", day - hour, GameState.POST)
        val overnight = event("overnight", day - hour, GameState.LIVE)
        val tomorrow = event("tomorrow", day + 24 * hour, GameState.PRE)
        val today = event("today", day, GameState.PRE)
        val events = listOf(tomorrow, yesterday, today, overnight)
        assertEquals(listOf(overnight, today), SportsEventListings.allEvents(events, day, day + 24 * hour, now))
        assertEquals(listOf(tomorrow), SportsEventListings.allEvents(events, day + 24 * hour, day + 48 * hour, now))
    }

    @Test fun `multi day event remains on today and appears only once as a result`() {
        val tournament = event("tournament", day - 48 * hour, GameState.LIVE).copy(hasTime = false, endMs = day + hour * 18)
        assertEquals(listOf(tournament), SportsEventListings.allEvents(listOf(tournament), day, day + 24 * hour, now))
        val finished = tournament.copy(state = GameState.POST, endMs = now - hour)
        assertEquals(listOf(finished), SportsEventListings.allEvents(listOf(finished), day, day + 24 * hour, now))
        assertTrue(SportsEventListings.allEvents(listOf(finished), day + 24 * hour, day + 48 * hour, now).isEmpty())
    }

    @Test fun `TV Now uses actual airing intervals including replays instead of cached status`() {
        fun airing(id: String, start: Long, stop: Long, state: GameState) =
            event(id, start, state, EventSource.GUIDE).copy(
                guideHits = listOf("channel" to EpgProgram(start, stop, id)), endMs = stop)
        val finished = airing("finished", now - hour, now, GameState.LIVE)
        val upcoming = airing("upcoming", now + 1, now + hour, GameState.LIVE)
        val replay = airing("Replay", now - hour, now + hour, GameState.PRE)
        val starting = airing("show", now, now + hour, GameState.PRE)
        val fixture = event("live-fixture", now, GameState.LIVE)
        assertEquals(listOf(replay, starting), SportsEventListings.tvNow(listOf(starting, upcoming, fixture, finished, replay), now))
        assertTrue(replay.isReplay)
    }

    @Test fun `gaps between merged airings are not treated as on air`() {
        val show = event("merged", now - 2 * hour, GameState.LIVE, EventSource.GUIDE).copy(
            endMs = now + 2 * hour,
            guideHits = listOf(
                "first" to EpgProgram(now - 2 * hour, now - hour, "show"),
                "second" to EpgProgram(now + hour, now + 2 * hour, "show")))
        assertTrue(SportsEventListings.tvNow(listOf(show), now).isEmpty())
        assertEquals(listOf(show), SportsEventListings.tvNow(listOf(show), now + hour))
    }
}
