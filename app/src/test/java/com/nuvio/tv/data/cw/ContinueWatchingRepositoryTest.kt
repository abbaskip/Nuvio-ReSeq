package com.nuvio.tv.data.cw

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.homeCard
import com.nuvio.tv.ui.screens.home.sortContinueWatchingItems
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.inject.Provider

class ContinueWatchingRepositoryTest {
    @get:org.junit.Rule val androidRename = com.nuvio.tv.data.cw.AndroidAtomicRenameRule()
    @get:Rule val folder = TemporaryFolder()
    private val profile = MutableStateFlow(1)
    private val source = MutableStateFlow(WatchProgressSource.TRAKT)
    private val remote = MutableStateFlow(emptyList<WatchProgress>())
    private val seeds = MutableStateFlow(emptyList<WatchProgress>())
    private val loaded = MutableStateFlow(false)
    private val profiles = mockk<ProfileManager> { every { resolvedActiveProfileId } returns profile; every { activeProfileId } returns profile }
    private val settings = mockk<TraktSettingsDataStore> {
        coEvery { getWatchProgressSource(any()) } answers { source.value }
        every { watchProgressSource } returns source
    }
    private val progress = mockk<WatchProgressRepository> {
        coEvery { confirmSelectedDeviceProgress() } returns Unit
        every { watchedItems } returns kotlinx.coroutines.flow.flowOf(emptyList())
        every { allProgress } returns remote
        every { observeNextUpSeeds() } returns seeds
        every { observeRemoteProgressLoaded() } returns loaded
        every { hasActiveTrackingProgressProvider() } returns true
        every { isDroppedShow(any()) } returns false
    }
    private fun repository() = ContinueWatchingRepository(
        mockk<Context> { every { filesDir } returns folder.root }, profiles, settings, Provider { progress })
    private fun p(episode: Int = 1, time: Long = 1_000) = WatchProgress("show", "series", "Show", null, null,
        null, "show:1:$episode", 1, episode, null, 20, 100, time)

    @Test fun `undo a normal or split rewatch returns a finished show to its prior absence without a placeholder or late Details flicker`() = runTest {
        for (split in listOf(false, true)) {
            val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(73244, "alttwo", 10719652, 6, 17, listOf(10719652), "show:6:17")
            val original = p(17, 1_000).copy(season = 6, videoId = context.requestVideoId, position = 100,
                tvdbContext = context, tvdbEpisodeIds = context.episodeIds)
            val played = original.copy(lastWatched = 9_000, deviceWatchEventId = "play-$split")
            val native = played.copy(deviceWatchEventId = null, tvdbContext = null, episode = if (split) 16 else 17,
                progressPercent = if (split) 50f else 100f, source = WatchProgress.SOURCE_TRAKT_PLAYBACK, traktPlaybackId = 77)
            val offer = played.seriesChoice().copy(videoId = "show:6:18", season = 6, episode = 18, chooseEpisode = false)
            val resolver = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver> {
                io.mockk.coEvery { isFullyWatched(1, "show", any()) } coAnswers { delay(517); true }
            }
            every { progress.getAllEpisodeProgress("show", 1) } returns flowOf(mapOf((6 to 17) to original))
            fun owner() = ContinueWatchingRepository(mockk { every { filesDir } returns folder.root }, profiles, settings,
                Provider { progress }, tvdbTracking = Provider { resolver })
            val cw = owner()
            cw.committedProgress(1, listOf(played))
            cw.enrich(cw.snapshot().lease, nextUp = listOf(offer))
            val staleLease = cw.snapshot().lease
            val frames = mutableListOf<Pair<Boolean, Boolean>>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                cw.projection.filterNotNull().map { state ->
                    (state.inProgress.any { it.chooseEpisode } || state.nextUp.any { it.chooseEpisode }) to
                        (state.inProgress.isNotEmpty() || state.nextUp.isNotEmpty())
                }.distinctUntilChanged().collect { frames += it }
            }
            loaded.value = true
            seeds.value = listOf(original.copy(source = WatchProgress.SOURCE_TRAKT_HISTORY))
            remote.value = emptyList()
            cw.deviceWatchRemoved(1, com.nuvio.tv.data.local.DeviceWatchEvent("play-$split", played, 8_000, 9_000,
                removed = true, nativeEvent = native))
            delay(60_000)
            cw.enrich(staleLease, nextUp = listOf(offer.copy(chooseEpisode = true)))
            cw.enrich(staleLease, nextUp = listOf(offer))
            assertEquals(listOf(false to true, false to false), frames)
            val restarted = owner().snapshot().forPresentation()
            assertTrue(restarted.inProgress.isEmpty())
            assertTrue(restarted.nextUp.isEmpty())
            assertTrue(cw.snapshot().completed["show"].orEmpty().values.none { it.at == 9_000L })
            collector.cancel()
        }
    }

    @Test fun `restored placeholder and stale completions yield to current tracker history after restart`() = runTest {
        val stale = p(5, 4_000).copy(position = 100, source = WatchProgress.SOURCE_TRAKT_HISTORY)
        repository().committedProgress(1, listOf(stale))
        val owner = repository()
        val initial = owner.snapshot()
        assertTrue(initial.nextUp.single().chooseEpisode)
        val current = p(4, 2_000).copy(position = 100, source = WatchProgress.SOURCE_TRAKT_HISTORY)
        seeds.value = listOf(current)
        owner.refreshProjection()
        assertEquals(stale, owner.snapshot().members.getValue("show").progress)

        loaded.value = true
        owner.refreshProjection()
        val rebuilt = owner.snapshot()
        assertEquals(current, rebuilt.members.getValue("show").progress)
        val next = current.seriesChoice().copy(videoId = stale.videoId, season = 1, episode = 5, chooseEpisode = false)
        owner.enrich(initial.lease, nextUp = listOf(next))
        assertTrue(owner.snapshot().nextUp.single().chooseEpisode)
        owner.enrich(rebuilt.lease, nextUp = listOf(next))
        owner.refreshProjection()
        assertEquals(next, repository().snapshot().nextUp.single())
    }

    @Test fun `completion without Home survives stale return refreshes and process restart`() = runTest {
        val owner = repository()
        owner.snapshot()
        owner.committedProgress(1, listOf(p()))
        val staleMetadataLease = owner.snapshot().lease
        owner.committedProgress(1, listOf(p(time = 2_000).copy(position = 100)))
        remote.value = listOf(p())
        repeat(3) { owner.refreshProjection() } // close, external result absent, foreground resume
        owner.enrich(staleMetadataLease, inProgress = listOf(p().cached()))
        assertTrue(owner.snapshot().inProgress.isEmpty())
        assertTrue(owner.snapshot().nextUp.single().chooseEpisode)
        val restored = repository().snapshot()
        assertTrue(restored.nextUp.single().chooseEpisode)
        assertTrue(restored.members.getValue("show").progress!!.isCompleted())
    }

    @Test fun `startup provider cache cannot erase a resolved offer for the same refreshed episode`() = runTest {
        val seed = p(4, 2_000).copy(position = 100, source = WatchProgress.SOURCE_TRAKT_HISTORY)
        val offer = seed.seriesChoice().copy(videoId = "show:1:5", season = 1, episode = 5, chooseEpisode = false)
        val first = repository()
        first.committedProgress(1, listOf(seed))
        first.enrich(first.snapshot().lease, nextUp = listOf(offer))
        val restarted = repository()
        seeds.value = listOf(p(5, 3_000).copy(position = 100, source = WatchProgress.SOURCE_TRAKT_HISTORY))
        restarted.refreshProjection()
        assertEquals(offer, restarted.snapshot().nextUp.single())
        seeds.value = listOf(seed.copy(name = "Updated tracker title", poster = "updated-artwork"))
        loaded.value = true
        restarted.refreshProjection()
        assertEquals(offer, restarted.snapshot().nextUp.single())
        assertEquals(seeds.value.single(), restarted.snapshot().members.getValue("show").progress)
    }
    @Test fun `a verified split episode remains visible during a tracker rebuild and a changed pause replaces it`() = runTest {
        val raw = p().copy(source = WatchProgress.SOURCE_TRAKT_PLAYBACK, traktEpisodeId = 12, tvdbEpisodeId = 45L)
        val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(32, "alttwo", 46, 1, 2, listOf(46), "show:1:2")
        val offer = raw.seriesChoice().copy(videoId = context.requestVideoId, season = 1, episode = 2,
            tvdbEpisodeId = 46, tvdbContext = context, chooseEpisode = false)
        CwProjectionStore(java.io.File(folder.root, "cw_enrichment")).write(CwProjection(
            profileId = 1, source = source.value.name, members = mapOf("show" to CwMember(progress = raw, nextUp = offer))))
        remote.value = listOf(raw.copy(poster = "new"))
        loaded.value = true
        val restarted = repository()
        restarted.refreshProjection()
        assertEquals(offer, restarted.snapshot().nextUp.single())
        assertTrue(restarted.snapshot().inProgress.isEmpty())
        // A subsequent feed emission adds older history while the tracker replays the
        // same pause, this time outside the initial restore pass.
        val otherHistory = p(3, 500).copy(position = 100, source = WatchProgress.SOURCE_TRAKT_HISTORY)
        seeds.value = listOf(p(3, raw.lastWatched).copy(position = 0, source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS))
        remote.value = listOf(otherHistory, raw)
        restarted.refreshProjection()
        assertEquals(offer, restarted.snapshot().nextUp.single())
        seeds.value = emptyList()
        remote.value = listOf(raw.copy(position = 72, lastWatched = 3_000))
        repository().also { next ->
            next.refreshProjection()
            assertTrue(next.snapshot().nextUp.isEmpty())
            assertEquals(72L, next.snapshot().inProgress.single().position)
        }
    }

    @Test fun `a second box reconciles a deleted rewatch to its surviving older watch during a warm refresh`() = runTest {
        val old = p(16, 1_000).copy(position = 100, source = WatchProgress.SOURCE_TRAKT_HISTORY, traktEpisodeId = 182129)
        val pause = old.copy(position = 0, progressPercent = 49.75f, lastWatched = 9_000,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK, traktPlaybackId = 77)
        var deleted = false
        val tracker = mockk<com.nuvio.tv.core.tracking.TrackingProgressProvider> {
            every { providerId } returns com.nuvio.tv.core.tracking.TrackingProviderId.TRAKT
            every { isAuthenticated } returns kotlinx.coroutines.flow.flowOf(true)
            every { movieCompletionTimes(1) } returns kotlinx.coroutines.flow.flowOf(emptyMap())
            coEvery { refreshForContinueWatching(any()) } returns Unit
            every { confirmsRemovedWatch(1, any()) } answers { deleted && secondArg<WatchProgress>().traktPlaybackId == 77L }
        }
        val registry = com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry(setOf(tracker))
        val secondBox = ContinueWatchingRepository(mockk { every { filesDir } returns folder.root }, profiles, settings,
            Provider { progress }, trackingProviders = Provider { registry })
        remote.value = listOf(pause)
        seeds.value = listOf(old)
        loaded.value = true
        secondBox.refreshProjection()
        assertEquals(pause, secondBox.snapshot().members.getValue("show").progress)
        deleted = true
        secondBox.refreshProjection() // an older in-flight playback emission can still contain the removed session
        assertEquals(old, secondBox.snapshot().members.getValue("show").progress)
        assertEquals(old, repository().snapshot().members.getValue("show").progress)
        remote.value = listOf(pause.copy(lastWatched = 12_000, traktPlaybackId = 88))
        secondBox.refreshProjection()
        assertEquals(88L, secondBox.snapshot().members.getValue("show").progress?.traktPlaybackId)
    }

    @Test fun `late tracker response cannot undo playback committed after restoring the cache`() = runTest {
        val prior = p(4, 2_000).copy(source = WatchProgress.SOURCE_TRAKT_PLAYBACK)
        repository().committedProgress(1, listOf(prior))
        val restarted = repository()
        restarted.snapshot()
        val completed = prior.copy(source = WatchProgress.SOURCE_LOCAL, position = 100, lastWatched = 3_000)
        // Playback commits while an older tracker snapshot is already in flight.
        every { progress.allProgress } returns kotlinx.coroutines.flow.flow {
            restarted.committedProgress(1, listOf(completed))
            emit(listOf(prior))
        }
        loaded.value = true
        restarted.refreshProjection()
        assertEquals(completed, restarted.snapshot().members.getValue("show").progress)
    }

    @Test fun `profile source and order changes reject in flight enrichment without clearing old namespace`() = runTest {
        val owner = repository()
        owner.snapshot()
        owner.committedProgress(1, listOf(p()))
        val original = owner.snapshot()
        profile.value = 2
        assertTrue(owner.snapshot().members.isEmpty())
        owner.enrich(original.lease, inProgress = listOf(p().cached()))
        assertTrue(owner.snapshot().members.isEmpty())
        profile.value = 1
        assertEquals(listOf(p().cached()), owner.snapshot().inProgress)
        source.value = WatchProgressSource.SIMKL
        assertTrue(owner.snapshot().members.isEmpty())
        source.value = WatchProgressSource.TRAKT
        val beforeOrder = owner.snapshot()
        owner.invalidate(1, setOf("show"))
        owner.enrich(beforeOrder.lease, inProgress = listOf(p().cached().copy(name = "Stale order")))
        assertEquals("Show", owner.snapshot().inProgress.single().name)
    }

    @Test fun `inactive profile completion writes only that namespace`() = runTest {
        val owner = repository()
        owner.snapshot()
        profile.value = 2
        owner.snapshot()
        owner.committedProgress(1, listOf(p().copy(position = 100)))
        assertTrue(owner.snapshot().members.isEmpty())
        profile.value = 1
        assertTrue(owner.snapshot().nextUp.single().chooseEpisode)
    }

    @Test fun `restart replays committed progress after crash before projection write`() = runTest {
        val owner = repository()
        owner.snapshot()
        owner.committedProgress(1, listOf(p()))
        // Represents the existing progress store's commit; no CW callback ran before death.
        remote.value = listOf(p(time = 2_000).copy(position = 100))
        val restarted = repository()
        restarted.refreshProjection()
        assertTrue(restarted.snapshot().inProgress.isEmpty())
        assertTrue(restarted.snapshot().nextUp.single().chooseEpisode)
    }

    @Test fun `new support wins over a delayed successful sync deletion proof`() = runTest {
        source.value = WatchProgressSource.NUVIO_SYNC
        val owner = repository()
        val refreshStarted = owner.snapshot().lease
        owner.committedProgress(1, listOf(p()))
        owner.pruneAfterSync(refreshStarted, emptySet())
        assertEquals(setOf("show"), owner.snapshot().members.keys)
        owner.pruneAfterSync(owner.snapshot().lease, emptySet())
        assertTrue(owner.snapshot().members.isEmpty())
    }

    @Test fun `removing the only watched episode drops the card and rejects its stale replay`() = runTest {
        val owner = repository()
        owner.snapshot()
        owner.committedProgress(1, listOf(p().copy(position = 100)))
        owner.historyRemoved(1, "show", setOf(1 to 1))
        remote.value = listOf(p().copy(position = 100))
        owner.refreshProjection()
        assertTrue(owner.snapshot().members.isEmpty())
        assertTrue(repository().snapshot().members.isEmpty())
    }

    @Test fun `latest unwatch rehydrates older committed history and persists its recomputed offer`() = runTest {
        val owner = repository()
        owner.snapshot()
        val earlier = p(4, 1000).copy(position = 100, videoId = "show")
        val latest = p(5, 2000).copy(position = 100, videoId = "show")
        owner.committedProgress(1, listOf(earlier, latest))
        val oldLease = owner.snapshot().lease
        owner.historyRemoved(1, "show", setOf(1 to 5))
        seeds.value = listOf(earlier)
        remote.value = listOf(latest) // delayed provider replay of the revoked episode
        loaded.value = true
        owner.refreshProjection()
        assertEquals(earlier, owner.snapshot().members.getValue("show").progress)
        val offer = earlier.seriesChoice().copy(videoId = "show:1:5", season = 1, episode = 5, chooseEpisode = false)
        owner.enrich(oldLease, nextUp = listOf(offer.copy(episode = 6)))
        assertTrue(owner.snapshot().nextUp.single().chooseEpisode)
        owner.enrich(owner.snapshot().lease, nextUp = listOf(offer))
        assertEquals(offer, repository().snapshot().nextUp.single())
    }

    @Test fun `unwatch uses already emitted remaining history and puts the show back in activity order`() = runTest {
        val owner = repository()
        owner.snapshot()
        // In DVD order the removed E5 is tracker E3. E4 was watched before the other show.
        val remaining = p(4, 1_000).copy(position = 100)
        val other = p(1, 2_000).copy(contentId = "other", position = 100)
        val revoked = p(3, 3_000).copy(position = 100)
        owner.committedProgress(1, listOf(remaining, other, revoked))
        seeds.value = listOf(remaining, other)
        remote.value = listOf(p(10, 500).copy(progressPercent = 47f))
        owner.refreshProjection() // Tracker already published before its unwatch callback.
        val before = owner.snapshot()
        assertEquals(revoked, before.members.getValue("show").progress)

        owner.historyRemoved(1, "show", setOf(1 to 3), setOf(990103L))

        // No additional remote emission or foreground refresh.
        val after = repository().snapshot()
        assertEquals(remaining, after.members.getValue("show").progress)
        val sorted = sortContinueWatchingItems(after.nextUp.map { it.homeCard() }, ContinueWatchingSortMode.DEFAULT)
        assertEquals(listOf("other", "show"), sorted.map { (it as ContinueWatchingItem.NextUp).info.contentId })
    }

    @Test fun `removing a restored pause cannot rebuild away older completed history`() = runTest {
        val first = p(1, 1_000).copy(position = 100, source = WatchProgress.SOURCE_TRAKT_HISTORY)
        val last = p(3, 2_000).copy(position = 100, source = WatchProgress.SOURCE_TRAKT_HISTORY)
        val pause = p(1, 3_000).copy(position = 1, source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            traktPlaybackId = 77, traktEpisodeId = 12)
        val original = repository()
        original.committedProgress(1, listOf(first, last, pause))
        val history = original.snapshot().completed
        remote.value = listOf(pause) // Only recent playback is present in the startup feed.
        seeds.value = listOf(last)
        loaded.value = true
        val restarted = repository()
        restarted.partialWatchRemoved(1, pause)
        assertEquals(history, restarted.snapshot().completed)
        assertTrue(restarted.snapshot().inProgress.isEmpty())
        repeat(3) { restarted.refreshProjection() }
        assertEquals(history, repository().snapshot().completed)
    }

    @Test fun `selected TVDB order without verified identity retains series choice until compatible progress arrives`() = runTest {
        val orders = mockk<com.nuvio.tv.data.tvdb.TvdbOrderStore> {
            coEvery { exportSelections(1) } returns mapOf("alias:show" to "123", "order:123" to "dvd")
        }
        val owner = ContinueWatchingRepository(mockk<Context> { every { filesDir } returns folder.root },
            profiles, settings, Provider { progress }, tvdbOrders = orders)
        owner.snapshot()
        owner.committedProgress(1, listOf(p()))
        assertTrue(owner.snapshot().inProgress.single().chooseEpisode)
        val identified = p(time = 2_000).copy(tvdbContext = com.nuvio.tv.domain.model.TvdbPlaybackContext(
            123, "dvd", 44, 2, 5, listOf(44), "show:1:1"))
        owner.committedProgress(1, listOf(identified))
        assertFalse(owner.snapshot().inProgress.single().chooseEpisode)
        assertEquals(identified.tvdbContext, owner.snapshot().inProgress.single().tvdbContext)
    }
}
