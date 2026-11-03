package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class SportsCompetitionsTest {
    private fun browse() = SportsCatalog.browseOrdered(SportsSettings(region = "AU"))
    private fun team(league: String, id: String, name: String) = TeamInfo(league, id, name, name, name.take(3), null, null, null, null)

    @Test fun `duplicate league cards retain the real team feed`() {
        mapOf("NBA" to "basketball/nba", "NFL" to "football/nfl", "NHL" to "hockey/nhl", "MLB" to "baseball/mlb",
            "NRL" to "rugby-league/3", "WNBA" to "basketball/wnba").forEach { (name, id) ->
            val card = browse().single { it.name == name }
            assertEquals(id, card.id)
            assertNotNull(card.espnPath)
            assertTrue(SportsCompetitions.hasTeams(card.id))
            val alias = SportsTaxonomy.leagues.single { it.name == name && it.sport == card.sport }
            assertEquals(id, SportsCompetitions.canonicalId(alias.id))
            assertTrue(SportsTaxonomy.includesLeague(alias.id, id))
            assertTrue(SportsTaxonomy.includesLeague(id, alias.id))
        }
    }

    @Test fun `browse contains competitions once and category headings remain in category browser`() {
        val cards = browse()
        assertEquals(cards.size, cards.distinctBy { it.sport to SportsTaxonomy.normalize(it.name) }.size)
        assertTrue(cards.filter { it.id.startsWith("taxonomy/") }.all {
            val node = SportsTaxonomy.byId.getValue(it.id.removePrefix("taxonomy/"))
            node.parentId != null && node.children.isEmpty()
        })
        assertEquals(1, cards.count { it.name == "T20 Internationals" })
        assertTrue(SportsTaxonomy.byId.getValue("cricket.t20-internationals").children.size > 1)
    }

    @Test fun `old league follows collapse without changing team source IDs or personal row order`() {
        val alias = SportsTaxonomy.leagues.single { it.name == "NBA" }.id
        val team = FollowedTeam("basketball/nba", "42", "Example Club", "EX", null, "https://example.com/team.png")
        val original = SportsFollows(teams = listOf(team), pins = listOf(alias, "basketball/nba"),
            leagues = setOf(alias), unpinned = setOf(alias), order = listOf("t:${team.key}", "l:$alias", "l:basketball/nba"))
        val result = SportsCompetitions.follows(original)
        assertEquals(original.teams, result.teams)
        assertEquals(listOf("basketball/nba"), result.pins)
        assertEquals(setOf("basketball/nba"), result.leagues)
        assertEquals(setOf("basketball/nba"), result.unpinned)
        assertEquals(listOf("t:${team.key}", "l:basketball/nba"), result.order)
        assertEquals(result, SportsCompetitions.follows(result))
        assertEquals(result.order, SportsRows.rowKeys(original))
    }

    @Test fun `provider duplicates share a card but retain all teams and old follows`() {
        val old = SportsCatalog.extra
        try {
            val extra = League("tsdb/99999", Sport.BASKETBALL, "NBA", "NBA")
            SportsCatalog.setExtra(old + extra)
            assertEquals("basketball/nba", SportsCompetitions.canonicalId(extra.id))
            assertEquals(1, browse().count { it.name == "NBA" })
            assertTrue(SportsCompetitions.sources("basketball/nba").any { it.id == extra.id })
            val followed = FollowedTeam(extra.id, "old-id", "Club One", "ONE", null, null)
            val follows = SportsFollows(teams = listOf(followed), pins = listOf(extra.id))
            val merged = SportsCompetitions.teams("basketball/nba", listOf(
                team("basketball/nba", "new-id", "Club One"), team("basketball/nba", "2", "Club Two"),
                team(extra.id, "3", "Club Three")), follows)
            assertEquals(setOf("Club One", "Club Two", "Club Three"), merged.map { it.fullName }.toSet())
            assertEquals(followed.key, merged.single { it.fullName == "Club One" }.key)
            assertEquals(listOf(followed), SportsCompetitions.follows(follows).teams)
            assertEquals(listOf("basketball/nba"), SportsCompetitions.follows(follows).pins)
            assertTrue(SportsCompetitions.teams("basketball/nba", emptyList(), follows).any { it.key == followed.key })
        } finally { SportsCatalog.setExtra(old) }
    }

    @Test fun `provider cricket names retain team feeds without changing the agreed order`() {
        val old = SportsCatalog.extra
        try {
            SportsCatalog.setExtra(old + listOf(
                League("tsdb/4461", Sport.CRICKET, "Australian Big Bash League", "BBL"),
                League("tsdb/99998", Sport.CRICKET, "Indian Premier League", "IPL")
            ))
            val cricket = browse().filter { it.sport == Sport.CRICKET }
            assertEquals(listOf("Test Matches", "ODIs", "T20 Internationals", "IPL", "BBL", "The Hundred"), cricket.take(6).map { it.name })
            assertEquals(1, cricket.count { it.name == "BBL" })
            assertEquals("tsdb/4461", cricket.single { it.name == "BBL" }.id)
            assertTrue(SportsCompetitions.hasTeams(cricket.single { it.name == "IPL" }.id))
            assertEquals("tsdb/4461", SportsCompetitions.canonicalId("taxonomy/cricket.franchise-t20-leagues.bbl"))
        } finally { SportsCatalog.setExtra(old) }
    }

    @Test fun `provider abbreviations and longer league titles resolve to their curated competitions`() {
        mapOf("Rugby Union Test Matches" to "rugby/289234", "International Friendlies Football" to "soccer/fifa.friendly",
            "English Premier League" to "soccer/eng.1", "PREM Rugby" to "rugby/267979", "NBL Australia" to "basketball/nbl",
            "La Liga" to "soccer/esp.1", "UEFA Champions League" to "soccer/uefa.champions").forEach { (label, id) ->
            val alias = SportsTaxonomy.leagues.single { it.name == label }
            assertEquals(label, id, SportsCompetitions.canonicalId(alias.id))
            assertEquals(label, 1, browse().count { SportsCompetitions.same(it.id, id) })
        }
    }

    @Test fun `different gender country and competition stages do not collapse`() {
        listOf("football/college-football", "basketball/mens-college-basketball", "basketball/womens-college-basketball",
            "soccer/fifa.worldq.uefa", "soccer/fifa.worldq.concacaf", "soccer/fifa.world", "soccer/fifa.wwc",
            "soccer/eng.1", "soccer/eng.2").forEach { id ->
            assertEquals(id, SportsCompetitions.canonicalId(id))
            assertTrue(id, browse().any { it.id == id })
        }
        assertFalse(SportsCompetitions.same("baseball/mlb", "taxonomy/baseball.mlb-postseason-events.world-series"))
        assertFalse(SportsCompetitions.same("taxonomy/cricket.t20-internationals",
            "taxonomy/cricket.t20-internationals.t20-internationals"))
    }

    @Test fun `motorsport and horse racing are separate and main series precede support series`() {
        assertEquals("Motorsport", Sport.RACING.label)
        assertEquals(Sport.HORSE_RACING, SportsCatalog.league("guide/horse-racing")?.sport)
        SportsCatalog.regions.forEach { (region, _) ->
            val races = SportsCatalog.browseOrdered(SportsSettings(region = region)).filter { it.sport == Sport.RACING }
            assertEquals("racing/f1", races.first().id)
            assertTrue(races.indexOfFirst { it.id == "racing/nascar-premier" } < races.indexOfFirst { it.id == "racing/nascar-secondary" })
            assertTrue(races.indexOfFirst { it.id == "racing/nascar-premier" } < races.indexOfFirst { it.id == "racing/nascar-truck" })
        }
    }

    @Test fun `cue sport discovery and classification includes snooker pool and billiards`() {
        assertEquals(setOf("Snooker", "Pool", "Billiards"), FreeSportsDb.discoverySports.getValue(Sport.SNOOKER).toSet())
        listOf("Snooker", "Pool", "Billiards").forEach { sport ->
            val raw = """{"leagues":[{"idLeague":"99999","strLeague":"Example Championship","strSport":"$sport"}]}"""
            assertEquals(Sport.SNOOKER, FreeSportsDb.leagues(raw).single().sport)
        }
    }
}
