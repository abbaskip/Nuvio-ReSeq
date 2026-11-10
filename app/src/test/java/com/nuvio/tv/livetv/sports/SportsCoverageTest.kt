package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SportsCoverageTest {
    private val now = Instant.parse("2026-11-10T05:00:00Z").toEpochMilli()
    private val test = SportsCatalog.league("cricket/19430")!!
    private fun cricket(): SportsEvent = SportsFeeds.parseScoreboard(test,
        requireNotNull(javaClass.getResourceAsStream("/sports/pakistan-sri-lanka-test.json"))
            .bufferedReader().use { it.readText() }).single()

    @Test fun `Pakistan Sri Lanka Test retains live innings before the opponent bats and all five days`() {
        val event = cricket()
        assertEquals(GameState.LIVE, event.state)
        assertTrue(event.hasTime)
        assertTrue(event.endMs!! > now + 3 * 86_400_000L)
        assertTrue(event.hasScore)
        assertEquals("342/5 (85 ov)", event.home?.score)
        assertNull(event.away?.score)
        assertTrue(event.status!!.contains("Day 2"))
        assertTrue(event.subtitle!!.contains("1st Test"))
        assertEquals(listOf(event), SportsEventListings.allEvents(listOf(event), now - 3_600_000, now + 3_600_000, now))
        assertEquals(listOf(test), SportsRefreshPolicy.choose(listOf(event), listOf(test), event.id,
            emptySet(), emptySet(), true, now + 2 * 86_400_000L, emptyMap()))
        assertFalse(event.copy(state = GameState.PRE).hasScore)
        assertFalse(event.copy(home = event.home?.copy(score = null)).hasScore)
        assertEquals("20261109", SportsRefreshPolicy.scoreboardDay(test, event.startMs))
        val anchor = SportsRefreshPolicy.scoreboardAnchor(listOf(event), test, event.id, emptySet(), true, now)
        assertEquals(now, anchor)
        assertEquals("20261110", SportsRefreshPolicy.scoreboardDay(test, anchor))
    }

    @Test fun `day two cricket matches the current Fox Sports 505 session`() {
        val event = cricket()
        val program = EpgProgram(now - 30 * 60_000, now + 6 * 3_600_000, "Live: Cricket: Pakistan v Sri Lanka", "1st Test, day 2")
        val channel = channel("505")
        val db = mockk<EpgDatabase>()
        every { db.findByWords(any(), any(), any(), any(), any(), any()) } returns listOf(channel.key to program)
        val matches = SportsMatcher(db).match(SportsMatcher.Input(listOf(event), listOf(channel), emptyMap(), 0,
            SportsSettings(region = "AU"), now)).matches[event.id].orEmpty()
        assertTrue(matches.any { it.channel.key == channel.key && it.sure && it.program == program })
    }

    @Test fun `all motorsport and its championships can be followed without a discovery request`() {
        val browse = SportsCatalog.browseOrdered(SportsSettings(region = "AU"))
        val all = browse.single { it.id == "guide/motorsport" }
        listOf("4489", "4486", "4487", "4409", "4413").forEach { id ->
            val league = browse.single { it.id == "tsdb/$id" }
            assertEquals(Sport.RACING, league.sport)
            assertTrue(SportsTaxonomy.includesLeague(all.id, league.id))
            assertFalse(SportsTaxonomy.accepts(league, setOf("legacy/RACING")))
        }
        assertEquals("Supercars", SportsCatalog.league("tsdb/4489")?.name)
        assertTrue(SportsTaxonomy.includesLeague(all.id, "racing/f1"))
        assertFalse(SportsTaxonomy.includesLeague(all.id, test.id))
    }

    @Test fun `every provider sport parses and discovery includes all existing app categories`() {
        FreeSportsDb.discoverySports.values.flatten().distinct().filter { it != "Rugby" }.forEachIndexed { i, sport ->
            assertEquals(sport, 1, FreeSportsDb.leagues("""{"leagues":[{"idLeague":"$i","strLeague":"$sport Competition","strSport":"$sport"}]}""").size)
        }
        listOf(Sport.RACING, Sport.DARTS, Sport.VOLLEYBALL, Sport.NETBALL, Sport.AUSSIE_RULES,
            Sport.GAELIC, Sport.LACROSSE, Sport.OTHER).forEach { assertTrue(it.name, it in FreeSportsDb.discoverySports) }
        // All 17 sports in ESPN's public sport index have a scoreboard-backed entry.
        listOf("australian-football", "baseball", "basketball", "cricket", "field-hockey", "football",
            "golf", "hockey", "lacrosse", "mma", "racing", "rugby", "rugby-league", "soccer",
            "tennis", "volleyball", "water-polo").forEach { sport ->
            assertTrue(sport, SportsCatalog.leagues.any { it.espnPath?.startsWith("$sport/") == true })
        }
    }

    @Test fun `bowls cornhole and ten pin appear in TV Now with explicit live flags only`() {
        val titles = listOf("Live: Bowls Premier League: BPL 24 day 1", "Live: Corn Hole Championship", "Live: Ten Pin Bowling", "World Bowls Championship")
        val db = mockk<EpgDatabase>()
        every { db.fingerprint() } returns "coverage"
        every { db.contentRevision() } returns "1"
        every { db.visitPrograms(any(), any(), any()) } answers {
            titles.forEachIndexed { i, title -> thirdArg<(String, EpgProgram) -> Unit>().invoke("506",
                EpgProgram(now - (i + 1) * 60_000, now + 3_600_000, title)) }
        }
        val events = SportsMatcher(db).guideEvents(now - 3_600_000, now + 3_600_000, SportsCatalog.all,
            emptyList(), mapOf("506" to channel("506")), 0, now)
        assertEquals(4, SportsEventListings.tvNow(events, now).size)
        assertEquals(3, events.count { it.liveLabel == "LIVE" })
        assertEquals(1, events.count { it.liveLabel == "ON AIR" })
        assertTrue(events.all { SportsCatalog.league(it.leagueId)?.sport == Sport.OTHER })
        val bowls = "taxonomy/lawn-bowls"
        assertTrue(SportsTaxonomy.includesLeague(bowls, bowls))
        assertFalse(SportsTaxonomy.includesLeague(bowls, "taxonomy/cornhole"))
        assertFalse(SportsTaxonomy.accepts(SportsCatalog.league(bowls)!!, setOf("lawn-bowls")))
        assertTrue(SportsTaxonomy.accepts(SportsCatalog.league("taxonomy/cornhole")!!, setOf("lawn-bowls")))
    }

    private fun channel(number: String) = LiveChannel(number, "playlist", "Test", "AU: Fox Sports $number HD",
        null, null, null, "sports", "Sports", 1, "https://example.invalid/live")

    @Test fun `legacy NASCAR and newly discovered motorsport guide names remain supported`() {
        val db = mockk<EpgDatabase>()
        every { db.fingerprint() } returns "motorsport"
        every { db.contentRevision() } returns "1"
        val discovered = League("tsdb/99999", Sport.RACING, "New Racing Championship", "NRC",
            kind = LeagueKind.EVENT, guideWords = listOf("New Racing Championship"))
        every { db.visitPrograms(any(), any(), any()) } answers {
            listOf("Live: NASCAR", "Live: New Racing Championship").forEachIndexed { i, title ->
                thirdArg<(String, EpgProgram) -> Unit>().invoke("506",
                    EpgProgram(now - (i + 1) * 60_000, now + 3_600_000, title))
            }
        }
        val events = SportsMatcher(db).guideEvents(now - 3_600_000, now + 3_600_000,
            SportsCatalog.all + discovered, emptyList(), mapOf("506" to channel("506")), 0, now)
        assertEquals(setOf("racing/nascar-premier", discovered.id), events.map { it.leagueId }.toSet())
    }
}
