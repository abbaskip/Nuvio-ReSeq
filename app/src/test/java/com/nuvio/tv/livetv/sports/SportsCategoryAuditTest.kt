package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class SportsCategoryAuditTest {
    @Test fun `major competitions precede regional suggestions in every region`() {
        SportsCatalog.regions.forEach { (region, _) ->
            val leagues = SportsCatalog.browseOrdered(SportsSettings(region = region))
            assertEquals(region, "hockey/nhl", leagues.first { it.sport == Sport.HOCKEY }.id)
            assertEquals(region, "basketball/nba", leagues.first { it.sport == Sport.BASKETBALL }.id)
            assertEquals(region, "football/nfl", leagues.first { it.sport == Sport.AMERICAN_FOOTBALL }.id)
            assertEquals(region, listOf("MLB", "World Series"), leagues.filter { it.sport == Sport.BASEBALL }.take(2).map { it.name })
            assertEquals(region, listOf("Test Matches", "ODIs", "T20 Internationals", "IPL", "BBL", "The Hundred"),
                leagues.filter { it.sport == Sport.CRICKET }.take(6).map { it.name })
        }
    }

    @Test fun `cycling and cue sports have their own correctly labelled categories`() {
        assertEquals(Sport.CYCLING, SportsCatalog.league("guide/cycling")?.sport)
        assertFalse(SportsCatalog.all.any { it.sport == Sport.OTHER && it.name == "Cycling" })
        assertEquals("Ice Hockey", Sport.HOCKEY.label)
        assertEquals("Cue Sports", Sport.SNOOKER.label)
        assertEquals(Sport.SNOOKER, SportsTaxonomy.sport("billiards-cue-sports"))
    }

    @Test fun `major league badges exist before fixtures load and taxonomy shares them`() {
        listOf("football/nfl", "basketball/nba", "basketball/wnba", "hockey/nhl", "baseball/mlb",
            "soccer/eng.1", "soccer/esp.1", "soccer/uefa.champions", "rugby-league/3",
            "australian-football/afl", "racing/f1", "mma/ufc").forEach { id ->
            val league = requireNotNull(SportsCatalog.league(id))
            assertTrue(id, SportsLeagueLogos.logo(league)?.startsWith("https://") == true)
        }
        val nba = SportsTaxonomy.leagues.first { it.sport == Sport.BASKETBALL && it.name == "NBA" }
        assertEquals(SportsLeagueLogos.logo(requireNotNull(SportsCatalog.league("basketball/nba"))), SportsLeagueLogos.logo(nba))
    }
}
