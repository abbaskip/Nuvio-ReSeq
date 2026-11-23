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

    @Test fun `extended cut pause survives metadata enrichment and tracker refresh until matched completion`() = runTest {
        val preferences = WatchProgressPreferences(factory, profiles)
        val now = System.currentTimeMillis()
        val context = TvdbPlaybackContext(1, "extended", 99, 6, 19, listOf(51, 52), "office:6:19")
        val play = p.copy(episode = 19, videoId = context.requestVideoId, tvdbEpisodeIds = context.episodeIds,
            episodeTitle = "St. Patrick’s Day", position = 350_000, deviceWatchEventId = "device-play",
            lastWatched = now, tvdbContext = context)
        preferences.saveProgress(play.copy(poster = "art"), 1)
        preferences.saveProgress(play, 1)
        preferences.recordDeviceProgress(1, listOf(play), now - 2 * DEVICE_PROGRESS_GRACE_MS)
        val restarted = WatchProgressPreferences(factory, profiles)
        val stored = play.copy(poster = "art")
        assertEquals(listOf(stored), restarted.observeDeviceProgress(1, "TRAKT").first())
        val tracker = play.copy(source = WatchProgress.SOURCE_TRAKT_PLAYBACK, deviceWatchEventId = null)
        restarted.confirmDeviceProgress(1, "TRAKT", listOf(tracker))
        assertTrue(restarted.observeDeviceProgress(1, "TRAKT").first().isEmpty())
        restarted.confirmDeviceProgress(1, "TRAKT", emptyList())
        assertEquals(listOf(stored), restarted.observeDeviceProgress(1, "TRAKT").first())
        val complete = tracker.copy(position = play.duration)
        restarted.confirmDeviceProgress(1, "TRAKT", listOf(complete.copy(tvdbCompletedEpisodeIds = listOf(51))))
        assertEquals(listOf(stored), restarted.observeDeviceProgress(1, "TRAKT").first())
        restarted.confirmDeviceProgress(1, "TRAKT", listOf(complete))
        restarted.confirmDeviceProgress(1, "TRAKT", emptyList())
        assertTrue(restarted.observeDeviceProgress(1, "TRAKT").first().isEmpty())
    }

    @Test fun `pruned receipt requires a matching surviving device play and current raw position`() = runTest {
        val prefs = WatchProgressPreferences(factory, profiles)
        val play = p.copy(position = 450_000, deviceWatchEventId = "own")
        val event = DeviceWatchEvent("own", play, 8000, 9000)
        prefs.saveProgress(play, 1)
        prefs.confirmDeviceProgress(1, "TRAKT", emptyList(), listOf(event.copy(removed = true)))
        assertTrue(prefs.observeDeviceProgress(1, "TRAKT").first().isEmpty())
        prefs.confirmDeviceProgress(1, "TRAKT", emptyList(), listOf(event.copy(progress = play.copy(position = 12_000))))
        assertTrue(prefs.observeDeviceProgress(1, "TRAKT").first().isEmpty())
        prefs.confirmDeviceProgress(1, "TRAKT", emptyList(), listOf(event))
        assertEquals(listOf(play), prefs.observeDeviceProgress(1, "TRAKT").first())
        prefs.removeProgressKeys(listOf(play.storageKey), 1)
        prefs.revokeDeviceProgress(1, event.id, listOf(play))
        prefs.confirmDeviceProgress(1, "TRAKT", emptyList(), listOf(event))
        assertTrue(prefs.observeDeviceProgress(1, "TRAKT").first().isEmpty())
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
