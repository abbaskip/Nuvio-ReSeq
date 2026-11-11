package com.nuvio.tv.livetv.sports

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SportsDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `upgrade moves downloadable sports files to cache and preserves persistent state`() {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val legacy = File(files, "sports").apply { mkdirs() }
        val names = listOf("base-feed.json", "base-feed-state.json", "matches.json", "free_events_1.json",
            "cricket_au_1.json", "teams_cricket.json", "table_moves_nfl.json", "gemini-guide-1.json")
        names.forEach { File(legacy, it).writeText(it) }
        File(legacy, "gemini-guide-usage.json").writeText("budget")
        File(files, "preferences").writeText("followed teams and settings")
        val target = SportsDiskCache.directory(files, cache)
        names.forEach {
            assertEquals(it, File(target, it).readText())
            assertFalse(File(legacy, it).exists())
        }
        assertEquals("budget", File(legacy, "gemini-guide-usage.json").readText())
        assertEquals("followed teams and settings", File(files, "preferences").readText())
        // A second initialization must not recreate deleted cache from old app-data copies.
        target.listFiles()!!.forEach { assertTrue(it.delete()) }
        assertTrue(target.delete())
        assertTrue(SportsDiskCache.directory(files, cache).listFiles()!!.isEmpty())
    }

    @Test fun `clearing matching cache preserves the persistent request budget`() {
        val cache = temporary.newFolder("decisions")
        val usage = temporary.newFolder("budget")
        val clock = 1_793_318_400_000L
        val first = GeminiGuideCache(cache, usage) { clock }
        first.put(1, "event", GuideMatchDecision.MATCH)
        assertTrue(first.reserve())
        cache.listFiles()!!.forEach { assertTrue(it.delete()) }
        assertTrue(cache.delete())
        val reopened = GeminiGuideCache(cache, usage) { clock }
        assertNull(reopened.get(1, "event"))
        assertFalse(reopened.reserve())
    }
}
