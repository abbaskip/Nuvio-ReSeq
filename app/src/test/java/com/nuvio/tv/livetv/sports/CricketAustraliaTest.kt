package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CricketAustraliaTest {
    private val now = Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()
    private fun fixture(status: String, live: Boolean = false, format: Int = 1, competition: String = "Pakistan v Sri Lanka Tests") =
        """{"fixtures":[{"id":40986,"gameTypeId":$format,"competition":{"name":"$competition"},
        "startDateTime":"2026-11-09T04:30:00Z","endDateTime":"2026-11-13T11:00:00Z",
        "gameStatusId":"$status","gameStatus":"$status","isLive":$live,"isInProgress":true,
        "isCompleted":false,"matchDay":2,"homeTeam":{"id":11,"name":"Pakistan Men","shortName":"PAK"},
        "awayTeam":{"id":9,"name":"Sri Lanka Men","shortName":"SL"},
        "innings":[{"inningNumber":1,"battingTeamId":11,"runsScored":434,"numberOfWicketsFallen":9,"isDeclared":true,"oversBowled":"106.3"}]}]}"""

    @Test fun `stumps keeps scores and multi-day fixture but is never live`() {
        val event = CricketAustralia.parse(fixture("Stumps", true), now).single()
        assertEquals(GameState.OFF, event.state)
        assertTrue(event.hasScore)
        assertEquals("434/9d (106.3 ov)", event.home?.score)
        val start = Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()
        assertEquals(listOf(event), SportsEventListings.allEvents(listOf(event), start, start + 86_400_000, now))
    }
    @Test fun `play and normal session breaks stay live`() {
        for (status in listOf("InPlay", "Drinks", "Lunch", "Tea", "InningsBreak"))
            assertEquals(status, GameState.LIVE, CricketAustralia.parse(fixture(status), now).single().state)
        assertEquals(GameState.OFF, CricketAustralia.parse(fixture("RainDelay", true), now).single().state)
    }
    @Test fun `domestic coverage excludes BBL and unrelated first class competitions`() {
        assertEquals("ca/shield", CricketAustralia.parse(fixture("Stumps", format = 4, competition = "Sheffield Shield 2026-27"), now).single().leagueId)
        assertEquals("ca/one-day", CricketAustralia.parse(fixture("InPlay", format = 5, competition = "One-Day Cup 2026-27"), now).single().leagueId)
        assertTrue(CricketAustralia.parse(fixture("InPlay", format = 6, competition = "KFC BBL"), now).isEmpty())
        assertTrue(CricketAustralia.parse(fixture("InPlay", format = 4, competition = "County Championship"), now).isEmpty())
        assertEquals(300_000L, CricketAustralia.interval(1))
        assertEquals(900_000L, CricketAustralia.interval(4))
        assertTrue(SportsCompetitions.hasTeams("ca/shield"))
        assertTrue(SportsCompetitions.hasTeams("ca/one-day"))
    }
    @Test fun `secondary status preserves primary IDs and does not duplicate the match`() {
        val ca = CricketAustralia.parse(fixture("Stumps"), now).single()
        val original = ca.copy(id = "espn:123", leagueId = "cricket/19430", source = EventSource.ESPN, state = GameState.LIVE)
        val result = CricketAustralia.merge(listOf(original), listOf(ca)).single()
        assertEquals(original.id, result.id)
        assertEquals(original.source, result.source)
        assertEquals(GameState.OFF, result.state)
        assertEquals("Stumps", result.status)
        assertEquals(2, CricketAustralia.merge(listOf(original.copy(startMs = original.startMs - 7 * 86_400_000)), listOf(ca)).size)
    }
    @Test(expected = java.io.IOException::class) fun `error response cannot erase cached fixtures`() {
        CricketAustralia.parse("""{"responseError":"unavailable"}""", now)
    }
}
