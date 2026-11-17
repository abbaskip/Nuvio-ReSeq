package com.nuvio.tv.data.cw

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.tvdb.TvdbTrackingResolver
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class CwSettledStartupTest(private val selected: TrackingProviderId, private val order: List<Int>, private val sameCard: Boolean) {
    companion object {
        private fun permutations(items: List<Int>): List<List<Int>> = if (items.isEmpty()) listOf(emptyList())
            else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }
        @JvmStatic @Parameterized.Parameters(name = "{0} replies={1} cachedSame={2}")
        fun cases() = TrackingProviderId.entries.flatMap { source -> permutations(listOf(0, 1, 2, 3)).flatMap { order ->
            listOf(false, true).map { same -> arrayOf<Any>(source, order, same) }
        } }
    }
    @get:Rule val androidRename = AndroidAtomicRenameRule()
    @get:Rule val folder = TemporaryFolder()
    private val fixtureMocks = mutableListOf<Any>()
    @After fun releaseFixtureAnswers() {
        fixtureMocks.forEach { clearMocks(it, answers = true, recordedCalls = true, childMocks = true) }
        fixtureMocks.clear()
    }

    @Test fun `cold start and Details return publish only a settled card despite every reply order`() = runTest {
        val profile = MutableStateFlow(1)
        val settings = mockk<TraktSettingsDataStore> {
            coEvery { getWatchProgressSource(1) } returns WatchProgressSource.valueOf(selected.name)
            every { resolvedWatchProgressSource(1) } returns flowOf(WatchProgressSource.valueOf(selected.name))
        }
        val profiles = mockk<ProfileManager> { every { resolvedActiveProfileId } returns profile; every { activeProfileId } returns profile }
        val raw = WatchProgress("office", "series", "The Office", "poster", "backdrop", null,
            "office:6:16", 6, 16, "The Delivery", 0, 0, 9_000, progressPercent = 1f,
            source = when (selected) {
                TrackingProviderId.TRAKT -> WatchProgress.SOURCE_TRAKT_PLAYBACK
                TrackingProviderId.SIMKL -> WatchProgress.SOURCE_SIMKL_PLAYBACK
                TrackingProviderId.MDBLIST -> WatchProgress.SOURCE_REMOTE_PLAYBACK
            }, trackingProviderId = selected.storageId, traktEpisodeId = 12, tvdbEpisodeId = 45)
        val context = TvdbPlaybackContext(73244, "alttwo", 46, 6, 17, listOf(46), "office:6:17")
        val resolved = raw.copy(videoId = context.requestVideoId, season = 6, episode = 17,
            tvdbEpisodeId = 46, tvdbContext = context)
        val cached = resolved.copy(episode = if (sameCard) 17 else 18,
            videoId = if (sameCard) resolved.videoId else "office:6:18").cached()
        val cachedMember = CwMember(progress = raw, inProgress = cached)
        CwProjectionStore(java.io.File(folder.root, "cw_enrichment")).write(CwProjection(
            profileId = 1, source = selected.name, members = mapOf("office" to cachedMember)))
        val remote = MutableStateFlow(emptyList<WatchProgress>())
        val seeds = MutableStateFlow(emptyList<WatchProgress>())
        val catalogue = CompletableDeferred<Unit>()
        val allFinished = mutableSetOf<Int>()
        val selectedProvider = mockk<TrackingProgressProvider> {
            every { providerId } returns selected
            every { isAuthenticated } returns flowOf(true)
            every { movieCompletionTimes(1) } returns flowOf(emptyMap())
            every { confirmsRemovedWatch(any(), any()) } returns false
            coEvery { refreshForContinueWatching(any()) } coAnswers {
                coroutineScope {
                    order.mapIndexed { index, task -> launch {
                        delay((index + 1) * if (sameCard) 137L else 7_001L)
                        when (task) {
                            0 -> remote.value = listOf(raw)
                            1 -> seeds.value = listOf(raw.copy(position = 100, duration = 100, progressPercent = 100f,
                                lastWatched = 1_000, source = WatchProgress.SOURCE_REMOTE_HISTORY))
                            3 -> catalogue.complete(Unit)
                        }
                        allFinished += task
                    } }.joinAll()
                }
            }
        }
        val others = TrackingProviderId.entries.filter { it != selected }.map { id ->
            mockk<TrackingProgressProvider> { every { providerId } returns id }
        }
        val progress = mockk<WatchProgressRepository> {
            every { allProgress } returns remote
            every { observeNextUpSeeds() } returns seeds
            every { observeRemoteProgressLoaded() } returns flowOf(true) // old cache already reports loaded
            every { watchedItems } returns flowOf(emptyList())
            every { hasActiveTrackingProgressProvider() } returns true
            every { isDroppedShow(any()) } returns false
            coEvery { confirmSelectedDeviceProgress() } returns Unit
        }
        val resolver = mockk<TvdbTrackingResolver> {
            coEvery { continueWatchingResume(1, any(), any(), any()) } coAnswers {
                catalogue.await()
                delay(113)
                resolved
            }
        }
        val contextMock = mockk<Context> { every { filesDir } returns folder.root }
        fixtureMocks.addAll(listOf(profiles, settings, progress, resolver, selectedProvider, contextMock) + others)
        val owner = ContinueWatchingRepository(contextMock, profiles, settings,
            Provider { progress }, Provider { TrackingProgressProviderRegistry((others + selectedProvider).toSet()) },
            tvdbTracking = Provider { resolver })
        val frames = mutableListOf<com.nuvio.tv.data.local.CachedInProgressItem?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.projection.filterNotNull().map { it.members["office"]?.inProgress }.distinctUntilChanged().collect { frames += it }
        }
        owner.snapshot()
        assertEquals(listOf(cached), frames)
        owner.refreshSelected(TrackingRefreshIntent.INVALIDATED)
        assertEquals(setOf(0, 1, 2, 3), allFinished)
        assertEquals(listOf(cached), frames) // even the resolved working revision stays off screen
        val ready = owner.settledProgress.first()
        owner.enrich(ready.lease, inProgress = listOf(raw.cached().copy(chooseEpisode = true)), settled = false)
        delay(300)
        owner.enrich(ready.lease, inProgress = listOf(resolved.cached()), settled = true)
        // Details -> Home and delayed lookup/artwork callbacks must not publish a second card.
        delay(60_000)
        owner.enrich(owner.snapshot().lease, inProgress = listOf(raw.cached().copy(chooseEpisode = true)))
        owner.enrich(owner.snapshot().lease, inProgress = listOf(resolved.cached().copy(poster = "late artwork")))
        assertTrue(frames.none { it == null || it.chooseEpisode })
        assertTrue("frames=$frames", frames.size <= if (sameCard) 1 else 2)
        assertEquals(resolved.videoId, frames.last()!!.videoId)
        // A process restart uses the last presented card, never an unfinished working result.
        val disk = CwProjectionStore(java.io.File(folder.root, "cw_enrichment")).read(1, selected.name)
        assertEquals(frames.last(), disk.forPresentation().members.getValue("office").inProgress)
        others.forEach { other ->
            verify(exactly = 0) { other.isAuthenticated }
            verify(exactly = 0) { other.allProgress }
            coVerify(exactly = 0) { other.refreshForContinueWatching(any()) }
        }
    }
}
