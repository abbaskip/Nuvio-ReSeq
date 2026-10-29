package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingCacheFormatTest {
    private val gson = Gson()
    private val legacy = """[{"contentId":"tt0386676","contentType":"series","name":"Show","videoId":"tt0386676:5:16","season":5,"episode":16,"lastWatched":1000,"position":10000,"duration":60000,"sortTimestamp":1000,"tvdbEpisodeId":12345}]"""

    @Test
    fun `legacy next up cache preserves the episode identity and JSON names`() {
        val items: List<CachedNextUpItem> = gson.fromJson(legacy, object : TypeToken<List<CachedNextUpItem>>() {}.type)
        val entry = items.single()
        assertEquals("tt0386676", entry.contentId)
        assertEquals("tt0386676:5:16", entry.videoId)
        assertEquals(12345L, entry.tvdbEpisodeId)
        val saved = JsonParser.parseString(gson.toJson(items)).asJsonArray[0].asJsonObject
        assertEquals("tt0386676", saved["contentId"].asString)
        assertTrue(saved.has("videoId"))
    }

    @Test
    fun `legacy in progress cache preserves exact video progress`() {
        val items: List<CachedInProgressItem> = gson.fromJson(legacy, object : TypeToken<List<CachedInProgressItem>>() {}.type)
        val entry = items.single()
        assertEquals("tt0386676", entry.contentId)
        assertEquals(10000L, entry.position)
        assertEquals(60000L, entry.duration)
        val saved = JsonParser.parseString(gson.toJson(items)).asJsonArray[0].asJsonObject
        assertEquals("tt0386676:5:16", saved["videoId"].asString)
        assertEquals(10000L, saved["position"].asLong)
    }
}
