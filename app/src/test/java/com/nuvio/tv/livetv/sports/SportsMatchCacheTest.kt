package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SportsMatchCacheTest {
    private val channel = LiveChannel("key", "pl", "Test", "Channel", null, null, null,
        "sports", "Sports", 1, "https://example.invalid")

    @Test fun streamedCachePreservesLegacyFormatChannelOrderAndEveryResult() {
        val directory = Files.createTempDirectory("sports-matches").toFile()
        try {
            val file = java.io.File(directory, "matches.json")
            val channels = (0..10000).associate { "$it" to channel.copy(key = "$it") }
            val matches = channels.values.mapIndexed { i, ch -> ChannelMatch(ch, i % 201,
                "Guide: A \"quoted\" title", null, if (i % 2 == 0) "HD" else null, i % 3 == 0) }
            SportsMatchCache.write(file, mapOf("fixture" to matches))
            assertEquals(mapOf("fixture" to matches), SportsMatchCache.read(file, channels))
            val legacy = org.json.JSONArray(file.readText()).getJSONObject(0)
            assertEquals("fixture", legacy.getString("id"))
            assertEquals(matches.size, legacy.getJSONArray("m").length())
            var calls = 0
            try {
                SportsMatchCache.write(file, mapOf("partial" to matches)) {
                    if (++calls == 20) throw kotlinx.coroutines.CancellationException("Playback")
                }
                fail("Expected cancellation")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(mapOf("fixture" to matches), SportsMatchCache.read(file, channels))
            assertFalse(java.io.File(file.path + ".tmp").exists())
            SportsMatchCache.write(file, mapOf("new" to matches.take(1)))
            assertEquals(mapOf("new" to matches.take(1)), SportsMatchCache.read(file, channels))
        } finally { directory.deleteRecursively() }
    }
}
