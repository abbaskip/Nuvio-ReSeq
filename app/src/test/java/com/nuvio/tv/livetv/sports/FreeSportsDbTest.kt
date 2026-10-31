package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class FreeSportsDbTest {
    private val league = League("tsdb/4328", Sport.FOOTBALL, "English Premier League", "EPL", guideWords = listOf("EPL"))
    private fun raw(status: String = "FT", home: String = "0", away: String = "0", leagueId: String = "4328", time: String = "16:30:00") =
        """{"events":[{"idEvent":"9","idLeague":"$leagueId","strEvent":"Arsenal vs Chelsea","dateEvent":"2026-10-25","strTime":"$time","strStatus":"$status","idHomeTeam":"1","idAwayTeam":"2","strHomeTeam":"Arsenal","strAwayTeam":"Chelsea","intHomeScore":$home,"intAwayScore":$away}]}"""
    @Test fun `zero zero final is retained but missing scores are never invented`() {
        val final = FreeSportsDb.events(league, raw(), 100).single()
        assertEquals(GameState.POST, final.state)
        assertTrue(final.hasScore)
        assertEquals("0", final.home?.score)
        val unknown = FreeSportsDb.events(league, raw("NS", "null", "null"), 100).single()
        assertEquals(GameState.PRE, unknown.state)
        assertNull(unknown.home?.score)
        assertFalse(unknown.hasScore)
        assertEquals(100L, unknown.updatedAt)
    }
    @Test fun `old date alone never means finished or live`() {
        assertEquals(GameState.PRE, FreeSportsDb.events(league, raw(""), 100).single().state)
        assertEquals(GameState.OFF, FreeSportsDb.events(league, raw("PST"), 100).single().state)
        assertEquals(GameState.LIVE, FreeSportsDb.events(league, raw("HT"), 100).single().state)
    }
    @Test fun `provider league and event identities cannot be silently substituted`() {
        assertTrue(FreeSportsDb.events(league, raw(leagueId = "4416"), 100).isEmpty())
        assertEquals("tsdb:4328:9", FreeSportsDb.events(league, raw(), 100).single().id)
    }
    @Test fun `date only stays uncertain and partial responses merge without duplicating fixtures`() {
        assertFalse(FreeSportsDb.events(league, raw(time = ""), 100).single().hasTime)
        assertEquals(1, FreeSportsDb.events(league, FreeSportsDb.joinEvents(listOf(raw(), raw())), 100).size)
        assertTrue(FreeSportsDb.events(league, """{"events":null}""", 100).isEmpty())
    }
    @Test fun `country tagged coverage requires the requested event id`() {
        val tv = """{"tvevent":[{"idEvent":"9","strChannel":"Fox Sports 501","strCountry":"Australia"},{"idEvent":"10","strChannel":"Sky Sports 1","strCountry":"New Zealand"}]}"""
        assertEquals(listOf(SportsBroadcast("Fox Sports 501", "Australia")), FreeSportsDb.coverage(tv, "9"))
    }
    @Test fun `rugby league metadata is never automatically treated as union`() {
        val raw = """{"leagues":[{"idLeague":"4416","strLeague":"Australian National Rugby League","strSport":"Rugby"}]}"""
        assertEquals(Sport.RUGBY_LEAGUE, FreeSportsDb.leagues(raw).single().sport)
    }
    @Test fun `verified timestamp establishes kickoff when separate time field is missing`() {
        val withTimestamp = raw(time = "").replace("\"dateEvent\":", "\"strTimestamp\":\"2026-10-25T16:30:00Z\",\"dateEvent\":")
        val event = FreeSportsDb.events(league, withTimestamp, 100).single()
        assertTrue(event.hasTime)
        assertEquals(java.time.Instant.parse("2026-10-25T16:30:00Z").toEpochMilli(), event.startMs)
    }
    @Test fun `background watched channel refresh excludes future finished and stale events`() {
        val event = FreeSportsDb.events(league, raw("NS"), 100).single()
        val now = event.startMs + 60_000
        assertTrue(FreeSportsDb.refreshableWhileWatching(event, now))
        assertFalse(FreeSportsDb.refreshableWhileWatching(event.copy(startMs = now + 1), now))
        assertFalse(FreeSportsDb.refreshableWhileWatching(event.copy(state = GameState.POST), now))
        assertFalse(FreeSportsDb.refreshableWhileWatching(event.copy(state = GameState.OFF), now))
        assertFalse(FreeSportsDb.refreshableWhileWatching(event.copy(hasTime = false), now))
        assertFalse(FreeSportsDb.refreshableWhileWatching(event.copy(state = GameState.LIVE), now + 24 * 60 * 60_000L))
    }

    @Test fun `team parser retains every supplied team and supports current and legacy badges`() {
        val rows = (1..32).joinToString(",") { i ->
            """{"idTeam":"$i","strTeam":"Club $i","${if (i % 2 == 0) "strBadge" else "strTeamBadge"}":"https://example.com/$i.png"}"""
        }
        val teams = FreeSportsDb.teams(league, """{"teams":[$rows]}""")
        assertEquals(32, teams.size)
        assertEquals("https://example.com/32.png", teams.last().logo)
        assertEquals("https://example.com/1.png", teams.first().logo)
        assertEquals("32", teams.last().id)
        assertTrue(teams.all { it.leagueId == league.id })
    }

    @Test fun `missing badges and unsafe schemes keep the normal placeholder`() {
        val teams = FreeSportsDb.teams(league, """{"teams":[{"idTeam":"1","strTeam":"One","strBadge":"file:///private"},{"idTeam":"2","strTeam":"Two","strBadge":"null"}]}""")
        assertTrue(teams.all { it.logo == null })
    }

    @Test fun `NRL uses the complete ESPN catalogue and names never cross sport identities`() {
        val nrl = SportsCatalog.league("rugby-league/3")!!
        assertEquals(Sport.RUGBY_LEAGUE, nrl.sport)
        assertEquals("rugby-league/3", nrl.espnPath)
        assertEquals("Parramatta Eels", SportsCatalog.teamFullName(nrl, "289194", "Eels"))
        assertEquals("Penrith Panthers", SportsCatalog.teamFullName(nrl, "289199", "Panthers"))
        assertEquals("New Club", SportsCatalog.teamFullName(nrl, "new", "New Club"))
        assertEquals("Panthers", SportsCatalog.teamFullName(league, "289199", "Panthers"))
    }

    @Test fun `fixture badges retain their own side without extra team lookups`() {
        val json = raw().replace("\"strHomeTeam\":", "\"strHomeTeamBadge\":\"https://example.com/home.png\",\"strAwayTeamBadge\":\"file:///private\",\"strHomeTeam\":")
        val event = FreeSportsDb.events(league, json, 100).single()
        assertEquals("https://example.com/home.png", event.home!!.team.logo)
        assertNull(event.away!!.team.logo)
    }

}
