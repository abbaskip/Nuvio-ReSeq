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

    @Test fun currentSportsIncludeLongBowlsSessionsReplaysAndUnconfirmedCoverageWithoutFixtures() {
        val programmes = listOf(
            EpgProgram(now - 10 * hour, now + hour, "Live: Bowls Premier League: BPL 24 day 1"),
            EpgProgram(now - hour, now + hour, "NBA Replay: Lakers vs Celtics"),
            EpgProgram(now - hour, now + hour, "Premier League Highlights"),
            EpgProgram(now - hour, now + hour, "Formula 1: Singapore Grand Prix"),
            EpgProgram(now - hour, now + hour, "MasterChef"))
        val keys = listOf("506", "nba", "football", "f1", "cooking")
        val channels = keys.associateWith { channel.copy(key = it) }
        every { db.visitCurrentPrograms(now, any(), any()) } answers {
            programmes.forEachIndexed { i, programme -> thirdArg<(String, EpgProgram) -> Unit>().invoke(keys[i], programme) }
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
        every { db.visitPrograms(any(), any(), any()) } just Runs
        val matcher = SportsMatcher(db)
        assertTrue(matcher.guideEvents(now - 12 * hour, now + 12 * hour, SportsCatalog.all,
            emptyList(), mapOf("506" to channel), hour, now).isEmpty())
        var title = "Live: Bowls Premier League: BPL 24 day 1"
        var start = now - 11 * hour
        every { db.visitCurrentPrograms(now - hour, any(), any()) } answers {
            thirdArg<(String, EpgProgram) -> Unit>().invoke("506", EpgProgram(start, now + hour, title))
        }
        fun read() = matcher.tvNowEvents(SportsCatalog.all, emptyList(), mapOf("506" to channel), hour, now).single()
        val bowls = read()
        assertEquals(now - 10 * hour, bowls.startMs)
        assertEquals(now + 2 * hour, bowls.endMs)
        title = "NBA Replay: Lakers vs Celtics"
        start = now - 2 * hour
        assertTrue(read().title.contains("NBA"))
        verify(exactly = 2) { db.visitCurrentPrograms(now - hour, any(), any()) }
    }
}
