package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class SportsBrowseLeagueDeduplicationTest {
    @Test fun `NHL picker keeps working provider league while legacy guide IDs remain resolvable`() {
        val all = SportsCatalog.all
        val duplicate = all.first { it.name == "NHL" && it.id.startsWith("taxonomy/") }
        val browse = SportsCatalog.uniqueBrowseLeagues(all)
        assertEquals(listOf("hockey/nhl"), browse.filter { it.name == "NHL" }.map { it.id })
        assertNotNull(SportsCatalog.league(duplicate.id))
        val keys = browse.flatMap { l -> listOf(l.name, l.short).map { l.sport to SportsCatalog.norm(it).trim() }.distinct() }
        assertEquals(keys.size, keys.toSet().size)
    }
    @Test fun `taxonomy aliases also collapse differently worded duplicate league labels`() {
        val browse = SportsCatalog.uniqueBrowseLeagues(SportsCatalog.all)
        assertTrue(browse.any { it.id == "soccer/eng.1" })
        assertFalse(browse.any { it.name == "English Premier League" && it.id.startsWith("taxonomy/") })
    }
    @Test fun `same name in different sports and distinct NHL events remain available`() {
        val one = League("one", Sport.HOCKEY, "Premier League", "PL")
        val two = one.copy(id = "two", sport = Sport.FOOTBALL)
        assertEquals(2, SportsCatalog.uniqueBrowseLeagues(listOf(one, two)).size)
        assertTrue(SportsCatalog.uniqueBrowseLeagues(SportsCatalog.all).any { it.name == "NHL All-Star" })
    }
}
