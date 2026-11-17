package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class SportsQuickMatchingTest {
    private fun SportsMatcher.matchIncrementally(input: SportsMatcher.Input) = matchProgressively(input, null, {})
    @Test fun `4400 repeated participant fixtures retain the individual channel matches and order`() {
        val db = mockk<EpgDatabase>()
        val channels = listOf(channel("event", "NBA CLE vs ATL HD"), channel("network", "US NBA TV HD"),
            channel("other", "Other Sports HD"))
        val settings = SportsSettings()
        val fixture = List(4400) { i -> event.copy(id = "fixture-$i", title = "Fixture game $i",
            startMs = now + (i % 12) * 3_600_000L) }
        val expected = fixture.associate { e -> e.id to SportsMatcher(db).match(SportsMatcher.Input(
            listOf(e), channels, emptyMap(), 0, settings, now, includeGuide = false)).matches.getValue(e.id) }
        val matcher = SportsMatcher(db, incremental = true)
        val input = SportsMatcher.Input(fixture, channels, emptyMap(), 0, settings, now, includeGuide = false)
        assertEquals(expected, matcher.matchIncrementally(input).matches)
        assertEquals(expected, matcher.matchIncrementally(input).matches)
        verify(exactly = 0) { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `incremental pass equals full matching and revisits programmes at their boundary`() {
        val db = mockk<EpgDatabase>(relaxed = true)
        var revision = "one"
        every { db.contentRevision() } answers { revision }
        every { db.nextSportsBoundary(any()) } answers { if (firstArg<Long>() < now + 60_000) now + 60_000 else now + 120_000 }
        every { db.visitMatchPrograms(any(),any(),any(),any(),any(),any()) } answers {
            lastArg<(String,com.nuvio.tv.livetv.model.EpgProgram)->Unit>()("network",
                com.nuvio.tv.livetv.model.EpgProgram(now-30*60_000,now+3_600_000,event.title))
        }
        val channels = listOf(channel("network", "NBA TV HD"))
        val memo = SportsMatcher(db, incremental = true)
        val reference = SportsMatcher(db)
        fun input(at: Long) = SportsMatcher.Input(listOf(event.copy(startMs = now - 30 * 60_000)),
            channels, emptyMap(), 0, SportsSettings(), at)
        assertEquals(reference.match(input(now)).matches, memo.matchIncrementally(input(now)).matches)
        memo.match(SportsMatcher.Input(listOf(event.copy(id = "opened-game")), channels, emptyMap(), 0, SportsSettings(), now))
        io.mockk.clearMocks(db, answers = false)
        assertEquals(memo.matchIncrementally(input(now)).matches, memo.matchIncrementally(input(now + 1)).matches)
        verify(exactly = 0) { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) }
        memo.matchIncrementally(input(now + 3_600_000))
        verify(exactly = 1) { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) }
        revision = "two"
        memo.matchIncrementally(input(now + 3_600_001))
        verify(exactly = 2) { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `guide result preparation checks cancellation inside the slot`() {
        val db = mockk<EpgDatabase>()
        var queried = false
        every { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) } answers {
                val rows: List<Pair<String, com.nuvio.tv.livetv.model.EpgProgram>> = run {
            queried = true
            List(500) { "network" to com.nuvio.tv.livetv.model.EpgProgram(now, now + 3_600_000,
                "NBA Basketball", description = event.title) }

                }
                rows.forEach { (ch,p) -> lastArg<(String,com.nuvio.tv.livetv.model.EpgProgram)->Unit>()(ch,p) }
            }
        try {
            SportsMatcher(db).match(SportsMatcher.Input(listOf(event), listOf(channel("network", "NBA TV HD")),
                emptyMap(), 0, SportsSettings(), now)) {
                if (queried) throw kotlinx.coroutines.CancellationException("Playback started")
            }
            fail("Guide scoring ignored cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertTrue(queried)
        }
    }
    @Test fun `large channel preparation observes cancellation before completing`() {
        val db = mockk<EpgDatabase>()
        val channels = (1..1000).map { channel("channel$it", "US NBA TV $it HD") }
        var checks = 0
        try {
            SportsMatcher(db).match(SportsMatcher.Input(listOf(event), channels, emptyMap(), 0,
                SportsSettings(), now, includeGuide = false)) {
                if (++checks == 3) throw kotlinx.coroutines.CancellationException("Playback started")
            }
            fail("Matcher ignored cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(3, checks)
        }
    }
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
        verify(exactly = 0) { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) }
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
        verify(exactly = 0) { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) }
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
            verify(exactly = 0) { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) }
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

    @Test fun retainedHigherScoreTiesKeepTheOrderOfTheWinningEntries() {
        val a = channel("a", "ABC")
        val b = channel("b", "DEF")
        val programme = com.nuvio.tv.livetv.model.EpgProgram(now, now + 7200000, event.title)
        val weak = ChannelMatch(a, 40, "Weak", null, null, false)
        val strongB = ChannelMatch(b, 100, "Fresh", programme, null, false)
        val strongA = strongB.copy(channel = a, how = "Guide")
        for (bestFirst in listOf(true, false)) {
            val input = SportsMatcher.Input(listOf(event), listOf(a, b), emptyMap(), 0,
                SportsSettings(bestChannelFirst = bestFirst), now)
            val result = SportsMatcher.keepGuideMatches(mapOf(event.id to listOf(strongA, strongB.copy(how = "Duplicate"))),
                mapOf(event.id to listOf(weak, strongB)), setOf(event.id), input)
            assertEquals(listOf(strongB, strongA), result[event.id])
        }
    }

    @Test fun repeatedEventRefreshPreservesRankingAcrossChangedGroups() {
        val programme = com.nuvio.tv.livetv.model.EpgProgram(now, now + 7200000, event.title)
        val channels = List(160) { i -> channel("key-$i", "US: Sports $i").copy(group = "group-" + (i % 8)) }
        val matches = channels.map { ChannelMatch(it, 100, "Guide", programme, null, false) }
        val previous = (1..80).associate { "event-$it" to matches }
        // Allocation evidence is optional on JVMs without the HotSpot management extension.
        val allocatedBytes = runCatching {
            val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
            val read = { method.invoke(bean) as Long }
            read
        }.getOrNull()
        for (preferred in listOf("group-7", "group-3")) {
            val settings = SportsSettings(preferredGroups = setOf(preferred))
            val input = SportsMatcher.Input(listOf(event), channels, emptyMap(), 0, settings, now)
            val expected = SportsMatcher.sortMatches(matches, settings.bestChannelFirst, SportsMatcher.preferenceRank(settings))
            val before = allocatedBytes?.invoke()
            val actual = SportsMatcher.keepGuideMatches(previous, emptyMap(), previous.keys, input)
            if (before != null && before >= 0) println("Sports merge allocated bytes=" + (allocatedBytes() - before))
            assertEquals(previous.keys, actual.keys)
            actual.values.forEach { assertEquals(expected, it) }
        }
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
