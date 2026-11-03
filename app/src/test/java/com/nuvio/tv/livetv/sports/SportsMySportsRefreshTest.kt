package com.nuvio.tv.livetv.sports

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test

class SportsMySportsRefreshTest {
    private val nba = SportsCatalog.league("basketball/nba")!!
    private val start = Instant.parse("2026-11-03T03:00:00Z").toEpochMilli()
    private val now = start + 5 * 3_600_000L
    private val game = SportsEvent("espn:basketball/nba:401909921", nba.id, start, true,
        GameState.PRE, "Miami Heat at Los Angeles Lakers", null, "Scheduled", null, null)
    private val next = game.copy(id = "next", startMs = start + 2 * 86_400_000L, title = "Lakers at Kings")
    private val follows = SportsFollows.fromJson("""{"leagues":["basketball/nba"],"setUp":true}""")

    @Test fun `migrated whole NBA follow counts in My Sports and All Events channel filtering`() {
        assertTrue(follows.leagues.isEmpty())
        assertTrue(nba.id in follows.pins)
        assertTrue(isMine(game, follows))
        val page = SportsRows.build(listOf(game, next), follows, now, Sport.BASKETBALL) { isMine(it, follows) }
        assertEquals(listOf(next.id), page.heroes.map { it.id })
        assertTrue(page.rows.any { it.kind == SportsRow.Kind.UPCOMING && next in it.events })
    }

    @Test fun `today in Sydney can still need yesterday's ESPN scoreboard`() {
        assertEquals("2026-11-03", Instant.ofEpochMilli(start).atZone(ZoneId.of("Australia/Sydney")).toLocalDate().toString())
        val due = SportsRefreshPolicy.choose(listOf(game, next), listOf(nba), next.id, emptySet(),
            setOf(nba.id), true, now, emptyMap())
        assertEquals(listOf(nba), due)
        val anchor = SportsRefreshPolicy.scoreboardAnchor(listOf(game, next), nba, next.id, emptySet(), true, now)
        assertEquals(start, anchor)
        assertEquals("20261102", DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("America/New_York")).format(Instant.ofEpochMilli(anchor)))
        assertEquals("20261103", DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("America/New_York")).format(Instant.ofEpochMilli(now)))
    }

    @Test fun `finished game returns to the league timeline without losing next games`() {
        val result = game.copy(state = GameState.POST, status = "Final")
        val page = SportsRows.build(listOf(result, next), follows, now, Sport.BASKETBALL) { isMine(it, follows) }
        val row = page.rows.single { it.kind == SportsRow.Kind.LEAGUE }
        assertEquals(listOf(result.id, next.id), row.events.map { it.id })
        assertEquals(1, row.startIndex)
        assertEquals(listOf(next.id), page.heroes.map { it.id })
    }

    @Test fun `result catchup does not add polling while watching outside Sports`() {
        assertTrue(SportsRefreshPolicy.choose(listOf(game), listOf(nba), null, emptySet(),
            setOf(nba.id), false, now, emptyMap()).isEmpty())
    }

    @Test fun `finished games qualify for replay before the old five hour threshold`() {
        val recent = game.copy(startMs = now - 2 * 3_600_000L, state = GameState.POST)
        assertTrue(sportsReplayLookup(recent, now))
        assertFalse(sportsReplayLookup(recent.copy(state = GameState.LIVE), now))
        assertFalse(sportsReplayLookup(next, now))
    }

    @Test fun `cricket has no unverified external calendar capable of inventing an Ashes season`() {
        val cricket = SportsCatalog.league("guide/cricket")!!
        assertTrue(cricket.guideOnly)
        assertNull(cricket.fixtureSlug)
        assertTrue(cricket.guideWords.contains("Cricket"))
    }
}
