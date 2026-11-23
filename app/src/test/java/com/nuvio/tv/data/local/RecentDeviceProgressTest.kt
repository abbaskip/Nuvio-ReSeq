package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecentDeviceProgressTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }
    private val stores = mutableMapOf<Pair<Int, String>, MemoryStore>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>(), ::MemoryStore) }
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    private val p = WatchProgress("office", "series", "Office", null, null, null, "office:6:17", 6, 17,
        "The Delivery Part 1", 1_000, 1_000_000, 9000, tvdbEpisodeIds = listOf(46))

    @Test fun `unconfirmed interrupted episode survives another device play and app restart`() = runTest {
        val preferences = WatchProgressPreferences(factory, profiles)
        val episode = p.copy(position = 350_000)
        val now = System.currentTimeMillis()
        preferences.saveProgress(episode, 1)
        preferences.recordDeviceProgress(1, listOf(episode), now - 2 * DEVICE_PROGRESS_GRACE_MS)
        preferences.recordDeviceProgress(1, listOf(p.copy(contentId = "other")), now)
        preferences.confirmDeviceProgress(1, "TRAKT", emptyList())
        val restarted = WatchProgressPreferences(factory, profiles)
        assertTrue(episode in restarted.observeDeviceProgress(1, "TRAKT").first())
        preferences.confirmDeviceProgress(1, "TRAKT", listOf(episode.copy(position = episode.duration)))
        assertTrue(restarted.observeDeviceProgress(1, "TRAKT").first().isEmpty())
    }

    @Test fun `cloud imports and mirrored tracker rows never acquire a device receipt`() = runTest {
        val preferences = WatchProgressPreferences(factory, profiles)
        preferences.saveProgress(p, 1) // Same storage API used by imports: no repository device receipt.
        assertTrue(preferences.observeDeviceProgress(1, "TRAKT").first().isEmpty())
        preferences.recordDeviceProgress(1, listOf(p.copy(source = WatchProgress.SOURCE_SIMKL_PLAYBACK, trackingProviderId = "simkl")))
        assertTrue(preferences.observeDeviceProgress(1, "TRAKT").first().isEmpty())
        preferences.recordDeviceProgress(1, listOf(p))
        val restarted = WatchProgressPreferences(factory, profiles)
        assertEquals(listOf(p), restarted.observeDeviceProgress(1, "TRAKT").first { it.isNotEmpty() })
        preferences.removeProgressKeys(listOf(p.storageKey), 1)
        // The existing progress bucket publishes through an IO stateIn cache.
        assertTrue(restarted.observeDeviceProgress(1, "TRAKT").first { it.isEmpty() }.isEmpty())
    }

    @Test fun `selected confirmation retires the device override only for that tracker`() = runTest {
        val preferences = WatchProgressPreferences(factory, profiles)
        preferences.saveProgress(p, 1)
        preferences.recordDeviceProgress(1, listOf(p))
        val native = p.copy(videoId = "office:6:16", episode = 16, tvdbEpisodeIds = listOf(45, 46),
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK, progressPercent = 1f, lastWatched = 10000)
        preferences.confirmDeviceProgress(1, "TRAKT", listOf(native.copy(lastWatched = 8000)))
        assertEquals(listOf(p), preferences.observeDeviceProgress(1, "TRAKT").first())
        preferences.confirmDeviceProgress(1, "TRAKT", listOf(native))
        assertTrue(preferences.observeDeviceProgress(1, "TRAKT").first().isEmpty())
        assertEquals(listOf(p), preferences.observeDeviceProgress(1, "SIMKL").first())
        assertEquals(listOf(p), preferences.observeDeviceProgress(1, "MDBLIST").first())
    }

    @Test fun `device grace is bounded and split confirmation requires coverage of the selected part`() {
        val receipt = RecentDeviceProgress(p, 100)
        assertTrue(receipt.pendingFor("TRAKT", 101))
        assertFalse(receipt.pendingFor("TRAKT", 99))
        assertFalse(receipt.pendingFor("TRAKT", 100 + DEVICE_PROGRESS_GRACE_MS))
        assertFalse(p.copy(tvdbEpisodeIds = listOf(45), lastWatched = 10000).confirmsDeviceProgress(p))
        assertTrue(p.copy(tvdbEpisodeIds = listOf(45, 46), lastWatched = 10000).confirmsDeviceProgress(p))
        assertFalse(p.copy(tvdbEpisodeIds = listOf(46), lastWatched = 10000).confirmsDeviceProgress(p.copy(tvdbEpisodeIds = listOf(45, 46))))
    }
}
