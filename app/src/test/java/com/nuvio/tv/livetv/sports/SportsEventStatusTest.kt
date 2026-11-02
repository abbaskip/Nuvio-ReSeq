package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SportsEventStatusTest {
    private val f1 = SportsCatalog.league("racing/f1")!!

    private fun weekend(state: String, name: String = "STATUS_FINAL"): SportsEvent {
        val now = System.currentTimeMillis()
        val start = Instant.ofEpochMilli(now - 2 * 86_400_000L).toString()
        val end = Instant.ofEpochMilli(now - 2 * 3_600_000L).toString()
        return SportsFeeds.parseScoreboard(f1, """
            {"events":[{"id":"race","name":"Mexico City Grand Prix","date":"$start","endDate":"$end",
              "status":{"type":{"state":"$state","name":"$name","shortDetail":"$name"}},
              "competitions":[{"status":{"type":{"state":"post"}}}]}]}
        """.trimIndent()).single()
    }

    @Test fun `finished F1 weekend stays final hours after the race`() {
        val event = weekend("post")
        assertFalse(event.hasTime)
        assertEquals(GameState.POST, event.state)
    }

    @Test fun `weekend dates cannot promote scheduled or cancelled events to live`() {
        assertEquals(GameState.PRE, weekend("pre", "STATUS_SCHEDULED").state)
        assertEquals(GameState.OFF, weekend("post", "STATUS_CANCELED").state)
    }

    @Test fun `provider live status is retained across a multi-session weekend`() {
        assertEquals(GameState.LIVE, weekend("in", "STATUS_IN_PROGRESS").state)
    }

    private fun event(id: String, source: EventSource, state: GameState) = SportsEvent(
        id, f1.id, System.currentTimeMillis() + 60_000L, true, state, id, null, null,
        away = null, home = null, source = source
    )

    @Test fun `My Sports separates guide shows from live and upcoming events`() {
        val now = System.currentTimeMillis()
        val live = event("race", EventSource.ESPN, GameState.LIVE)
        val upcoming = event("next-race", EventSource.ESPN, GameState.PRE)
        val show = event("show", EventSource.GUIDE, GameState.LIVE)
        val nextShow = event("next-show", EventSource.GUIDE, GameState.PRE)
        val page = SportsRows.build(listOf(show, live, nextShow, upcoming), SportsFollows(), now) { true }
        assertEquals(listOf("up", "onair"), page.rows.map { it.key })
        assertEquals(listOf(live, upcoming), page.rows[0].events)
        assertEquals(listOf(show, nextShow), page.rows[1].events)
        assertTrue(page.heroes.all { it.source != EventSource.GUIDE })
        assertEquals("LIVE", live.liveLabel)
        assertEquals("ON AIR", show.liveLabel)
    }

    @Test fun `guide-only follows still get a sports shows row`() {
        val show = event("show", EventSource.GUIDE, GameState.LIVE)
        val page = SportsRows.build(listOf(show), SportsFollows(), System.currentTimeMillis()) { true }
        assertEquals(SportsRow.Kind.ON_AIR, page.rows.single().kind)
        assertTrue(page.heroes.isEmpty())
    }

    @Test fun `followed league timelines also separate events from guide shows`() {
        val live = event("race", EventSource.ESPN, GameState.LIVE)
        val show = event("show", EventSource.GUIDE, GameState.LIVE)
        val follows = SportsFollows(pins = listOf(f1.id))
        val page = SportsRows.build(listOf(live, show), follows, System.currentTimeMillis()) { true }
        val leagueRows = page.rows.filter { it.kind == SportsRow.Kind.LEAGUE }
        assertEquals(2, leagueRows.size)
        assertEquals(listOf(live), leagueRows[0].events)
        assertEquals(listOf(show), leagueRows[1].events)
        assertTrue(leagueRows[1].title.endsWith("Sports Shows"))
    }
}
