package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class SportsQuickMatchingTest {
    private val now = 1_793_318_400_000L
    private val league = "basketball/nba"
    private fun side(id: String, name: String, home: Boolean) = Side(
        TeamInfo(league, id, name, name, id, null, null, null, null), null, null, false, home)
    private val event = SportsEvent("espn:basketball/nba:1", league, now, true, GameState.LIVE,
        "Cleveland Cavaliers at Atlanta Hawks", null, null,
        side("CLE", "Cleveland Cavaliers", false), side("ATL", "Atlanta Hawks", true), broadcasts = listOf("NBA TV"))
    private fun channel(id: String, name: String) = LiveChannel(id, "playlist", "Test", name, null, null, null,
        "sports", "NBA Basketball", 1, "https://example.invalid/live")
    @Test fun `names broadcasters and saved choices appear without accessing an importing guide`() {
        val db = mockk<EpgDatabase>()
        val channels = listOf(channel("event", "NBA CLE vs ATL HD"), channel("network", "US: NBA TV HD"), channel("manual", "My channel"))
        val settings = SportsSettings(eventChannels = mapOf(event.id to "manual|$now"))
        val results = SportsMatcher(db).match(SportsMatcher.Input(listOf(event), channels, emptyMap(), 0,
            settings, now, includeGuide = false)).matches[event.id].orEmpty()
        assertEquals(setOf("event", "network", "manual"), results.map { it.channel.key }.toSet())
        assertEquals("manual", results.first().channel.key)
        assertFalse(results.first { it.channel.key == "network" }.sure)
        verify(exactly = 0) { db.findByWords(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { db.range(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { db.visitPrograms(any(), any(), any()) }
    }
    @Test fun `quick replay suggestions preserve archive eligibility and blocking`() {
        val db = mockk<EpgDatabase>()
        val fixture = event.copy(startMs = now - 36 * 3_600_000, state = GameState.POST)
        val channels = listOf(channel("archive", "US: NBA TV HD").copy(catchup = CatchupInfo("xc", null, 2)),
            channel("expired", "US: NBA TV HD").copy(catchup = CatchupInfo("xc", null, 1)),
            channel("blocked", "US: NBA TV HD"))
        val results = SportsMatcher(db).match(SportsMatcher.Input(listOf(fixture), channels, emptyMap(), 0,
            SportsSettings(blocked = setOf("${fixture.id}|blocked")), now, replay = true, includeGuide = false)).matches[fixture.id].orEmpty()
        assertEquals(setOf("archive", "expired"), results.map { it.channel.key }.toSet())
        assertTrue(results.first { it.channel.key == "archive" }.catchup)
        assertFalse(results.first { it.channel.key == "expired" }.catchup)
        assertTrue(results.none { it.sure || it.program != null })
        verify(exactly = 0) { db.findByWords(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { db.range(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { db.visitPrograms(any(), any(), any()) }
    }

    @Test fun `compact broadcaster lookup retains aliases and rejects other numbered or plus services`() {
        val cases = listOf(
            Triple("Fox Sports 501", "AU: FOXSPORTS501 FHD", true),
            Triple("Sky Sports Main Event", "UK: SkySportsMainEvent UHD", true),
            Triple("TNT Sports 1", "UK: BT Sport 1 HD", true),
            Triple("BBC One", "UK: BBC1 HD", true),
            Triple("Seven Network", "AU: Channel7HD", true),
            Triple("Nine Network", "AU: 9HD", true),
            Triple("ESPN2", "US: ESPN2 HD", true),
            Triple("Fox Sports 501", "AU: FOXSPORTS502 HD", false),
            Triple("Sky Sports 1", "UK: Sky Sports 10 HD", false),
            Triple("ESPN", "US: ESPN+ HD", false))
        for ((network, name, expected) in cases) {
            val db = mockk<EpgDatabase>()
            val fixture = event.copy(broadcasts = listOf(network))
            val results = SportsMatcher(db).match(SportsMatcher.Input(listOf(fixture), listOf(channel("test", name)),
                emptyMap(), 0, SportsSettings(), now, includeGuide = false)).matches[fixture.id].orEmpty()
            assertEquals("$network / $name", expected, results.isNotEmpty())
            verify(exactly = 0) { db.findByWords(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { db.range(any(), any(), any(), any(), any()) }
        }
    }
    @Test fun `background quick refresh keeps opened event guide matches but not removed or blocked channels`() {
        val programme = com.nuvio.tv.livetv.model.EpgProgram(now, now + 7_200_000, event.title)
        val valid = channel("valid", "Premier Sports 1")
        val removed = channel("removed", "Old channel")
        val blocked = channel("blocked", "Blocked channel")
        fun match(ch: LiveChannel) = ChannelMatch(ch, 100, "Guide", programme, null, false)
        val previous = mapOf(event.id to listOf(match(valid), match(removed), match(blocked)), "old-event" to listOf(match(valid)))
        val input = SportsMatcher.Input(listOf(event), listOf(valid, blocked), emptyMap(), 0,
            SportsSettings(blocked = setOf("${event.id}|blocked")), now, includeGuide = false)
        val merged = SportsMatcher.keepGuideMatches(previous, emptyMap(), setOf(event.id), input)
        assertEquals(setOf(event.id), merged.keys)
        assertEquals(listOf("valid"), merged[event.id]!!.map { it.channel.key })
        assertTrue(SportsMatcher.keepGuideMatches(previous, emptyMap(), emptySet(), input).isEmpty())
    }

    @Test fun `new manual choice outranks retained guide result and unverified stale suggestions are removed`() {
        val ch = channel("test", "My channel")
        val programme = com.nuvio.tv.livetv.model.EpgProgram(now, now + 7_200_000, event.title)
        val guide = ChannelMatch(ch, 100, "Guide", programme, null, false)
        val manual = guide.copy(score = 200, how = "Your pick")
        val stale = guide.copy(channel = channel("stale", "Radio"), score = 40, program = null)
        val input = SportsMatcher.Input(listOf(event), listOf(ch, stale.channel), emptyMap(), 0, SportsSettings(), now)
        val merged = SportsMatcher.keepGuideMatches(mapOf(event.id to listOf(guide, stale)),
            mapOf(event.id to listOf(manual)), setOf(event.id), input)
        assertEquals(listOf(manual), merged[event.id])
    }

    @Test fun `quick refresh retains all strong guide matches beyond suggestion limit`() {
        val programme = com.nuvio.tv.livetv.model.EpgProgram(now, now + 7_200_000, event.title)
        val channels = (1..15).map { channel("$it", "Sports channel $it") }
        val previous = mapOf(event.id to channels.map { ChannelMatch(it, 100, "Guide", programme, null, false) })
        val input = SportsMatcher.Input(listOf(event), channels, emptyMap(), 0,
            SportsSettings(blocked = setOf("${event.id}|15")), now, includeGuide = false)
        val matches = SportsMatcher.keepGuideMatches(previous, emptyMap(), setOf(event.id), input)[event.id].orEmpty()
        assertEquals(14, matches.size)
        assertTrue(matches.none { it.channel.key == "15" })
    }

    @Test fun `unverified network suggestions remain bounded`() {
        val channels = (1..15).map { channel("$it", "US: NBA TV HD") }
        val matches = SportsMatcher(mockk<EpgDatabase>()).match(SportsMatcher.Input(listOf(event), channels,
            emptyMap(), 0, SportsSettings(), now, includeGuide = false)).matches[event.id].orEmpty()
        assertTrue(matches.isNotEmpty() && matches.size <= 8)
        assertTrue(matches.none { it.sure })
    }

    @Test fun `ranking reuse preserves region language and changed category preferences`() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            val settings = SportsSettings(region = "US", preferredGroups = setOf("Favourites"))
            val rank = SportsMatcher.preferenceRank(settings)
            val local = channel("us", "US: NBA TV HD")
            val spanish = channel("es", "ES: Sports HD")
            val preferred = spanish.copy(group = "Favourites")
            repeat(20) {
                assertEquals(1, rank(local))
                assertEquals(-1, rank(spanish))
                assertEquals(2, rank(preferred))
            }
            assertEquals(-1, SportsMatcher.preferenceRank(settings.copy(preferredGroups = emptySet()))(preferred))
            val matches = listOf(local, spanish, preferred.copy(key = "preferred")).map {
                ChannelMatch(it, 100, "Guide", null, "HD", false)
            }
            for (best in listOf(true, false)) {
                assertEquals(listOf("preferred", "us", "es"), SportsMatcher.sortMatches(matches, best, rank).map { it.channel.key })
            }
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test fun `team candidate pruning preserves names abbreviations radio and dedicated feeds`() {
        val fixture = event.copy(broadcasts = emptyList())
        val channels = listOf(channel("names", "Cleveland Cavaliers vs Atlanta Hawks"),
            channel("abbr", "NBA CLE vs ATL HD"), channel("team", "NBA Cleveland Cavaliers"),
            channel("radio", "Radio: Cleveland Cavaliers"), channel("league", "NBA League Pass"))
        val matches = SportsMatcher(mockk<EpgDatabase>()).match(SportsMatcher.Input(listOf(fixture), channels,
            emptyMap(), 0, SportsSettings(), now, includeGuide = false)).matches[event.id].orEmpty()
        assertEquals(setOf("names", "abbr", "team", "radio"), matches.map { it.channel.key }.toSet())
        assertTrue(matches.filter { it.channel.key in setOf("names", "abbr") }.all { it.sure })
        assertTrue(matches.filter { it.channel.key in setOf("team", "radio") }.none { it.sure })
    }

}
