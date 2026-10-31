package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class SportsGuideEventsTest {
    private fun event(id: String, title: String, description: String = "Women's Rugby League World Cup", start: Long = 1_000_000) =
        SportsEvent(id, "world-cup", start, true, GameState.LIVE, title, description, null, null, null,
            source = EventSource.GUIDE, endMs = start + 7_200_000,
            guideHits = listOf(id to EpgProgram(start, start + 7_200_000, title, description)))
    @Test fun `verified duplicate participants retain every regional provider and quality feed`() {
        val events = listOf(event("au", "Rugby League World Cup 2026 : Women: England v Samoa"),
            event("uk", "Women's Rugby League World Cup", "Pool A: England v Samoa\nWomen's Rugby League World Cup", 1_300_000),
            event("nz", "Rugby League World Cup : RLWC: England v Samoa", start = 1_600_000))
        val merged = SportsGuideEvents.merge(events, emptySet()).single()
        assertEquals(events.flatMap { it.guideHits }.toSet(), merged.guideHits.toSet())
        assertEquals(events.maxOf { it.endMs!! }, merged.endMs)
    }
    @Test fun `current guide broadcast never claims original match is live or final`() {
        val replay = event("replay", "NRL : Warriors v Knights Semi Final")
        assertEquals("ON AIR", replay.liveLabel)
        assertEquals("AIRED", replay.copy(state = GameState.POST).finishedLabel)
        assertEquals("LIVE", replay.copy(source = EventSource.ESPN).liveLabel)
        assertEquals("FINAL", replay.copy(source = EventSource.ESPN, state = GameState.POST).finishedLabel)
    }
    @Test fun `replay tags require explicit guide evidence and do not override sports feed status`() {
        val replay = event("replay", "NRL : Warriors v Knights Semi Final", "A repeat of September's semi final.")
        assertTrue(replay.isReplay)
        assertEquals("REPLAY", replay.liveLabel)
        assertFalse(event("unknown", "NRL : Warriors v Knights Semi Final", "Coverage of the semi final.").isReplay)
        assertFalse(event("narrative", "NRL: Warriors v Knights", "Can Warriors repeat their classic victory?").isReplay)
        assertFalse(replay.copy(source = EventSource.ESPN).isReplay)
        assertEquals("LIVE", replay.copy(source = EventSource.ESPN).liveLabel)
    }
    @Test fun `saved preference identities stay separate and unchanged`() {
        val events = listOf(event("a", "England v Samoa"), event("b", "World Cup: England vs Samoa"))
        assertEquals(events, SportsGuideEvents.merge(events, setOf("b")))
    }
    @Test fun `different opponents categories competitions times and ambiguous pairings never merge`() {
        val first = event("first", "England v Samoa")
        val others = listOf(event("opponent", "England v Fiji"), event("men", "England v Samoa", "Men's Rugby League World Cup"),
            event("age", "England v Samoa", "Women's U19 Rugby League World Cup"),
            event("later", "England v Samoa", start = 20_000_000),
            event("league", "England v Samoa").copy(leagueId = "another"),
            event("ambiguous", "England v Samoa", "Women: England v Fiji"))
        for (other in others) assertEquals(other.id, 2, SportsGuideEvents.merge(listOf(first, other), emptySet()).size)
    }
}
