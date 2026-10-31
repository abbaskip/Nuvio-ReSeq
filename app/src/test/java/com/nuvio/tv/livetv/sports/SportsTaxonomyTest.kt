package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class SportsTaxonomyTest {
    @Test fun `shared competition labels use explicit sport context without overriding qualified titles`() {
        assertEquals("football-soccer", SportsTaxonomy.classifyProgramme("Women's Super League: Team One v Team Two",
            "Coverage of English women's professional football.")?.sportId)
        assertEquals("rugby-league", SportsTaxonomy.classifyProgramme("Women's Super League: Team One v Team Two",
            "Coverage of women's rugby league.")?.sportId)
        assertEquals("rugby-league", SportsTaxonomy.classifyProgramme("RFL Women's Super League", "Football follows later")?.sportId)
    }
    @Test fun `indexed phrases respect title boundaries repeated words and long descriptions`() {
        assertEquals("rugby-league", SportsTaxonomy.classify("NRL Premiership", "football ".repeat(1000))?.sportId)
        assertEquals("football-soccer", SportsTaxonomy.classify("UEFA", "Champions League")?.sportId)
        assertNull(SportsTaxonomy.classify("NBAmazing", "ordinary programme ".repeat(1000)))
    }
    @Test fun `entire supplied hierarchy is reachable with unique stable IDs`() {
        assertEquals(13, SportsTaxonomy.roots.size)
        assertEquals(52, SportsTaxonomy.roots.sumOf { it.children.size })
        assertEquals(405, SportsTaxonomy.roots.sumOf { it.children.sumOf { c -> c.children.size } })
        assertEquals(SportsTaxonomy.nodes.size, SportsTaxonomy.byId.size)
        assertTrue(SportsTaxonomy.nodes.all { SportsTaxonomy.enabled(it.id, emptySet()) })
    }
    @Test fun `ambiguous terms cannot identify sports alone`() {
        listOf("Asia Cup", "Champions League", "NCAA", "Test Match", "NBL", "World Series").forEach {
            assertNull(it, SportsTaxonomy.classify(it, null))
        }
    }
    @Test fun `aliases and description identify the correct sport`() {
        mapOf("ACC Asia Cup India v Pakistan" to "cricket", "TATA IPL Mumbai v Chennai" to "cricket",
            "UEFA Champions League Arsenal" to "football-soccer", "NCAA Women's Gymnastics" to "gymnastics",
            "UCI Track Nations Cup" to "cycling", "MLB World Series" to "baseball",
            "NRL Premiership" to "rugby-league", "Six Nations Rugby Union" to "rugby-union").forEach { (text, sport) ->
            assertEquals(text, sport, SportsTaxonomy.classify("Live coverage", text)?.sportId)
        }
    }
    @Test fun `excluded events stay excluded in cached and schedule results`() {
        val node = requireNotNull(SportsTaxonomy.classify("TATA IPL", null))
        assertFalse(SportsTaxonomy.acceptsEvent(null, "TATA IPL", null, setOf(node.id)))
        assertFalse(SportsTaxonomy.acceptsEvent(null, "TATA IPL", null, setOf(node.sportId)))
        assertTrue(SportsTaxonomy.acceptsEvent(null, "TATA IPL", null, setOf("rugby-league")))
        assertFalse(SportsTaxonomy.accepts(League("tennis", Sport.TENNIS, "Tennis", "Tennis"), setOf("legacy/TENNIS")))
    }
    @Test fun `title evidence takes precedence over incidental descriptions`() {
        assertEquals("rugby-league", SportsTaxonomy.classify("Live NRL Premiership", "Six Nations Rugby Union highlights elsewhere")?.sportId)
    }
    @Test fun `EPG fixture pairs can confirm unique acronyms without guessing shared ones`() {
        assertEquals("rugby-league", SportsTaxonomy.classifyProgramme("NRL: Raiders v Rabbitohs", null)?.sportId)
        assertEquals("cricket", SportsTaxonomy.classifyProgramme("IPL: Mumbai vs Chennai", null)?.sportId)
        assertNull(SportsTaxonomy.classifyProgramme("NCAA: Michigan v Wisconsin", null))
        assertNull(SportsTaxonomy.classifyProgramme("NBL: Team One v Team Two", null))
        assertNull(SportsTaxonomy.classifyProgramme("NRL Tonight", null))
    }
    @Test fun `FA qualified womens super league is football without description context`() {
        assertEquals("football-soccer", SportsTaxonomy.classifyProgramme("FA Women's Super League : Everton vs. Aston Villa", "From Goodison Park in Liverpool, Merseyside, England.")?.sportId)
    }
    @Test fun `words respect boundaries and punctuation normalization`() {
        assertNull(SportsTaxonomy.classify("NBAmazing", null))
        assertEquals(SportsTaxonomy.normalize("Café–Rugby"), SportsTaxonomy.normalize("Cafe Rugby"))
    }
    @Test fun `branch opt outs inherit and never enable an excluded league through its sport`() {
        val node = requireNotNull(SportsTaxonomy.classify("TATA IPL", null))
        assertNotNull(node.parentId)
        assertFalse(SportsTaxonomy.enabled(node.id, setOf(node.id)))
        assertFalse(SportsTaxonomy.enabled(node.id, setOf(node.parentId!!)))
        assertFalse(SportsTaxonomy.enabled(node.id, setOf(node.sportId)))
        assertTrue(SportsTaxonomy.enabled(node.sportId, setOf(node.id)))
        assertTrue(SportsTaxonomy.enabled("rugby-union", setOf("rugby-league")))
    }
}
