package com.nuvio.tv.livetv.sports

import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.test.runTest
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
        assertTrue(table.conferences.isEmpty())
    }

    @Test fun collegeFootballKeepsFlatConferencesAlongsideDivisionsAndReadsOverallRecords() {
        val league = SportsCatalog.league("football/college-football")!!
        val table = Standings.parseEspn(league, fixture("college-football"))!!
        assertEquals(listOf("American Conference", "Sun Belt Conference"), table.conferences)
        val utsa = table.view(StandingsView.DIVISION, "American Conference").single().rows.single()
        assertEquals("8", utsa.values["wins"])
        assertEquals("2", utsa.values["losses"])
        assertEquals(listOf("Sun Belt - East", "Sun Belt - West"),
            table.view(StandingsView.DIVISION, "Sun Belt Conference").map { it.name })
        assertEquals(2, table.view(StandingsView.CONFERENCE, "Sun Belt Conference").single().rows.size)
        assertEquals(3, table.view(StandingsView.LEAGUE, null).single().rows.size)
    }

    @Test fun flatGroupsKeepOnlyTheSelectedConferenceAndIgnoreSplitsInEitherOrder() {
        val league = SportsCatalog.league("basketball/mens-college-basketball")!!
        val raw = """{"children":[
            {"name":"Big Ten","standings":{"entries":[{"team":{"id":"1","name":"Team A"},"stats":[
                {"name":"wins","type":"home_wins","displayValue":"2"},
                {"name":"wins","type":"wins","displayValue":"4"},
                {"name":"losses","type":"losses","displayValue":"1"},
                {"name":"losses","type":"vsconf_losses","displayValue":"0"}]}]}},
            {"name":"SEC","standings":{"entries":[{"team":{"id":"2","name":"Team B"}}]}}
        ]}"""
        val table = Standings.parseEspn(league, raw)!!
        assertFalse(table.hasDivisions)
        assertEquals(listOf("Big Ten", "SEC"), table.conferences)
        val row = table.view(StandingsView.DIVISION, null).single().rows.single()
        assertEquals("4", row.values["wins"])
        assertEquals("1", row.values["losses"])
        assertEquals("2", table.view(StandingsView.DIVISION, "SEC").single().rows.single().id)
    }

    @Test fun collegeDivisionDetailKeepsTeamsMissingFromTheConferenceFeed() = runTest {
        val league = SportsCatalog.league("baseball/college-baseball")!!
        val division = """{"id":"1","name":"NCAA Division I","standings":{"entries":[
            {"team":{"id":"a","name":"A"}},{"team":{"id":"b","name":"B"}}]}}"""
        val raw = """{"children":[$division]}"""
        val detail = JSONObject(raw)
        val wrapper = detail.getJSONArray("children").getJSONObject(0)
        wrapper.put("children", JSONArray("""[{"name":"Big 12","standings":{"entries":[{"team":{"id":"a","name":"A"}}]}}]"""))
        val table = Standings.parseEspn(league, Standings.withCollegeConferences(raw) { detail.toString() })!!
        assertEquals(listOf("Big 12", "Other teams · NCAA Division I"), table.conferences)
        assertFalse(table.hasDivisions)
        assertEquals("a", table.view(StandingsView.DIVISION, "Big 12").single().rows.single().id)
        assertEquals("b", table.view(StandingsView.DIVISION, "Other teams · NCAA Division I").single().rows.single().id)
        assertEquals(raw, Standings.withCollegeConferences(raw) { throw java.io.IOException("offline") })
    }

    private fun entries(node: JSONObject): List<JSONObject> {
        val own = node.optJSONObject("standings")?.optJSONArray("entries")
        if (own != null) return (0 until own.length()).map { own.getJSONObject(it) }
        val children = node.optJSONArray("children") ?: return emptyList()
        return (0 until children.length()).flatMap { entries(children.getJSONObject(it)) }
    }
}
