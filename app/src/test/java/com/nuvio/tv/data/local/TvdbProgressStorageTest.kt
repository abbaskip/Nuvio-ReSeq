package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TvdbProgressStorageTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    @Test fun `same numeric slot preserves native and different TVDB progress through store recreation`() = runTest {
        val stores = mutableMapOf<Pair<Int, String>, MemoryStore>()
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>(), ::MemoryStore) }
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val preferences = WatchProgressPreferences(factory, profiles)
        val native = WatchProgress("tt0386676", "series", "Office", null, null, null, "tt0386676:5:3",
            5, 3, "Native episode", 10, 100, 1, providerDefaultPlayback = true)
        val context = TvdbPlaybackContext(73244, "alternate", 390588, 5, 3, listOf(390588), native.videoId)
        val alternative = native.copy(position = 25, lastWatched = 2, tvdbContext = context,
            providerDefaultPlayback = false, tvdbEpisodeIds = context.episodeIds)
        val anotherEpisode = alternative.copy(position = 40, lastWatched = 3,
            tvdbContext = context.copy(episodeId = 99, episodeIds = listOf(99)), tvdbEpisodeIds = listOf(99))
        for (entry in listOf(native, alternative, anotherEpisode)) preferences.saveProgress(entry, 1)
        val restored = WatchProgressPreferences(factory, profiles)
        assertEquals(native, restored.getEpisodeProgress(native.contentId, 5, 3, 1).first())
        assertEquals(setOf(10L, 25L, 40L), restored.observeEpisodeRecords(native.contentId, 1).first().map { it.position }.toSet())
        assertEquals(context, restored.getAllRawEntries(1).getValue(alternative.storageKey).tvdbContext)
        assertEquals(mapOf((5 to 3) to native), restored.getAllEpisodeProgress(native.contentId, 1).first())
    }
    @Test fun `cloud snapshots and tracking-source changes preserve local TVDB resume without retaining native progress`() = runTest {
        val stores = mutableMapOf<Pair<Int, String>, MemoryStore>()
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>(), ::MemoryStore) }
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val preferences = WatchProgressPreferences(factory, profiles)
        val context = TvdbPlaybackContext(1, "alternate", 101, 1, 3, listOf(101, 102), "tt123:1:3", 44)
        val local = WatchProgress("tt123", "series", "Example", null, null, null, context.requestVideoId,
            1, 3, null, 500_000, 2_800_000, 1, tvdbContext = context)
        val native = local.copy(tvdbContext = null, episode = 2, videoId = "tt123:1:2")
        preferences.saveProgress(local)
        preferences.saveProgress(native)
        preferences.replaceWithRemoteEntries(emptyMap())
        assertEquals(listOf(local), preferences.observeEpisodeRecords("tt123", 1).first())
        preferences.saveProgress(native)
        preferences.clearAllPreservingNonTraktIds(preserveTvdbProgress = true) { false }
        assertEquals(local, preferences.getAllRawEntries().getValue(local.storageKey))
        assertEquals(1, preferences.getAllRawEntries().size)
        preferences.clearAllPreservingNonTraktIds { false }
        assertTrue(preferences.getAllRawEntries().isEmpty())
    }

}
