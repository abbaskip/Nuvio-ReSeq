package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class SportsWorldCupTest {
    private val originalExtra = SportsCatalog.extra
    @After fun restoreCatalogue() { SportsCatalog.setExtra(originalExtra) }

    @Test fun `provider country names match short guide pairings on actual broadcasters`() {
        val league = League("tsdb/4417", Sport.RUGBY_LEAGUE, "Rugby League World Cup", "RLWC", guideWords = listOf("Rugby League World Cup"))
        SportsCatalog.setExtra(SportsCatalog.extra.filterNot { it.id == league.id } + league)
        val start = 1_793_349_900_000L
        fun side(id: String, name: String) = Side(TeamInfo(league.id, id, name, name, id, null, null, null, null), null, null, false, false)
        val event = SportsEvent("world-cup", league.id, start, true, GameState.PRE,
            "England Rugby League vs Papua New Guinea Rugby League", null, null,
            side("eng", "England Rugby League"), side("png", "Papua New Guinea Rugby League"))
        val programme = EpgProgram(start, start + 7_200_000, "Men's Rugby League World Cup", "Pool B: England v PNG. Live coverage.")
        val channels = listOf("7mate Perth", "UK: Premier Sports 1 HD").mapIndexed { i, name ->
            LiveChannel("$i", "playlist", "Test", name, null, null, null, "sports", "Sports", i, "https://example.invalid")
        }
        val db = mockk<EpgDatabase>(relaxed = true)
        every { db.findByWords(any(), any(), any(), any(), any(), any()) } returns channels.map { it.key to programme }
        val matches = SportsMatcher(db).match(SportsMatcher.Input(listOf(event), channels, emptyMap(), 0, SportsSettings(), start - 3_600_000)).matches[event.id].orEmpty()
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.sure && it.program == programme })
        val names = SportsCatalog.teamNames(event.away!!.team, league).first
        assertTrue("england" in names)
        assertFalse("england" in SportsCatalog.teamNames(event.away!!.team, league.copy(sport = Sport.RUGBY)).first)
    }

    @Test fun `country radio fallback never matches a different team containing the country name`() {
        val league = League("tsdb/4417", Sport.RUGBY_LEAGUE, "Rugby League World Cup", "RLWC")
        SportsCatalog.setExtra(SportsCatalog.extra.filterNot { it.id == league.id } + league)
        val now = 1_793_349_900_000L
        fun side(id: String, name: String) = Side(TeamInfo(league.id, id, name, name, id, null, null, null, null), null, null, false, false)
        val event = SportsEvent("world-cup", league.id, now, true, GameState.PRE,
            "England vs Papua New Guinea", null, null,
            side("eng", "England Rugby League"), side("png", "Papua New Guinea Rugby League"))
        val channels = listOf("Radio: England", "Radio: New England Patriots").mapIndexed { i, name ->
            LiveChannel("$i", "playlist", "Test", name, null, null, null, "radio", "Radio", i, "https://example.invalid")
        }
        val matches = SportsMatcher(mockk<EpgDatabase>()).match(SportsMatcher.Input(listOf(event), channels,
            emptyMap(), 0, SportsSettings(), now, includeGuide = false)).matches[event.id].orEmpty()
        assertEquals(listOf("Radio: England"), matches.map { it.channel.name })
        assertTrue(matches.none { it.sure })
    }

    @Test fun `following rugby league includes womens guide fixtures but not union`() {
        val women = requireNotNull(SportsTaxonomy.classifyProgramme("Women's Rugby League World Cup : Pool A: England v Samoa", null))
        val id = "taxonomy/" + women.id
        assertTrue(SportsTaxonomy.includesLeague("guide/rugby-league", id))
        assertTrue(SportsTaxonomy.includesLeague("taxonomy/rugby-league", id))
        assertFalse(SportsTaxonomy.includesLeague("taxonomy/rugby-union", id))
        assertFalse(SportsTaxonomy.includesLeague("tsdb/4416", id))
        val event = SportsEvent("women", id, 1000, true, GameState.PRE, "England v Samoa", null, null, null, null)
        val follows = SportsFollows(leagues = setOf("guide/rugby-league"))
        assertTrue(isMine(event, follows))
        assertTrue(SportsRows.build(listOf(event), follows, 0) { isMine(it, follows) }.rows.any {
            it.key == "l:guide/rugby-league" && event in it.events
        })
    }
    @Test fun `local guide matches survive more than eight broadcaster quality variants`() {
        val league = League("tsdb/5806", Sport.RUGBY_LEAGUE, "Rugby League World Cup", "RLWC")
        SportsCatalog.setExtra(SportsCatalog.extra.filterNot { it.id == league.id } + league)
        val start = 1_793_351_100_000L
        fun side(id: String, name: String) = Side(TeamInfo(league.id, id, name, name, id, null, null, null, null), null, null, false, false)
        val event = SportsEvent("world-cup", league.id, start, true, GameState.PRE,
            "England Rugby League vs Papua New Guinea Rugby League", null, null,
            side("eng", "England Rugby League"), side("png", "Papua New Guinea Rugby League"))
        val programme = EpgProgram(1_793_349_000_000L, 1_793_358_000_000L,
            "Rugby League World Cup 2026 : England v Papua New Guinea", "Kick off at 8:05pm AEDT.")
        fun channel(id: String, name: String, group: String) = LiveChannel(id, "playlist", "Test", name,
            null, null, null, group, group, 1, "https://example.invalid")
        val local = channel("local", "7mate Perth", "AU | Locals")
        val unrelated = channel("unrelated", "Other local channel", "AU | Locals")
        val variants = (1..12).map { channel("variant-$it", "Premier Sports 1 FHD $it", "Sports") }
        val channels = variants + local + unrelated
        val db = mockk<EpgDatabase>(relaxed = true)
        every { db.findByWords(any(), any(), any(), any(), any(), any()) } returns
            (variants + local).map { it.key to programme } +
            (unrelated.key to programme.copy(title = "France v Samoa", description = null))
        for (bestFirst in listOf(true, false)) {
            val matches = SportsMatcher(db).match(SportsMatcher.Input(listOf(event), channels, emptyMap(), 0,
                SportsSettings(bestChannelFirst = bestFirst), start - 3_600_000)).matches[event.id].orEmpty()
            assertEquals(13, matches.size)
            assertTrue(matches.all { it.sure })
            assertEquals(programme, matches.single { it.channel.key == local.key }.program)
            assertFalse(matches.any { it.channel.key == unrelated.key })
        }
    }

}
