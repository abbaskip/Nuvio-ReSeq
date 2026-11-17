package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class SportsTvNowDiscoveryTest {
    private val now = 1_794_312_000_000L
    private val hour = 3_600_000L
    private val db = mockk<EpgDatabase> {
        every { fingerprint() } returns "committed-guide"
        every { contentRevision() } returns "unchanged-during-import"
    }
    private val channel = LiveChannel("506", "playlist", "Test", "AU: Fox Sports 506 HD",
        null, null, null, "sports", "Sports", 1, "https://example.invalid")

    @Test fun repeatedSchedulesRetainEveryAiringAndChannelWhileSharingProgrammeValues() {
        val channels = (0..10000).associate { i -> "$i" to channel.copy(key = "$i", name = "Fixture Cricket $i") }
        val first = EpgProgram(now, now + hour, "Australia vs England - Live Test Cricket", "Test cricket coverage")
        every { db.visitSportsPrograms(now, now + 2 * hour, Long.MIN_VALUE, any(), any()) } answers {
            val collect = lastArg<(String, EpgProgram) -> Unit>()
            for (key in channels.keys) {
                collect(key, first.copy())
                collect(key, first.copy(startMs = now + hour, stopMs = now + 2 * hour))
            }
            collect("0", first.copy()) // The existing channel/start duplicate rule remains intact.
        }
        val events = SportsMatcher(db, limits=SportsMatchLimits.UNCAPPED).guideEvents(now, now + 2 * hour, SportsCatalog.all,
            emptyList(), channels, 0, now)
        assertEquals(2, events.size)
        events.forEach { event ->
            // The released loop-channel rule deliberately excludes the channel named "247".
            assertEquals(channels.keys.filterNot { it == "247" }, event.guideHits.map { it.first })
            assertSame(event.guideHits.first().second, event.guideHits.last().second)
        }
    }

    @Test fun longReplaysAndUnconfirmedSessionsRemainCurrentTvEvenWithoutALiveFixture() {
        val programmes = listOf(
            EpgProgram(now - 8 * hour, now + hour, "Replay: Snooker: Ronnie O'Sullivan v Judd Trump"),
            EpgProgram(now - 8 * hour, now + hour, "Replay: Tennis: Australian Open"),
            EpgProgram(now - 8 * hour, now + hour, "PGA Tour Golf: Hero World Challenge"))
        val channels = programmes.indices.associate { "$it" to channel.copy(key = "$it") }
        every { db.visitSportsPrograms(now - 30 * hour, now + 1, now, any(), any()) } answers {
            programmes.forEachIndexed { i, programme -> lastArg<(String, EpgProgram) -> Unit>().invoke("$i", programme) }
        }
        val found = SportsMatcher(db).tvNowEvents(SportsCatalog.all, emptyList(), channels, 0, now)
        assertEquals(found.map { it.title }.toString(), 3, found.size)
        assertEquals(2, found.count { it.isReplay })
        assertTrue(found.all { it.source == EventSource.GUIDE })
        assertTrue(SportsEventListings.allEvents(found, now - hour, now + hour, now).isEmpty())
        verify(exactly = 0) { db.visitPrograms(any(), any(), any()) }
    }

    @Test fun currentSportsIncludeLongBowlsSessionsReplaysAndUnconfirmedCoverageWithoutFixtures() {
        val programmes = listOf(
            EpgProgram(now - 10 * hour, now + hour, "Live: Bowls Premier League: BPL 24 day 1"),
            EpgProgram(now - hour, now + hour, "NBA Replay: Lakers vs Celtics"),
            EpgProgram(now - hour, now + hour, "Premier League Highlights"),
            EpgProgram(now - hour, now + hour, "Formula 1: Singapore Grand Prix"),
            EpgProgram(now - hour, now + hour, "MasterChef"))
        val keys = listOf("506", "nba", "football", "f1", "cooking")
        val channels = keys.associateWith { channel.copy(key = it) }
        every { db.visitSportsPrograms(now - 30 * hour, now + 1, now, any(), any()) } answers {
            programmes.forEachIndexed { i, programme -> lastArg<(String, EpgProgram) -> Unit>().invoke(keys[i], programme) }
        }
        val found = SportsMatcher(db).tvNowEvents(SportsCatalog.all, emptyList(), channels, 0, now)
        assertEquals(found.map { it.title }.toString(), 4, SportsEventListings.tvNow(found, now).size)
        assertEquals("LIVE", found.single { it.title.contains("Bowls") }.liveLabel)
        assertEquals("REPLAY", found.single { it.title.contains("NBA") }.liveLabel)
        assertEquals("REPLAY", found.single { it.title.contains("Highlights") }.liveLabel)
        assertEquals("ON AIR", found.single { it.title.contains("Formula 1") }.liveLabel)
        assertTrue(SportsEventListings.allEvents(found, now - hour, now + hour, now).isEmpty())
        verify(exactly = 0) { db.visitPrograms(any(), any(), any()) }
        val matches = SportsMatcher(db).match(SportsMatcher.Input(found, channels.values.toList(), emptyMap(), 0,
            SportsSettings(), now, includeGuide = false)).matches
        assertEquals("506", matches[found.single { it.title.contains("Bowls") }.id]!!.single().channel.key)
    }

    @Test fun currentReadIgnoresAnEmptyWholeDayCacheAndHonorsGuideOffsetAndProgrammeChanges() {
        every { db.visitSportsPrograms(any(), any(), any(), any(), any()) } just Runs
        val matcher = SportsMatcher(db)
        assertTrue(matcher.guideEvents(now - 12 * hour, now + 12 * hour, SportsCatalog.all,
            emptyList(), mapOf("506" to channel), hour, now).isEmpty())
        var title = "Live: Bowls Premier League: BPL 24 day 1"
        var start = now - 11 * hour
        every { db.visitSportsPrograms(now - 31 * hour, now - hour + 1, now - hour, any(), any()) } answers {
            lastArg<(String, EpgProgram) -> Unit>().invoke("506", EpgProgram(start, now + hour, title))
        }
        fun read() = matcher.tvNowEvents(SportsCatalog.all, emptyList(), mapOf("506" to channel), hour, now).single()
        val bowls = read()
        assertEquals(now - 10 * hour, bowls.startMs)
        assertEquals(now + 2 * hour, bowls.endMs)
        title = "NBA Replay: Lakers vs Celtics"
        start = now - 2 * hour
        assertTrue(read().title.contains("NBA"))
        verify(exactly = 2) { db.visitSportsPrograms(now - 31 * hour, now - hour + 1, now - hour, any(), any()) }
    }
}
