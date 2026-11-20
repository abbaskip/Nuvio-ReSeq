package com.nuvio.tv.livetv.calendar

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

class CalendarPageCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val page = CalendarPage(region = "AU", zone = "Australia/Sydney", checkedAt = 10,
        items = listOf(CalendarItem("series/1", "series", "Example", 20_000, CalendarKind.EPISODE)))

    @Test fun independentProfilesAndRegionsWithAtomicLastGoodReplacement() {
        val dir = File(temp.root, "calendar")
        val cache = CalendarPageCache(dir)
        assertNull(cache.read(1, page.region, page.zone))
        assertFalse(dir.exists())
        cache.write(1, page)
        cache.write(2, page.copy(items = emptyList()))
        assertEquals(page, cache.read(1, page.region, page.zone))
        assertTrue(cache.read(2, page.region, page.zone)!!.items.isEmpty())
        assertNull(cache.read(1, "NZ", page.zone))
        assertNull(cache.read(1, page.region, "Pacific/Auckland"))
        val large = page.copy(items = listOf(page.items.first().copy(detail = "a".repeat(CalendarPageCache.MAX_BYTES.toInt()))))
        assertThrows(IOException::class.java) { cache.write(1, large) }
        assertEquals(page, cache.read(1, page.region, page.zone))
        assertFalse(File(dir, "profile-1-week-0.tmp").exists())
        File(dir, "profile-1-week-0.json").writeText("{")
        assertNull(cache.read(1, page.region, page.zone))
    }

    @Test fun legacyTitleBacklogIsIgnoredAndEachProfileKeepsOnlySixWeeklySnapshots() {
        val directory = File(temp.root, "calendar").apply { mkdirs() }
        File(directory, "profile-4.json").writeText("""{"version":1,"remaining":4000}""")
        val cache = CalendarPageCache(directory)
        assertNull(cache.read(4, page.region, page.zone, 100))
        cache.write(41, page.copy(weekStart = 100))
        repeat(8) { index -> cache.write(4, page.copy(weekStart = 100L + 7 * index)) }
        assertEquals(6, directory.listFiles()!!.count { it.name.startsWith("profile-4-week-") })
        assertEquals(149L, cache.read(4, page.region, page.zone, 149)!!.weekStart)
        CalendarPageCache.clear(temp.root, 4)
        assertNotNull(cache.read(41, page.region, page.zone, 100))
    }

    @Test fun scheduleIsBoundedAndWeekNavigationSurvivesAYearBoundary() {
        val today = LocalDate.of(2026, 12, 31)
        val window = CalendarWindow.around(today, Locale.UK)
        assertEquals(LocalDate.of(2026, 12, 21).toEpochDay(), window.first)
        val items = (0..1500).map { page.items.first().copy(key = "row/" + it, day = today.toEpochDay()) }
        val distant = items.first().copy(key = "distant", day = window.last + 1)
        val saved = CalendarPolicy.displayed(items + items + distant, window, today.toEpochDay())
        assertEquals(CalendarPolicy.MAX_ITEMS, saved.size)
        assertEquals(saved.size, saved.distinctBy { it.key }.size)
        assertFalse(distant in saved)
    }

    @Test fun deletingAProfileInvalidatesARefreshThatFinishesAfterTheSlotIsReused() {
        val id = 41
        val cache = CalendarPageCache(File(temp.root, "calendar"))
        val old = page.copy(generation = CalendarPageCache.generation(id))
        cache.write(id, old)
        CalendarPageCache.clear(temp.root, id)
        assertNull(cache.read(id, old.region, old.zone))
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { cache.write(id, old) }
        val replacement = old.copy(items = emptyList(), generation = CalendarPageCache.generation(id))
        cache.write(id, replacement)
        assertEquals(replacement, cache.read(id, old.region, old.zone))
    }
}
