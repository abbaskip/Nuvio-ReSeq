package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class SportsEventIdentityTest {
    private val espn = League("soccer/eng.1", Sport.FOOTBALL, "Premier League", "EPL", guideWords = listOf("EPL"))
    private val tsdb = League("tsdb/4328", Sport.FOOTBALL, "English Premier League", "EPL")
    private fun event(id: String, l: League, first: String = "Arsenal", second: String = "Chelsea", at: Long = 10000000) =
        SportsEvent(id,l.id,at,true,GameState.PRE,"$first v $second",null,null,
            Side(TeamInfo(l.id,"1",first,first,first,null,null,null,null),null,null,false,false),
            Side(TeamInfo(l.id,"2",second,second,second,null,null,null,null),null,null,false,true))
    @Test fun `both teams competition and kickoff establish cross feed correspondence`() {
        assertTrue(SportsEventIdentity.same(event("a",espn),event("b",tsdb),espn,tsdb))
        assertTrue(SportsEventIdentity.same(event("a",espn),event("b",tsdb,"Chelsea","Arsenal"),espn,tsdb))
    }

    @Test fun `week index preserves both matching paths at the cutoff and across bucket boundaries`() {
        val window = 90 * 60_000L
        val query = event("query", tsdb, at = 48 * window - 1)
        val candidates = (0 until 168).map { event("hour-$it", espn, at = it * 3_600_000L) } +
            listOf(-window - 1, -window, -1L, 0L, 1L, window, window + 1).map { delta ->
                event("delta-$delta", espn, at = query.startMs + delta)
            } + listOf(query.copy(id = "no time", hasTime = false), query.copy(id = "one side", home = null))
        val index = SportsFixtureIndex(candidates)
        var inspected = 0
        val near = mutableListOf<SportsEvent>()
        index.anyNear(query) { inspected++; near.add(it); false }
        assertTrue(inspected < candidates.size / 8)
        for (matches in listOf<(SportsEvent) -> Boolean>(
            { SportsEventIdentity.same(query, it, tsdb, espn) },
            { SportsEventIdentity.guideCoversFixture(query, it, espn) }
        )) {
            val expected = candidates.filter(matches).map { it.id }.toSet()
            assertTrue("inclusive cutoff", "delta-$window" in expected && "delta-${-window}" in expected)
            assertEquals(expected, near.filter(matches).map { it.id }.toSet())
        }
    }
    @Test fun `one shared team or a different date is insufficient`() {
        assertFalse(SportsEventIdentity.same(event("a",espn),event("b",tsdb,second="Liverpool"),espn,tsdb))
        assertFalse(SportsEventIdentity.same(event("a",espn),event("b",tsdb,at=100000000),espn,tsdb))
    }

    @Test fun `followed scoreboard emits only a live increase from a known score`() {
        val fixture = event("live", espn)
        val live = fixture.copy(state = GameState.LIVE, home = fixture.home!!.copy(score = "0"),
            away = fixture.away!!.copy(score = "0"))
        val scored = live.copy(home = live.home!!.copy(score = "1"))
        val follows = SportsFollows(leagues = setOf(espn.id))
        val alerts = mutableListOf<SportsAlert>()
        noticeScoreIncreases(espn, emptyList(), listOf(live), follows, alerts::add)
        noticeScoreIncreases(espn, listOf(live), listOf(scored), follows, alerts::add)
        noticeScoreIncreases(espn, listOf(scored), listOf(scored), follows, alerts::add)
        noticeScoreIncreases(espn, listOf(live), listOf(scored), SportsFollows(), alerts::add)
        assertEquals(listOf(scored.home!!.team), alerts.map { it.team })
    }
    @Test fun `women and other competitions cannot inherit a mens event coverage`() {
        val other=tsdb.copy(name="Women's Super League",guideWords=listOf("WSL"))
        assertFalse(SportsEventIdentity.same(event("a",espn),event("b",other),espn,other))
        assertFalse(SportsEventIdentity.same(event("a",espn).copy(hasTime=false),event("b",tsdb),espn,tsdb))
    }
    @Test fun `guide duplicates require both teams and preserve women and wheelchair fixtures`() {
        val league = League("rlwc", Sport.RUGBY_LEAGUE, "Rugby League World Cup", "RLWC")
        val fixture = event("men", league, "England", "Papua New Guinea")
        fun guide(title: String) = fixture.copy(id = "guide", title = title, home = null, away = null)
        assertTrue(SportsEventIdentity.guideCoversFixture(guide("Men's Rugby League World Cup: England v Papua New Guinea"), fixture, league))
        assertFalse(SportsEventIdentity.guideCoversFixture(guide("Women's Rugby League World Cup: England v Samoa"), fixture, league))
        assertFalse(SportsEventIdentity.guideCoversFixture(guide("Women's Rugby League World Cup: England v Papua New Guinea"), fixture, league))
        assertFalse(SportsEventIdentity.guideCoversFixture(guide("Wheelchair Rugby League World Cup: England v Papua New Guinea"), fixture, league))
        assertFalse(SportsEventIdentity.guideCoversFixture(guide("England v Samoa"), fixture, league))
    }
}
