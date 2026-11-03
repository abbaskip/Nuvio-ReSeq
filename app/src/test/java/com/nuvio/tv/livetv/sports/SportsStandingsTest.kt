package com.nuvio.tv.livetv.sports

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsStandingsTest {
    private val nba = SportsCatalog.league("basketball/nba")!!
    private val nrl = SportsCatalog.league("rugby-league/3")!!
    // Reduced public ESPN standings captured on 2 November 2026. NBA keeps one division
    // from each conference; the feed's original row order is deliberately retained.
    private fun fixture(name: String) = javaClass.getResource("/sports/$name-standings.json")!!.readText()

    @Test fun nbaDivisionsPutLeadersFirstAndNumberPlacesWithinEachDivision() {
        val table = Standings.parseEspn(nba, fixture("nba"))!!
        val groups = table.groups
        assertEquals(2, groups.size)
        for (group in groups) {
            assertEquals(group.rows.map { it.seed }.sortedBy { it }, group.rows.map { it.seed })
            assertEquals((1..group.rows.size).toList(), group.rows.map { it.pos })
        }
        assertEquals("Philadelphia 76ers", groups.first().rows.first().name)
        assertEquals("Brooklyn Nets", groups.first().rows.last().name)
        // This division is not simply reversed: Denver and Minnesota are out of order too.
        assertEquals(listOf("Portland Trail Blazers", "Oklahoma City Thunder", "Denver Nuggets",
            "Minnesota Timberwolves", "Utah Jazz"), groups.last().rows.map { it.name })
    }

    @Test fun nbaConferenceAndLeagueViewsStillPutLeadersFirst() {
        val table = Standings.parseEspn(nba, fixture("nba"))!!
        for (conference in table.conferences) {
            val rows = table.view(StandingsView.CONFERENCE, conference).single().rows
            assertEquals(rows.map { it.seed }.sortedBy { it }, rows.map { it.seed })
        }
        val league = table.view(StandingsView.LEAGUE, null).single().rows
        assertEquals(league.map { it.sortValue }.sortedDescending(), league.map { it.sortValue })
    }

    @Test fun nbaWithoutSeedsFallsBackToBestRecordFirst() {
        val root = JSONObject(fixture("nba"))
        entries(root).forEach { entry ->
            val stats = entry.getJSONArray("stats")
            for (i in stats.length() - 1 downTo 0) {
                if (stats.getJSONObject(i).getString("name") == "playoffSeed") stats.remove(i)
            }
        }
        val table = Standings.parseEspn(nba, root.toString())!!
        for (group in table.groups) {
            assertTrue(group.rows.all { it.seed == null })
            assertEquals(group.rows.map { it.sortValue }.sortedDescending(), group.rows.map { it.sortValue })
        }
    }

    @Test fun nrlUsesLadderPointsAndRugbyStatNamesRatherThanWinningPercentage() {
        val table = Standings.parseEspn(nrl, fixture("nrl"))!!
        assertEquals(StandingsKind.TABLE, table.kind)
        assertEquals(listOf("P", "W", "L", "D", "PD", "PTS"), table.columns.map { it.label })
        val leader = table.groups.single().rows.first()
        assertEquals(1, leader.pos)
        assertEquals("18", leader.values["wins"])
        assertEquals("6", leader.values["losses"])
        assertEquals("0", leader.values["ties"])
        assertEquals("+336", leader.values["pointDifferential"])
        assertEquals("42", leader.values["points"])
    }

    private fun entries(node: JSONObject): List<JSONObject> {
        val own = node.optJSONObject("standings")?.optJSONArray("entries")
        if (own != null) return (0 until own.length()).map { own.getJSONObject(it) }
        val children = node.optJSONArray("children") ?: return emptyList()
        return (0 until children.length()).flatMap { entries(children.getJSONObject(it)) }
    }
}
