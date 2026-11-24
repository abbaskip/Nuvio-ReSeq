package com.nuvio.tv.livetv.sports

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsMatchStatsTest {
    private val league = "cricket/19430"
    private fun side(name: String, home: Boolean) = Side(TeamInfo(league, name, name, name, name, null, null, null, null),
        null, null, false, home)
    private val event = SportsEvent("match", league, 1_000L, true, GameState.PRE, "Match", null, null,
        side("Australia", false), side("South Africa", true))

    @Test fun `local dates distinguish either side of midnight including unconfirmed times`() {
        val zone = java.util.TimeZone.getDefault()
        val locale = java.util.Locale.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Australia/Sydney"))
            java.util.Locale.setDefault(java.util.Locale.US)
            val late = event.copy(startMs = java.time.Instant.parse("2026-11-23T12:55:00Z").toEpochMilli())
            val early = late.copy(startMs = late.startMs + 10 * 60_000)
            assertEquals("Mon 23 Nov  ·  23:55", eventTimelineLabel(late, true))
            assertEquals("Tue 24 Nov  ·  12:05 AM", eventTimelineLabel(early, false))
            assertEquals("Tue 24 Nov  ·  Time to be confirmed", eventTimelineLabel(early.copy(hasTime = false), true))
        } finally {
            java.util.TimeZone.setDefault(zone)
            java.util.Locale.setDefault(locale)
        }
    }

    @Test fun `cricket scorecard distinguishes the active pair and ranks best innings across the whole match`() {
        val raw = """{"fixture":{"id":40691,"innings":[
            {"inningNumber":1,"batsmen":[{"playerId":1,"runsScored":102,"ballsFaced":155,"dismissalTypeId":"Caught"}],
            "bowlers":[{"playerId":3,"wicketsTaken":5,"runsConceded":53}]},
            {"inningNumber":2,"batsmen":[{"playerId":2,"runsScored":33,"ballsFaced":60,"isBatting":true,"dismissalTypeId":"NotOut"},
            {"playerId":3,"runsScored":13,"ballsFaced":23,"isBatting":true,"dismissalTypeId":"NotOut"},
            {"playerId":4,"dismissalTypeId":"yet to bat"}],"bowlers":[{"playerId":1,"wicketsTaken":5,"runsConceded":62}]}]},
            "players":[{"id":1,"name":"A"},{"id":2,"name":"B"},{"id":3,"name":"C"},{"id":4,"name":"D"}]}"""
        val root = JSONObject(raw)
        assertEquals(listOf("33* (60 balls)", "13* (23 balls)"), SportsPlayerStats.cricketAustralia(root, 40691).map { it.line })
        assertTrue(SportsPlayerStats.cricketAustralia(root, 40692).isEmpty())
        root.getJSONObject("fixture").put("isCompleted", true)
        val leaders = SportsPlayerStats.cricketAustralia(root, 40691)
        assertEquals(listOf("A","C"), leaders.take(2).map { it.name })
        assertTrue(leaders[0].line.startsWith("102"))
        assertTrue(leaders[1].line.startsWith("5/53"))
    }

    @Test fun `international cricket uses all innings for final leaders and the active innings for not outs`() {
        val root = JSONObject("""{"header":{"competitions":[{"status":{"period":2}}]},"matchcards":[
            {"inningsNumber":"1","headline":"Batting","playerDetails":[{"playerName":"Earlier","dismissal":"not out","runs":"72"}]},
            {"inningsNumber":"2","headline":"Batting","playerDetails":[{"playerName":"Current","dismissal":"not out","runs":"5","ballsFaced":"11"}]}],
            "rosters":[{"roster":[{"athlete":{"displayName":"Opener"},"linescores":[{"period":1,"linescores":[{"statistics":{"categories":[
            {"stats":[{"name":"runs","value":172},{"name":"batted","value":1},{"name":"ballsFaced","value":210}]}]}}]}]},
            {"athlete":{"displayName":"Bowler"},"linescores":[{"period":2,"linescores":[{"statistics":{"categories":[
            {"stats":[{"name":"wickets","value":6},{"name":"conceded","value":44}]}]}}]}]}]}]}""")
        assertEquals("Current", SportsPlayerStats.espnCricket(root, GameState.LIVE).single().name)
        val leaders = SportsPlayerStats.espnCricket(root, GameState.POST)
        assertEquals(listOf("Opener", "Bowler"), leaders.map { it.name })
        assertTrue(leaders.last().line.startsWith("6/44"))
    }

    @Test fun championshipUsesAvailablePointsAndOnlyAppearsForEligibleTests() {
        fun entry(name: String, games: Int, points: Int, rank: Int): String {
            fun stat(key: String, value: Int) = """{"name":"$key","value":$value,"displayValue":"$value"}"""
            val stats = listOf(stat("rank", rank), stat("matchesPlayed", games), stat("matchPoints", points), stat("matchesDraw", 0))
            return """{"team":{"id":"$name","name":"$name"},"stats":[${stats.joinToString()}]}"""
        }
        val entries = listOf(entry("Australia", 13, 108, 1), entry("South Africa", 8, 72, 4), entry("England", 16, 74, 2))
        val raw = """{"name":"ICC World Test Championship","children":[{"name":"","standings":{"entries":[${entries.joinToString()}]}}]}"""
        val table = Standings.parseEspn(SportsCatalog.league(league)!!, raw)!!
        val section = cricketSections(event, table).single()
        assertEquals("ICC World Test Championship", section.heading)
        assertEquals(listOf("M", "D", "PTS", "PCT"), section.columns)
        assertEquals(listOf("South Africa", "Australia", "England"), section.rows.map { it.team })
        assertEquals(listOf("8", "0", "72", "75.00"), section.rows.first().values)
        assertEquals(listOf(true, true, false), section.rows.map { it.highlighted })
        val pinned = pinnedSummary(event, null, table, false, true)
        assertEquals("Competition positions", pinned.title)
        assertTrue(pinned.rows.first().value.contains("1st"))
        fun summary(rel: String) = JSONObject("""{"header":{"leagues":[{"id":"19430","links":[{"rel":["$rel","desktop"]}]}]}}""")
        assertEquals(league, SportsExtras.parse(summary("table"), event, Sport.CRICKET).cricketTableLeague)
        assertNull(SportsExtras.parse(summary("serieshome"), event, Sport.CRICKET).cricketTableLeague)
    }

    @Test fun domesticTableRetainsFixtureSeasonAndPublishedFractionalPoints() {
        val raw = """{"fixtures":[{"id":123,"gameTypeId":4,"competition":{"id":4689,"name":"Sheffield Shield"},
            "startDateTime":"2026-11-16T00:30:00Z"}]}"""
        val fixture = CricketAustralia.parse(raw, 0).single()
        assertEquals(4689, SportsSnapshotEvent.from(fixture).event().caCompetitionId)
        val html = """<h1>Sheffield Shield 2026-27</h1><div class="w-series-standings"><table>
            <thead><tr><th>Team</th><th aria-labelledby="round0_group0_played">P</th>
            <th aria-labelledby="round0_group0_bonus">B</th><th aria-labelledby="round0_group0_points">PTS</th></tr></thead>
            <tbody><tr><th><span class="o-standings__team-position">1</span><img class="o-standings__team-flag" data-id="44">
            <span class="o-standings__team-name">Western Australia Men</span><span class="w-series-standings__team-name--short">WA</span></th>
            <td>4</td><td>3.4</td><td>18.42</td></tr></tbody></table></div>"""
        val table = CricketAustralia.parseTable(fixture.leagueId, html, 0)!!
        val row = table.groups.single().rows.single()
        assertEquals("Sheffield Shield 2026-27", table.season)
        assertEquals("Western Australia", row.name)
        assertEquals(mapOf("gamesPlayed" to "4", "points" to "18.42"), row.values)
        assertNull(CricketAustralia.parseTable(fixture.leagueId, "<h1>Fixtures</h1>", 0))
    }

    @Test fun scorecardShowsCurrentBattersAtStumpsAndHiddenScoresAndPendingResultsStayPrivate() {
        val stumps = event.copy(state = GameState.OFF, status = "Stumps",
            home = event.home!!.copy(score = "193/4", periods = listOf("255 (84 ov)", "193/4 (50 ov)")))
        val batter = Performer("At the crease", "Batter", "33* (61 balls)", null)
        val batting = pinnedSummary(stumps, GameExtras(playerLines = listOf(batter)), null, false, true)
        assertEquals("At the crease", batting.title)
        assertEquals("33* (61 balls)", batting.rows.single().value)
        assertFalse(pinnedSummary(stumps, null, null, false, true).rows.any { it.value.contains("193/4") })
        val info = GameExtras(venue = "Centurion")
        for (summary in listOf(pinnedSummary(stumps, info, null, true, true),
            pinnedSummary(stumps.copy(awaitingUpdate = true), info, null, false, true))) {
            assertEquals("Event details", summary.title)
            assertTrue(summary.rows.contains(PinnedStat("Venue", "Centurion")))
            assertFalse(summary.rows.any { it.value.contains("193") })
        }
    }

    @Test fun homeFirstAndAwayFirstSportsAlignStatsWithTheDisplayedTeams() {
        val extras = GameExtras(stats = listOf(StatLine("Shots", "2", "5", 2.0, 5.0)))
        val live = event.copy(state = GameState.LIVE)
        val soccer = pinnedSummary(live.copy(leagueId = "soccer/eng.1"), extras, null, false, true)
        val nba = pinnedSummary(live.copy(leagueId = "basketball/nba"), extras, null, false, true)
        assertEquals(listOf("South Africa", "Australia"), soccer.teams)
        assertEquals(PinnedStat("Shots", "5", "2"), soccer.rows.single())
        assertEquals(soccer.teams.reversed(), nba.teams)
        assertEquals(PinnedStat("Shots", "2", "5"), nba.rows.single())
    }
}
