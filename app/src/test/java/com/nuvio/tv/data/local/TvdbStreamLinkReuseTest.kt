package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TvdbStreamLinkReuseTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private val memory = MemoryStore()
    private val factory = mockk<ProfileDataStoreFactory> { every { get(1, "stream_link_cache") } returns memory }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    private val cache = StreamLinkCacheDataStore(factory, profiles)
    private val selected = TvdbPlaybackContext(73244, "alternate", 390588, 5, 3, listOf(390588), "tt0386676:5:3")
    private fun keys(context: TvdbPlaybackContext? = selected, videoId: String = context?.requestVideoId ?: "tt0386676:5:2") =
        streamLinkCacheKeys("series", videoId, context)

    @Test fun `valid last link follows verified content across order and number changes`() = runTest {
        cache.save(keys().single(), "https://fixture.invalid/ethics", "chosen stream", null)
        val anotherOrder = selected.copy(orderType = "official", season = 5, episode = 2, requestVideoId = "tt0386676:5:2")
        assertEquals("chosen stream", cache.getValid(keys(anotherOrder), 60_000, true)?.streamName)
    }

    @Test fun `same search number with another identity cannot reuse link or resume position`() = runTest {
        cache.save(keys().single(), "https://fixture.invalid/ethics", "chosen stream", null)
        val other = selected.copy(episodeId = 390589, episodeIds = listOf(390589))
        assertNull(cache.getValid(keys(other), 60_000, true))
        assertNotEquals(selected.storageKey("tt0386676"), other.storageKey("tt0386676"))
        assertEquals("tt0386676:5:3", other.requestVideoId)
    }

    @Test fun `expiry disabled reuse and missing identity all require a fresh selected-number search`() = runTest {
        cache.save(keys().single(), "https://fixture.invalid/ethics", "chosen stream", null)
        assertNull(cache.getValid(keys(), 60_000, false))
        val expired = memory.data.value.toMutablePreferences()
        val entry = expired.asMap().entries.single()
        @Suppress("UNCHECKED_CAST")
        expired[entry.key as Preferences.Key<String>] = JSONObject(entry.value as String).put("cachedAtMs", 1L).toString()
        memory.data.value = expired.toPreferences()
        assertNull(cache.getValid(keys(), 60_000, true))
        assertNull(cache.getValid(keys(selected.copy(episodeIds = emptyList())), 60_000, true))
        assertEquals("tt0386676:5:3", selected.requestVideoId)
    }

    @Test fun `native cache bridge requires equal coverage and Provider Default keeps its normal key`() = runTest {
        val source = EpisodeSourceIdentity("tt0386676:5:2", EpisodeCoordinates(5, 2), listOf(390588), "ignored title")
        val identity = EpisodeIdentity(390588, 73244, "alternate", EpisodeCoordinates(5, 3), EpisodeCoordinates(5, 2),
            canonicalEpisodeIds = listOf(390588), providerSources = listOf(source))
        assertEquals(listOf("series|tt0386676:5:2"), keys(null))
        cache.save(keys(null).single(), "https://fixture.invalid/native", "native choice", null)
        val bridged = streamLinkCacheKeys("series", selected.requestVideoId, selected, identity)
        assertEquals("native choice", cache.getValid(bridged, 60_000, true)?.streamName)
        val combined = identity.copy(providerSources = listOf(source.copy(episodeIds = listOf(390588, 390589))))
        assertEquals(keys(), streamLinkCacheKeys("series", selected.requestVideoId, selected, combined))
    }
}
