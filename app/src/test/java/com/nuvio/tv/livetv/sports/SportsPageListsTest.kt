package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class SportsPageListsTest {
    private val hour = 3_600_000L
    private val day = 10 * 24 * hour
    private val now = day + 12 * hour
    private val nba = "basketball/nba"
    private fun event(id: String, start: Long, state: GameState, source: EventSource = EventSource.ESPN) =
        SportsEvent(id, nba, start, true, state, id, null, null, null, null, source = source)

    @Test fun allEventsTvNowAndTeamRowsMatchTheReleasedExpressions() {
        val replay = event("Replay", now - hour, GameState.PRE, EventSource.GUIDE).copy(
            endMs = now + hour, guideHits = listOf("ch" to EpgProgram(now - hour, now + hour, "Replay")))
        val events = (0 until 300).map { event("game-$it", now - hour + it, GameState.LIVE) } + replay
        val known = events + event("result", now - 2 * hour, GameState.POST) +
            event("tomorrow", day + 30 * hour, GameState.PRE)
        val matches = mapOf("game-1" to listOf(io.mockk.mockk<ChannelMatch>(relaxed = true)))
        val active = setOf(nba)
        for (follows in listOf(SportsFollows(), SportsFollows(leagues = setOf(nba)))) {
            for (ready in listOf(false, true)) for (offset in 0..1) {
                val settings = SportsSettings(onlyOnMyChannels = true)
                val filters = SportsPageFilters(settings, follows, active,
                    SportsRows.followedSports(follows, SportsCatalog.sportsOrder(settings)))
                val start = day + offset * 24 * hour
                val display = SportsEventListings.display(events, now)
                val knownDisplay = SportsEventListings.display(known, now)
                val expected = SportsEventListings.onMyChannels(SportsEventListings.allEvents(knownDisplay, start, start + 24 * hour, now),
                    matches, true, ready) { isMine(it, follows) }
                for (chip in listOf(CHIP_ALL, CHIP_TV_NOW, CHIP_MINE)) {
                    val page = SportsPageLists.build(SportsPageRequest(offset, chip, "result"), filters, events, known,
                        matches, now, start, start + 24 * hour, true, ready, null)
                    assertEquals(expected, page.dayEvents)
                    assertEquals(expected.count { it.state == GameState.LIVE }, page.liveCount)
                    assertSame(matches, page.matches)
                    val shown = if (chip == CHIP_TV_NOW) SportsCurrentGuide.withFixtures(
                        SportsEventListings.tvNow(display, now), SportsCurrentGuide.fixtures(display), matches, now)
                        else expected
                    assertEquals(shown, page.shown)
                    assertEquals(SportsEventListings.entryIndex(shown), page.entryIndex)
                    assertEquals("result", page.opened?.id)
                    if (chip == CHIP_MINE) {
                        val rows = SportsRows.build(knownDisplay, follows, now) { isMine(it, follows) }
                        assertEquals(rows.heroes, page.teams!!.heroes)
                        assertEquals(rows.rows.map { it.key to it.events }, page.teams.rows.map { it.key to it.events })
                    }
                    if (!ready && offset == 0) assertEquals(300, page.liveCount)
                }
            }
        }
    }
}
