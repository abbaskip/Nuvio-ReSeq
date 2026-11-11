package com.nuvio.tv.ui.screens.home

import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.repository.traktRefreshEvents
import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.data.tvdb.TvdbOrderPreference
import com.nuvio.tv.data.tvdb.tvdbFixtureMeta
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShuffleHomeRefreshTest {
    private val vm = mockk<HomeViewModel>(relaxed = true)
    private val store = mockk<com.nuvio.tv.data.tvdb.TvdbOrderStore>()
    private val id = "tt0303461"
    private val seed = WatchProgress(id, "series", "Show", null, null, null, "$id:1:1", 1, 1,
        "Pilot", 100, 100, 1, source = WatchProgress.SOURCE_TRAKT_HISTORY)
    private val card = ContinueWatchingItem.NextUp(NextUpInfo(id, "series", "Show", null, null, null,
        "$id:1:2", 1, 2, "Two", thumbnail = null, lastWatched = 1, sortTimestamp = 1, tvdbEpisodeId = 22))
    private val snapshot = HomeUiState(continueWatchingItems = listOf(card))
    private val native = MutableStateFlow(snapshot)
    private val seeds = MutableStateFlow(listOf(seed))
    private val profile = MutableStateFlow(EpisodeShuffleProfile(1, true, mapOf(id to EpisodeShuffleSettings(true))))
    private val watched = MutableStateFlow(emptyList<WatchedItem>())
    private val progress = MutableStateFlow(emptyList<WatchProgress>())
    private val refresh = MutableStateFlow(HomeShuffleRefresh(1))
    private val coord = EpisodeCoordinates(1, 2)
    private val video = Video(card.info.videoId, "Two", "2020-01-01", null, season = 1, episode = 2, overview = null,
        episodeIdentity = EpisodeIdentity(22, 111, "dvd", coord, coord, card.info.videoId, coord))
    private val cache = mutableMapOf<String, CwMetaSummary?>()
    private val negative = mutableMapOf<String, Long>()
    private val requests = mutableListOf<String>()
    private val key = "series:$id"
    private fun summary(v: Video) = CwMetaSummary(id, "Show", null, null, null, null, emptyList(), null,
        null, null, null, listOf(CwVideoSummary(v.id, v.title, v.released, null, v.season, v.episode,
            null, v.available, episodeIdentity = v.episodeIdentity)), tvdbOrder = true)

    private fun TestScope.setup() {
        mockkStatic("androidx.lifecycle.ViewModelKt")
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { testScheduler.currentTime }
        every { vm.viewModelScope } returns backgroundScope
        every { vm._uiState } returns native
        every { vm.shuffleHomeRefresh } returns refresh
        every { vm.cwMetadataLoads } returns CwMetadataLoads()
        every { vm.cwMetaCache } returns cache
        every { vm.cwMetaNegativeCacheTimestamps } returns negative
        every { vm.episodeShuffle } returns EpisodeShuffle()
        every { vm.episodeShuffleStore.profiles } returns profile
        every { vm.traktSettingsDataStore.continueWatchingDaysCap } returns flowOf(-1)
        every { vm.traktSettingsDataStore.dismissedNextUpKeys } returns flowOf(emptySet())
        every { vm.continueWatchingSortMode } returns ContinueWatchingSortMode.SPLIT_UPCOMING
        every { vm.profileManager.activeProfileId } returns MutableStateFlow(1)
        every { vm.tvdbOrderStore } returns store
        every { store.hasSnapshot(1) } returns true
        every { store.cachedSelection(1, any()) } returns TvdbOrderPreference(111, "dvd")
        coEvery { store.selected(1, any()) } returns TvdbOrderPreference(111, "dvd")
        val repository = mockk<WatchProgressRepository>(relaxed = true)
        every { vm.watchProgressRepository } returns repository
        every { repository.allProgress } returns progress
        every { repository.watchedItems } returns watched
        every { repository.observeNextUpSeeds() } returns seeds
        every { repository.activeProviderContinueWatchingCutoffEpochMs(any(), any()) } returns null
        every { repository.shouldUseAsNextUpSeed(any(), any()) } returns true
        every { vm.externalMetaPrefetchEnabled } returns true
        every { vm.metaRepository.getMetaFromAllAddons(any(), any(), any()) } answers {
            val series = secondArg<String>()
            flow {
                requests += series
                delay(if (series == id) 10_000 else 1_000)
                emit(NetworkResult.Success(tvdbFixtureMeta(listOf(video)).copy(id = series,
                    episodeOrder = EpisodeOrderStatus(111, "dvd", "dvd"))))
            }
        }
        cache[key] = summary(video)
    }

    @After fun cleanup() {
        unmockkStatic("androidx.lifecycle.ViewModelKt")
        unmockkStatic(SystemClock::class)
        unmockkStatic(Toast::class)
    }

    private fun TestScope.home(): StateFlow<HomeUiState> = vm.createShuffleHomeState().also { runCurrent() }
    private fun StateFlow<HomeUiState>.onlyCard() = value.continueWatchingItems.single()
    private fun TestScope.minute() { advanceTimeBy(60_000); runCurrent() }

    @Test fun trackerRefreshAloneKeepsRestoredShow() = runTest {
        setup()
        val home = home()
        assertEquals(id, home.onlyCard().contentId())
        var rounds = 0
        backgroundScope.launch {
            traktRefreshEvents(emptyFlow()) { 60_000L }.drop(1).collect {
                rounds++
                watched.value = listOf(WatchedItem(id, "series", "Show", 1, 1, currentTime))
                progress.value = listOf(seed)
                seeds.value = listOf(seed.copy(lastWatched = currentTime))
            }
        }
        minute()
        assertEquals(1, rounds)
        assertEquals(id, home.onlyCard().contentId())
    }

    @Test fun revisitWithEligibleEpisodeKeepsRestoredShow() = runTest {
        setup()
        val home = home()
        assertEquals(id, home.onlyCard().contentId())
        minute()
        refresh.update { it.copy(visit = 2) }
        runCurrent()
        assertEquals(id, home.onlyCard().contentId())
    }

    @Test fun coldSnapshotIsVisibleWithoutCatalogue() = runTest {
        setup()
        cache.clear()
        assertEquals(id, home().onlyCard().contentId())
    }

    @Test fun metadataEvictionAndTrackerReplacementKeepTheSelectedShuffleEpisode() = runTest {
        setup()
        val home = home()
        val selected = home.onlyCard() as ContinueWatchingItem.NextUp
        assertTrue(selected.shufflePlayback)
        cache.clear()
        native.value = HomeUiState(continueWatchingItems = listOf(card.copy(info = card.info.copy(
            videoId = "$id:1:3", season = 1, episode = 3, episodeTitle = "Sequential next"
        ))))
        refresh.update { it.copy(metadata = it.metadata + 1) }
        runCurrent()
        val refreshed = home.onlyCard() as ContinueWatchingItem.NextUp
        assertTrue(refreshed.shufflePlayback)
        assertFalse(refreshed.chooseEpisode)
        assertEquals(selected.info.videoId, refreshed.info.videoId)
        assertEquals(selected.info.tvdbContext, refreshed.info.tvdbContext)
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(home.onlyCard().shufflePlayback)
    }

    @Test fun sequentialNextUpKeepsItsVerifiedEpisodeDuringMetadataEviction() = runTest {
        setup()
        profile.value = EpisodeShuffleProfile(1, true)
        native.value = snapshot.copy(continueWatchingItems = listOf(card.copy(info = card.info.copy(
            tvdbContext = video.tvdbPlaybackContext()
        ))))
        val home = home()
        val selected = home.onlyCard() as ContinueWatchingItem.NextUp
        assertFalse(selected.shufflePlayback)
        cache.clear()
        refresh.update { it.copy(metadata = it.metadata + 1) }
        runCurrent()
        val refreshing = home.onlyCard() as ContinueWatchingItem.NextUp
        assertFalse("a cache miss must not turn ordinary Next Up into series navigation", refreshing.chooseEpisode)
        assertFalse(refreshing.shufflePlayback)
        assertEquals(selected.info, refreshing.info)
        advanceTimeBy(10_001)
        runCurrent()
        assertFalse(home.onlyCard().chooseEpisode)
    }

    @Test fun sequentialResumeKeepsItsVerifiedEpisodeDuringMetadataEviction() = runTest {
        setup()
        profile.value = EpisodeShuffleProfile(1, true)
        val resume = ContinueWatchingItem.InProgress(seed.copy(videoId = video.id, season = 1, episode = 2,
            tvdbEpisodeId = 22, tvdbContext = video.tvdbPlaybackContext(), position = 30))
        native.value = HomeUiState(continueWatchingItems = listOf(resume))
        val home = home()
        assertFalse(home.onlyCard().chooseEpisode)
        cache.clear()
        refresh.update { it.copy(metadata = it.metadata + 1) }
        runCurrent()
        assertFalse("a cache miss does not invalidate the verified resume", home.onlyCard().chooseEpisode)
        assertFalse(home.onlyCard().shufflePlayback)
        assertEquals(resume.progress, (home.onlyCard() as ContinueWatchingItem.InProgress).progress)
    }

    @Test fun ordinaryPlayFromAStaleFallbackUsesTheCurrentSequentialEpisode() = runTest {
        setup()
        profile.value = EpisodeShuffleProfile(1, true)
        val home = home()
        every { vm.uiState } returns home
        var played: ContinueWatchingItem? = null
        vm.verifyTvdbPlayback(card.copy(chooseEpisode = true), { played = it },
            { _, _, _ -> fail("Ordinary Play redirected to Details despite a resolved Next Up") })
        advanceTimeBy(10_001)
        runCurrent()
        val target = played as ContinueWatchingItem.NextUp
        assertEquals(card.info.videoId, target.info.videoId)
        assertFalse(target.chooseEpisode)
        assertFalse(target.shufflePlayback)
    }

    @Test fun sequentialNextUpDoesNotRetainIdentityAfterTheSelectedOrderChanges() = runTest {
        setup()
        profile.value = EpisodeShuffleProfile(1, true)
        native.value = snapshot.copy(continueWatchingItems = listOf(card.copy(info = card.info.copy(
            tvdbContext = video.tvdbPlaybackContext()
        ))))
        val home = home()
        cache.clear()
        every { store.cachedSelection(1, any()) } returns TvdbOrderPreference(111, "alternate")
        refresh.update { it.copy(metadata = it.metadata + 1) }
        runCurrent()
        assertTrue(home.onlyCard().chooseEpisode)
        assertFalse(home.onlyCard().canMarkWatched())
    }

    @Test fun normalPlayBypassesAPendingHomeCacheButStillResolvesFreshTvdbIdentity() = runTest {
        setup()
        profile.value = EpisodeShuffleProfile(1, true)
        cache[key] = summary(video).copy(tvdbOrder = false, tvdbPending = true)
        negative[key] = currentTime
        val home = home()
        every { vm.uiState } returns home
        val fallback = home.onlyCard()
        assertTrue(fallback.chooseEpisode)
        var played: ContinueWatchingItem? = null
        vm.verifyTvdbPlayback(fallback, { played = it }, { _, _, _ -> fail("Known sequential episode opened Details") })
        advanceTimeBy(10_001)
        runCurrent()
        val target = played as ContinueWatchingItem.NextUp
        assertFalse(target.chooseEpisode)
        assertFalse(target.shufflePlayback)
        assertEquals(video.tvdbPlaybackContext(), target.info.tvdbContext)
        assertEquals(listOf(id), requests)
    }

    @Test fun verifiedSequentialCardSurvivesATemporaryTvdbFallbackWithoutBypassingPlaybackChecks() = runTest {
        setup()
        profile.value = EpisodeShuffleProfile(1, true)
        native.value = snapshot.copy(continueWatchingItems = listOf(card.copy(info = card.info.copy(
            tvdbContext = video.tvdbPlaybackContext()
        ))))
        cache[key] = summary(video).copy(tvdbOrder = false, tvdbPending = true)
        negative[key] = currentTime
        val home = home()
        every { vm.uiState } returns home
        assertFalse(home.onlyCard().chooseEpisode)
        every { vm.metaRepository.getMetaFromAllAddons(any(), any(), any()) } returns flowOf(NetworkResult.Error("unavailable"))
        mockkStatic(Toast::class)
        val toast = mockk<Toast>(relaxed = true)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns toast
        vm.verifyTvdbPlayback(home.onlyCard(), { fail("Played without fresh identity verification") },
            { _, _, _ -> fail("Playback failure redirected to Details") })
        runCurrent()
        verify(exactly = 1) { toast.show() }
    }

    @Test fun manualPlayFromStalePlaceholderUsesTheResolvedCardInsteadOfDetails() = runTest {
        setup()
        val home = home()
        every { vm.uiState } returns home
        var played: ContinueWatchingItem? = null
        vm.verifyTvdbPlayback(card.copy(chooseEpisode = true), { played = it },
            { _, _, _ -> fail("Manual play opened Details") }, manualSelection = true)
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(card.info.videoId, (played as ContinueWatchingItem.NextUp).info.videoId)
        assertTrue(played!!.shufflePlayback)
    }

    @Test fun manualPlayWaitsForColdShuffleMetadataAndThenOpensTheChosenEpisode() = runTest {
        setup()
        cache.clear()
        val home = home()
        every { vm.uiState } returns home
        val placeholder = home.onlyCard()
        assertTrue(placeholder.chooseEpisode)
        var played: ContinueWatchingItem? = null
        vm.verifyTvdbPlayback(placeholder, { played = it },
            { _, _, _ -> fail("Manual play opened Details") }, manualSelection = true)
        advanceTimeBy(20_002)
        runCurrent()
        assertEquals(card.info.videoId, (played as ContinueWatchingItem.NextUp).info.videoId)
        assertFalse(played!!.chooseEpisode)
        assertTrue(played!!.shufflePlayback)
    }

    @Test fun manualPlayWithUnresolvedIdentityNeverRedirectsToDetailsOrPlaysTheSeed() = runTest {
        setup()
        cache[key] = summary(video).copy(tvdbOrder = false, tvdbPending = true)
        negative[key] = currentTime
        val home = home()
        every { vm.uiState } returns home
        mockkStatic(Toast::class)
        val toast = mockk<Toast>(relaxed = true)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns toast
        vm.verifyTvdbPlayback(home.onlyCard(), { fail("Played an unresolved seed") },
            { _, _, _ -> fail("Manual play opened Details") }, manualSelection = true)
        advanceTimeBy(22_001)
        runCurrent()
        verify(exactly = 1) { toast.show() }
    }

    @Test fun changingOrderDuringACacheMissDoesNotRetainTheOldShuffleIdentity() = runTest {
        setup()
        val home = home()
        assertTrue(home.onlyCard().shufflePlayback)
        cache.clear()
        every { store.cachedSelection(1, any()) } returns TvdbOrderPreference(111, "alternate")
        refresh.update { it.copy(metadata = it.metadata + 1) }
        runCurrent()
        assertTrue(home.onlyCard().chooseEpisode)
        assertFalse(home.onlyCard().canMarkWatched())
    }

    @Test fun pipelineOmissionWithNegativeMetadataKeepsTrackerShow() = runTest {
        setup()
        val home = home()
        assertEquals(id, home.onlyCard().contentId())
        minute()
        cache[key] = null
        negative[key] = currentTime
        assertSame(snapshot, native.value.afterTrackingRefresh(emptyList(), emptyList(), hasLoadedRemoteProgress = false))
        native.update { it.afterTrackingRefresh(emptyList(), emptyList(), hasLoadedRemoteProgress = true) }
        runCurrent()
        assertEquals(id, home.onlyCard().contentId())
    }

    @Test fun negativeMetadataRetriesAfterExpiryWithoutAnotherInput() = runTest {
        setup()
        val home = home()
        assertEquals(id, home.onlyCard().contentId())
        minute()
        cache[key] = null
        negative[key] = currentTime
        refresh.update { it.copy(metadata = it.metadata + 1) }
        runCurrent()
        advanceTimeBy(5 * 60_000 + 10_001)
        runCurrent()
        assertEquals(listOf(id), requests)
        assertTrue(home.onlyCard().shufflePlayback)
    }

    @Test fun selectedOrderPendingWithoutKeyKeepsTrackerShowAfterPipelineReplacement() = runTest {
        setup()
        val home = home()
        assertEquals(id, home.onlyCard().contentId())
        minute()
        cache[key] = summary(video.copy(episodeIdentity = null)).copy(tvdbOrder = false)
        every { vm.metaRepository.getMetaFromAllAddons(any(), any(), any()) } returns
            flowOf(NetworkResult.Success(tvdbFixtureMeta(listOf(video.copy(episodeIdentity = null))).copy(episodeOrder = null)))
        assertNull(vm.selectedOrderSeed(seed))
        assertSame(snapshot, native.value.afterTrackingRefresh(emptyList(), emptyList(), hasLoadedRemoteProgress = false))
        native.update { it.afterTrackingRefresh(emptyList(), emptyList(), hasLoadedRemoteProgress = true) }
        runCurrent()
        assertEquals(id, home.onlyCard().contentId())
        assertFalse("pending identity must not offer a guessed shuffle target", home.onlyCard().shufflePlayback)
        assertTrue(home.onlyCard().chooseEpisode)
        assertFalse(home.onlyCard().canMarkWatched())
    }

    @Test fun revisitWithOnlyAnUnavailableUnwatchedEpisodeKeepsNativeNextUp() = runTest {
        setup()
        val home = home()
        assertEquals(id, home.onlyCard().contentId())
        minute()
        cache[key] = summary(video.copy(available = false))
        refresh.update { it.copy(visit = 2) }
        runCurrent()
        assertEquals(card.info.videoId, (home.onlyCard() as ContinueWatchingItem.NextUp).info.videoId)
        assertFalse(home.onlyCard().shufflePlayback)
    }

    @Test fun refreshedSplitPickRetainsVerifiedTrackingIdentityAndContext() = runTest {
        setup()
        val home = home()
        assertEquals(id, home.onlyCard().contentId())
        minute()
        val split = video.copy(episodeIdentity = video.episodeIdentity!!.copy(canonicalEpisodeIds = listOf(11L, 12L)))
        cache[key] = summary(split)
        refresh.update { it.copy(visit = 2) }
        runCurrent()
        val next = home.onlyCard() as ContinueWatchingItem.NextUp
        assertEquals(11L, next.info.tvdbEpisodeId)
        assertEquals(split.tvdbPlaybackContext(), next.info.tvdbContext)
    }

    @Test fun previouslySelectedEpisodeLosesItsSourceDuringTheSameVisit() = runTest {
        setup()
        val home = home()
        assertTrue(home.onlyCard().shufflePlayback)
        minute()
        val unresolved = video.copy(episodeIdentity = video.episodeIdentity!!.copy(providerVideoId = null))
        assertTrue(listOf(unresolved).watchableEpisodes().isEmpty())
        cache[key] = summary(unresolved)
        refresh.update { it.copy(metadata = it.metadata + 1) }
        runCurrent()
        val retained = home.onlyCard()
        assertEquals(id, retained.contentId())
        assertTrue(retained.chooseEpisode)
        assertFalse(retained.canMarkWatched())
    }

    @Test fun unresolvedResumeNeverRemovesTheWholeGroupedShow() = runTest {
        setup()
        val home = home()
        assertEquals(id, home.onlyCard().contentId())
        minute()
        val resume = ContinueWatchingItem.InProgress(seed.copy(videoId = "unknown", tvdbEpisodeId = 999,
            position = 30, episodeTitle = "Another episode"))
        native.value = snapshot.copy(continueWatchingItems = listOf(card, resume))
        runCurrent()
        assertEquals(resume.progress, (home.onlyCard() as ContinueWatchingItem.InProgress).progress)
    }

    @Test fun trackerSeedWithUnavailableUnwatchedEpisodeStillOpensSeries() = runTest {
        setup()
        native.value = HomeUiState()
        cache[key] = summary(video.copy(available = false))
        val only = home().onlyCard()
        assertTrue(only.chooseEpisode)
        assertFalse(only.shufflePlayback)
        assertFalse(only.canMarkWatched())
    }

    @Test fun cacheInvalidationDuringLookupRetriesWithoutAnotherHomeInput() = runTest {
        setup()
        cache.clear()
        val home = home()
        assertEquals(id, home.onlyCard().contentId())
        advanceTimeBy(1_000)
        vm.cwMetadataLoads.invalidate(setOf(id))
        refresh.update { it.copy(metadata = it.metadata + 1) }
        runCurrent()
        advanceTimeBy(CW_META_NEGATIVE_CACHE_TTL_MS + 10_001)
        runCurrent()
        assertTrue(home.onlyCard().shufflePlayback)
    }

    @Test fun rawCachedMetadataForSelectedOrderIsReplacedWithoutAnotherHomeInput() = runTest {
        setup()
        cache[key] = summary(video.copy(episodeIdentity = null)).copy(tvdbOrder = false)
        val home = home()
        assertTrue(home.onlyCard().chooseEpisode)
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(listOf(id), requests)
        assertTrue(home.onlyCard().shufflePlayback)
    }

    @Test fun activeHomeLookupSurvivesReplacementOfTheFirstWaiter() = runTest {
        setup()
        cache.clear()
        val first = launch { vm.resolveCwMeta(seed) }
        advanceTimeBy(1_000)
        val second = async { vm.resolveCwMeta(seed) }
        runCurrent()
        first.cancelAndJoin()
        assertNotNull(second.await())
        assertEquals("one upstream request despite replacement", listOf(id), requests)
    }

    @Test fun disabledShuffleWithMissingTvdbOrderOffersSeriesOnlyUntilTrackerRemoval() = runTest {
        setup()
        profile.value = EpisodeShuffleProfile(1, false)
        native.value = HomeUiState()
        cache[key] = summary(video).copy(tvdbOrder = false, tvdbPending = true)
        negative[key] = currentTime
        val home = home()
        val pending = home.onlyCard()
        assertTrue(pending.chooseEpisode)
        assertFalse(pending.canMarkWatched())
        var details: String? = null
        vm.verifyTvdbPlayback(pending, { fail("Played an unverified episode") }, { series, _, _ -> details = series })
        runCurrent()
        assertEquals(id, details)
        seeds.value = emptyList()
        runCurrent()
        assertTrue(home.value.continueWatchingItems.isEmpty())
    }

    @Test fun fourRestoredShuffleCardsStayVisibleWhileOneOrderTakesTenSeconds() = runTest {
        setup()
        cache.clear()
        val ids = listOf(id, "fast1", "fast2", "fast3")
        profile.value = EpisodeShuffleProfile(1, true, ids.associateWith { EpisodeShuffleSettings(true) })
        native.value = snapshot.copy(continueWatchingItems = ids.map { card.copy(info = card.info.copy(contentId = it)) })
        seeds.value = ids.map { seed.copy(contentId = it) }
        val home = home()
        for (elapsed in listOf(0L, 1_001L, 5_001L, 4_000L)) {
            advanceTimeBy(elapsed)
            runCurrent()
            assertEquals(ids.toSet(), home.value.continueWatchingItems.map { it.contentId() }.toSet())
        }
        assertTrue(home.value.continueWatchingItems.all { it.shufflePlayback })
        assertEquals(1, requests.count { it == id })
    }

    @Test fun slowSelectedOrderCompletesOnceAlongsideThreeFastShows() = runTest {
        setup()
        cache.clear()
        val results = (listOf(id, "fast1", "fast2", "fast3")).map {
            async { vm.resolveCwMeta(seed.copy(contentId = it)) }
        }.awaitAll()
        assertTrue(results.all { it?.tvdbOrder == true })
        assertEquals(1, requests.count { it == id })
    }
}
