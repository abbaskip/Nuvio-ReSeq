package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.graphics.luminance
import java.time.Instant

class CricketAustraliaTest {
    @Test fun `state colours cover both domestic feeds and preserve other competitions`() {
        val names = listOf("New South Wales", "Queensland Bulls", "Victoria", "South Australia", "Western Australia", "Tasmania Tigers")
        val colors = listOf(0xFF6CACE4L, 0xFF7A0019L, 0xFF002B5CL, 0xFFE4002BL, 0xFFFFC72CL, 0xFF00843DL)
        for (id in listOf("ca/shield", "ca/one-day", "cricket/espn-test")) {
            for (competition in listOf("Sheffield Shield", "One-Day Cup")) {
                val league = League(id, Sport.CRICKET, competition, "TEST")
                for ((i, name) in names.withIndex()) for (suffix in listOf("", " Women")) {
                    val team = TeamInfo(id, "$i", name + suffix, name + suffix, "", null, null, null, null)
                    assertEquals(colors[i], stateCricketColor(team, league))
                    if (id.startsWith("ca/")) {
                        val band = teamBandColor(team)
                        assertTrue("White band text must meet 4.5:1 contrast", 1.05f / (band.luminance() + 0.05f) >= 4.5f)
                    }
                }
            }
        }
        val other = TeamInfo("cricket/19430", "1", "Victoria", "Victoria", "VIC", null, null, null, null)
        assertNull(stateCricketColor(other))
        assertNull(stateCricketColor(other.copy(leagueId = "ca/shield", name = "Pakistan", fullName = "Pakistan")))
    }

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

    @Test fun `My Sports retains Tests and Shield at stumps after their opening days`() {
        val today = Instant.parse("2026-11-12T10:00:00Z").toEpochMilli()
        val test = CricketAustralia.parse(fixture("Stumps"), today).single()
        val shield = CricketAustralia.parse(fixture("Stumps", format = 4, competition = "Sheffield Shield 2026-27"), today).single().copy(id = "ca:shield")
        val follows = SportsFollows(leagues = setOf(test.leagueId, shield.leagueId))
        val result = test.copy(id = "previous", state = GameState.POST, status = "Final", startMs = today - 86_400_000)
        val page = SportsRows.build(listOf(result, test, shield), follows, today, Sport.CRICKET) { true }

        assertEquals(setOf(test.id, shield.id), page.rows.first { it.key == "up" }.events.map { it.id }.toSet())
        assertEquals(setOf(test.id, shield.id), page.heroes.map { it.id }.toSet())
        assertEquals(1, page.rows.single { it.key == "l:${SportsCompetitions.canonicalId(test.leagueId)}" }.startIndex)
        for (event in listOf(test, shield)) {
            val row = page.rows.single { it.key == "l:${SportsCompetitions.canonicalId(event.leagueId)}" }
            assertEquals(event.id, row.events[row.startIndex].id)
            assertEquals(GameState.OFF, row.events[row.startIndex].state)
            assertTrue(row.events[row.startIndex].hasScore)
        }
    }

    @Test fun `My Sports does not promote cancelled finished or expired cricket to upcoming`() {
        val base = CricketAustralia.parse(fixture("Stumps"), now).single()
        val events = listOf(base.copy(id = "cancelled", status = "Cancelled"),
            base.copy(id = "finished", state = GameState.POST),
            base.copy(id = "expired", endMs = now),
            base.copy(id = "guide", source = EventSource.GUIDE),
            base.copy(id = "old", startMs = now - 6 * 86_400_000L, endMs = null))
        val page = SportsRows.build(events, SportsFollows(leagues = setOf(base.leagueId)), now) { true }
        assertTrue(page.heroes.isEmpty())
        assertTrue(page.rows.none { it.key == "up" })
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
        val refreshed = CricketAustralia.merge(listOf(original.copy(awaitingUpdate = true)), listOf(ca)).single()
        assertFalse(refreshed.awaitingUpdate)
        assertTrue(refreshed.hasScore)
    }
    @Test fun `real ESPN abbreviations merge with full CA country names`() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/sports/pakistan-sri-lanka-test.json"))
            .bufferedReader().use { it.readText() }
        val espn = SportsFeeds.parseScoreboard(SportsCatalog.league("cricket/19430")!!, raw).single()
        val ca = CricketAustralia.parse(fixture("Stumps"), now).single()
        assertEquals("PAK", espn.home!!.team.name)
        val merged = CricketAustralia.merge(listOf(espn), listOf(ca))
        assertEquals(1, merged.size)
        assertEquals(espn.id, merged.single().id)
        assertEquals("Stumps", merged.single().status)
        assertEquals("434/9d (106.3 ov)", merged.single().home!!.score)
        assertEquals("", compactCricketScore(merged.single().away!!))
        assertEquals("", cricketScoreText("null").runs)
    }

    @Test(expected = java.io.IOException::class) fun `error response cannot erase cached fixtures`() {
        CricketAustralia.parse("""{"responseError":"unavailable"}""", now)
    }
}
