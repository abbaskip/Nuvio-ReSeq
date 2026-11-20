package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.core.storage.DatabaseVersions
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveTvSettings
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalendarGuideTest {
    @Test fun onlyCommittedMatchingTitlesAreReadAndGuideOffsetsArePreserved() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val zone = ZoneId.of("Australia/Sydney")
        val now = LocalDate.of(2026, 12, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val db = EpgDatabase(context)
        val prefs = mockk<LiveTvPreferences> { every { settings } returns flowOf(LiveTvSettings(epgOffsetMinutes = 60)) }
        val listings = CalendarLocalListings(context, Lazy { prefs }, Lazy { error("No channel listing needed") }, Lazy { db },
            Lazy { error("No sports listing needed") })
        val movie = CalendarTitle("tt123", "The Crossing", true, "tt123", null, 0)
        val page = CalendarPage(region = "AU", zone = zone.id)
        val guideFile = context.getDatabasePath(DatabaseVersions.guide.fileName)
        try {
            assertEquals(page, listings.guide(page, listOf(movie), now, zone))
            assertFalse(guideFile.exists())
            db.replaceAll("first", mapOf("channel" to listOf(
                EpgProgram(now + 1000, now + 2000, "The Crossing"),
                EpgProgram(now + 3000, now + 4000, "After The Crossing"),
                EpgProgram(now + 5000, now + 6000, "Unrelated", "The Crossing"))), emptyMap())
            val first = listings.guide(page, listOf(movie), now, zone)
            assertEquals(listOf(movie.key), first.guideTitles)
            assertEquals(now + 3_601_000L, first.items.single().time)
            assertEquals("channel", first.items.single().channelKey)
            assertEquals(first, listings.guide(first, listOf(movie), now, zone))
            db.replaceAll("second", mapOf("channel" to listOf(EpgProgram(now, now + 2000, "New schedule"))), emptyMap())
            assertTrue(listings.guide(first, listOf(movie), now, zone).items.isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(DatabaseVersions.guide.fileName)
        }
    }
}
