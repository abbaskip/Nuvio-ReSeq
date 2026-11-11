package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class InternationalCricketTest {
    private val australia = InternationalCricket.teams.single { it.name == "Australia" }
    private val follows = SportsFollows(teams = listOf(FollowedTeam(australia.leagueId,
        australia.id, australia.name, australia.abbr, null, null)))
    private fun team(name: String, league: String = "cricket/19430") =
        TeamInfo(league, name.hashCode().toString(), name, name, "AUS", null, null, null, null)

    @Test fun `picker offers twelve unique countries without a network feed`() {
        assertEquals(12, InternationalCricket.teams.size)
        assertEquals(12, InternationalCricket.teams.map { it.id }.distinct().size)
        assertTrue(SportsCompetitions.hasTeams(australia.leagueId))
        assertEquals(1, SportsCatalog.browseOrdered(SportsSettings()).count { it.id == australia.leagueId })
    }

    @Test fun `one saved country matches all formats with different provider IDs`() {
        val restored = SportsFollows.fromJson(follows.toJson())
        listOf("Australia", "Australia Test", "Australia ODI", "Australia T20", "Australia (T20I)").forEach {
            assertTrue(it, restored.followsTeam(team(it)))
        }
        assertEquals(1, SportsRows.rowKeys(restored).size)
    }

    @Test fun `other countries sports and representative sides remain separate`() {
        listOf("England", "Australia Women", "Australia A", "Australia Under 19", "Australia Legends").forEach {
            assertFalse(it, follows.followsTeam(team(it)))
        }
        assertFalse(follows.followsTeam(team("Australia", "soccer/fifa.world")))
    }

    @Test fun `country follow loads test odi and t20 competitions`() {
        val related = SportsCatalog.relatedLeagues(australia.leagueId)
        assertTrue("cricket/19430" in related)
        assertTrue(related.any { it.contains("odi-internationals") })
        assertTrue(related.any { it.contains("t20-internationals") })
        assertFalse("cricket/8048" in related)
    }
    @Test fun `format fixtures share one My Sports country row`() {
        val events = listOf("Australia Test", "Australia ODI", "Australia T20").mapIndexed { i, name ->
            SportsEvent("match-$i", "cricket/19430", 1000L + i, true, GameState.PRE,
                name, null, null, Side(team(name), null, null, false, false), null)
        }
        val page = SportsRows.build(events, follows, 0) { isMine(it, follows) }
        assertEquals(3, page.heroes.size)
        val row = page.rows.single { it.kind == SportsRow.Kind.TEAM }
        assertEquals("Australia", row.title)
        assertEquals(events, row.events)
    }
}
