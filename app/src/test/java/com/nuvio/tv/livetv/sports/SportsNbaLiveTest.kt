package com.nuvio.tv.livetv.sports

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsNbaLiveTest {
    private val nba = SportsCatalog.league("basketball/nba")!!
    private fun fixture(name: String) = javaClass.getResource("/sports/$name.json")!!.readText()

    @Test fun `opened Bulls game gets its final score and both teams box scores from one summary`() {
        val root = JSONObject(fixture("nuggets-bulls-summary"))
        val baseline = SportsFeeds.parseScoreboard(nba, fixture("nba-live-scoreboard")).single()
        val pending = baseline.copy(id = "espn:basketball/nba:401910032", espnId = "401910032",
            state = GameState.PRE, awaitingUpdate = true, home = baseline.home!!.let { it.copy(team = it.team.copy(id = "4"), score = null) },
            away = baseline.away!!.let { it.copy(team = it.team.copy(id = "7"), score = null) })
        val score = requireNotNull(SportsFeeds.summaryScore(nba, pending, root, 1234L))
        assertEquals(GameState.POST, score.state)
        assertFalse(score.awaitingUpdate)
        assertEquals("151", score.away?.score)
        assertEquals("145", score.home?.score)
        assertNull(SportsFeeds.summaryScore(nba, pending.copy(espnId = "other"), root, 1234L))
        val players = SportsPlayerStats.basketball(root)
        assertEquals(listOf("Nikola Jokic", "Josh Giddey", "Jamal Murray", "Buddy Hield"), players.map { it.name })
        assertTrue(players.first().line.contains("37 PTS"))
        assertTrue(players[1].line.contains("22 AST"))
    }

    // Reduced public ESPN scoreboard/summary responses captured on 1 November 2026.
    @Test fun `NBA live scoreboard retains scores clock and underlying event identity`() {
        val event = SportsFeeds.parseScoreboard(nba, fixture("nba-live-scoreboard")).single()
        assertEquals(GameState.LIVE, event.state)
        assertEquals("401909898", event.espnId)
        assertEquals("espn:basketball/nba:401909898", event.id)
        assertFalse(event.away!!.score.isNullOrBlank())
        assertFalse(event.home!!.score.isNullOrBlank())
        assertFalse(event.status.isNullOrBlank())
        val now = event.startMs + 60_000
        assertEquals(listOf(nba), SportsRefreshPolicy.choose(listOf(event), listOf(nba), null,
            emptySet(), emptySet(), true, now, emptyMap()).map { it.league })
    }

    @Test fun `NBA summary exposes player leaders team stats and scoring plays from the same response`() {
        val event = SportsFeeds.parseScoreboard(nba, fixture("nba-live-scoreboard")).single()
        val summary = JSONObject(fixture("nba-live-summary"))
        val extras = SportsExtras.parse(summary, event, Sport.BASKETBALL)
        assertTrue(extras.stats.any { it.label.contains("ASSIST") })
        assertTrue(extras.performers.any { it.category == "POINTS" && it.name.isNotBlank() && it.line.isNotBlank() })
        val plays = SportsFeeds.parseSummary(summary)
        assertEquals(2, plays.size)
        assertTrue(plays.all { !it.teamAbbr.isNullOrBlank() && !it.awayScore.isNullOrBlank() && !it.homeScore.isNullOrBlank() })
    }

    @Test fun `team comparison keeps additional shared stats and each participants conference rank`() {
        val event = SportsFeeds.parseScoreboard(nba, fixture("nba-live-scoreboard")).single()
        val summary = JSONObject(fixture("nba-live-summary"))
        val teams = summary.getJSONObject("boxscore").getJSONArray("teams")
        for (i in 0 until teams.length()) {
            val stats = teams.getJSONObject(i).getJSONArray("statistics")
            repeat(4) { j -> stats.put(JSONObject().put("name", "extra$j").put("label", "Extra $j").put("displayValue", "${10*i+j}")) }
        }
        val groups = org.json.JSONArray()
        for ((index, side) in event.sides.withIndex()) {
            fun row(id: String, name: String, rank: Int) = JSONObject()
                .put("team", JSONObject().put("id", id).put("displayName", name))
                .put("stats", org.json.JSONArray().put(JSONObject().put("name", "rank").put("displayValue", rank.toString())))
            val entries = org.json.JSONArray().put(row("bottom-$index", "Below $index", 3))
                .put(row(side.team.id, side.team.name, 2)).put(row("top-$index", "Above $index", 1))
                .put(row("fourth-$index", "Fourth $index", 4))
            groups.put(JSONObject().put("name", "Group $index")
                .put("standings", JSONObject().put("entries", entries)))
        }
        summary.put("standings", JSONObject().put("groups", groups))
        val extras = SportsExtras.parse(summary, event, Sport.BASKETBALL)
        assertEquals(listOf("EXTRA 0", "EXTRA 1", "EXTRA 2", "EXTRA 3"), extras.stats.takeLast(4).map { it.label })
        assertEquals(listOf("Group 0", "Group 1"), extras.table.map { it.heading })
        extras.table.forEachIndexed { index, section ->
            assertEquals(listOf("Above $index", event.sides[index].team.name, "Below $index"), section.rows.map { it.team })
            assertEquals(listOf(false, true, false), section.rows.map { it.highlighted })
        }

        // Opponents from the same division still get separate windows. No invented neighbour above first.
        val sameDivision = event.copy(home = event.home!!.copy(team = event.home!!.team.copy(id = "top-0")))
        val neighbours = SportsExtras.parse(summary, sameDivision, Sport.BASKETBALL).table
        assertEquals(listOf("Group 0", "Group 0"), neighbours.map { it.heading })
        assertEquals(listOf("1", "2"), neighbours.last().rows.map { it.pos })
        assertEquals("Above 0", neighbours.last().rows.single { it.highlighted }.team)

        val last = event.copy(away = event.away!!.copy(team = event.away!!.team.copy(id = "fourth-0")))
        assertEquals(listOf("3", "4"), SportsExtras.parse(summary, last, Sport.BASKETBALL).table.first().rows.map { it.pos })
    }
}
