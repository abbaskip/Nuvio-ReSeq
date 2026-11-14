package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.cw.*
import com.nuvio.tv.data.local.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.*
import com.nuvio.tv.data.tvdb.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCwResolutionRaceTest {
    private val created = mutableListOf<HomeViewModel>()
    @Before fun setup() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun cleanup() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun home(stored: CwProjection, firstEmission: CompletableDeferred<Unit>,
        lightweightCompleted: CompletableDeferred<Unit>,
        realOwner: ContinueWatchingRepository? = null,
        progressSource: WatchProgressRepository? = null,
        orders: TvdbOrderStore? = null): HomeViewModel {
        val owner = realOwner ?: mockk<ContinueWatchingRepository>(relaxed = true) {
            every { projection } returns MutableStateFlow(stored)
            every { observeProjection() } returns flow { firstEmission.await(); emit(stored); awaitCancellation() }
            coEvery { snapshot() } returns stored
            coEvery { enrich(any(), any(), any()) } returns stored
        }
        val cache = ContinueWatchingEnrichmentCache(owner)
        val progress = progressSource ?: mockk<WatchProgressRepository>(relaxed = true) {
            every { allProgress } returns flowOf(stored.members.values.mapNotNull { it.progress })
            every { observeNextUpSeeds() } returns flowOf(emptyList())
            every { observeRemoteProgressLoaded() } returns flowOf(true)
            every { watchedItems } returns flowOf(emptyList())
            every { getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
            every { activeProviderContinueWatchingCutoffEpochMs(any(), any()) } returns null
            every { hasActiveTrackingProgressProvider() } returns false
        }
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true) {
            every { showUnairedNextUp } returns flowOf(false)
            every { nextUpFromFurthestEpisode } returns MutableStateFlow(true)
            every { continueWatchingSortMode } returns flowOf(ContinueWatchingSortMode.DEFAULT)
            every { detailImdbRatingsVisibility } returns flowOf(DetailImdbRatingsVisibility.SHOW_ALL)
        }
        val tracking = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { continueWatchingDaysCap } returns flowOf(30)
            every { dismissedNextUpKeys } returns flowOf(emptySet())
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.TRAKT)
        }
        val metadata = mockk<MetaRepository>(relaxed = true) {
            // Rich enrichment is reached only after the actual initial and final lightweight
            // publications (and both former early-resolution writes). Keep it suspended here.
            every { getMetaFromAllAddons(any(), any()) } returns flow {
                lightweightCompleted.complete(Unit)
                awaitCancellation()
            }
            every { getMetaFromPrimaryAddon(any(), any()) } returns flow {
                lightweightCompleted.complete(Unit)
                awaitCancellation()
            }
        }
        return HomeViewModel(
            appContext = mockk(relaxed = true), addonRepository = mockk(relaxed = true),
            startupSyncService = mockk(relaxed = true), catalogRepository = mockk(relaxed = true),
            watchProgressRepository = progress, libraryRepository = mockk(relaxed = true),
            metaRepository = metadata,
            episodeShuffleStore = mockk(relaxed = true) {
                every { profiles } returns MutableStateFlow(EpisodeShuffleProfile(1, false, emptyMap()))
            },
            episodeShuffle = EpisodeShuffle(), collectionsDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = layout, playerSettingsDataStore = mockk(relaxed = true),
            tmdbSettingsDataStore = mockk(relaxed = true), mdbListSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = tracking, authSessionNoticeDataStore = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true), tmdbMetadataService = mockk(relaxed = true),
            mdbListRepository = mockk(relaxed = true), imdbEpisodeRatingsRepository = mockk(relaxed = true),
            trailerService = mockk(relaxed = true),
            watchedSeriesStateHolder = mockk(relaxed = true) {
                every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
            },
            cwEnrichmentCache = cache,
            profileManager = mockk(relaxed = true) {
                every { activeProfileId } returns MutableStateFlow(1)
                // Hold unrelated startup work; start the real CW pipeline explicitly below.
                every { activeProfileReady } returns MutableStateFlow(false)
            },
            tvRecommendationManager = mockk(relaxed = true), serverCatalog = mockk(relaxed = true),
            tvdbOrderStore = orders
        ).also { created += it; it.currentTmdbSettings = TmdbSettings(enabled = false) }
    }

    @Test fun lightweightPipelineCannotResolveBeforeStoredProjectionPublishes(): Unit = runBlocking {
        val items = (1..3).map { id -> WatchProgress("tt990000$id", "series", "Fixture $id",
            null, null, null, "tt990000$id:1:1", 1, 1, "Episode 1", 40_000, 100_000, System.currentTimeMillis()) }
        val stored = CwMembershipReducer.progress(CwProjection(profileId = 1, source = "TRAKT"), items)
        val firstEmission = CompletableDeferred<Unit>()
        val lightweightCompleted = CompletableDeferred<Unit>()
        val vm = home(stored, firstEmission, lightweightCompleted)
        vm.setHomeContentActive(true)
        val publicHome = vm.uiState
        val observedAtResolution = CompletableDeferred<List<String>>()
        val observer = launch(Dispatchers.Unconfined) {
            vm.initialCwResolved.first { it }
            observedAtResolution.complete(vm._uiState.value.continueWatchingItems.map { it.contentId() })
        }
        try {
            vm.loadContinueWatchingPipeline()
            withTimeout(10_000) { lightweightCompleted.await() }
            coVerify(atLeast = 2) { vm.continueWatchingRepository.enrich(any(), any(), any()) }
            assertEquals(3, vm.continueWatchingRepository.projection.value!!.members.size)
            assertTrue(vm._uiState.value.continueWatchingItems.isEmpty())
            assertFalse("lightweight completion is not UI publication", vm.initialCwResolved.value)
            assertFalse(observedAtResolution.isCompleted)
            firstEmission.complete(Unit)
            assertEquals(items.map { it.contentId }.toSet(), withTimeout(5_000) { observedAtResolution.await() }.toSet())
            withTimeout(5_000) { publicHome.first { it.continueWatchingItems.size == 3 } }
        } finally { observer.cancel(); vm.viewModelScope.cancel() }
    }

    @Test fun genuinelyEmptyProjectionResolvesOnlyWhenItPublishes() = runBlocking {
        val firstEmission = CompletableDeferred<Unit>()
        val vm = home(CwProjection(profileId = 1, source = "TRAKT"), firstEmission, CompletableDeferred())
        assertFalse(vm.initialCwResolved.value)
        firstEmission.complete(Unit)
        withTimeout(5_000) { vm.initialCwResolved.first { it } }
        assertTrue(vm._uiState.value.continueWatchingItems.isEmpty())
    }

    @Test fun unwatchPublicationResolvesRetainedResumeInSelectedOrder(): Unit = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("cw-home-unwatch").toFile()
        val effective = tvdbEpisodeOverlay(tvdbFixtureMeta(tvdbFixtureSource()), 78874, tvdbFixtureOrder())
        val raw = WatchProgress(effective.id, "series", effective.name, null, null, null,
            "${effective.id}:1:1", 1, 1, "The Train Job", 47, 100, System.currentTimeMillis())
        val last = effective.videos.last()
        val completed = raw.copy(videoId = last.id, season = last.season, episode = last.episode,
            position = 100, lastWatched = raw.lastWatched + 1,
            tvdbEpisodeId = last.episodeIdentity!!.trackingEpisodeId, tvdbContext = last.tvdbPlaybackContext())
        val rows = MutableStateFlow(listOf(completed))
        val seeds = MutableStateFlow(listOf(completed))
        val source = mockk<WatchProgressRepository>(relaxed = true) {
            every { allProgress } returns rows
            every { observeNextUpSeeds() } returns seeds
            every { watchedItems } returns flowOf(emptyList())
            every { isDroppedShow(any()) } returns false
        }
        val order = mockk<TvdbOrderStore>(relaxed = true) {
            coEvery { exportSelections(1) } returns mapOf("alias:${effective.id}" to "78874", "order:78874" to "dvd")
            every { cachedSelection(1, effective.id) } returns TvdbOrderPreference(78874, "dvd")
        }
        val owner = ContinueWatchingRepository(
            mockk { every { filesDir } returns directory },
            mockk { every { resolvedActiveProfileId } returns flowOf(1); every { activeProfileId } returns MutableStateFlow(1) },
            mockk {
                coEvery { getWatchProgressSource(1) } returns WatchProgressSource.TRAKT
                every { resolvedWatchProgressSource(1) } returns flowOf(WatchProgressSource.TRAKT)
            }, javax.inject.Provider { source }, tvdbOrders = order)
        val vm = home(owner.snapshot(), CompletableDeferred(Unit), CompletableDeferred(), owner, source, order)
        try {
            vm.setHomeContentActive(true)
            owner.committedProgress(1, listOf(completed))
            withTimeout(5_000) { vm.uiState.first { it.continueWatchingItems.singleOrNull() is ContinueWatchingItem.NextUp } }
            // This is the real durable removal -> remaining provider progress -> Home flow.
            owner.historyRemoved(1, effective.id, emptySet(), completed.tvdbContentIds)
            seeds.value = emptyList()
            rows.value = listOf(raw)
            owner.refreshProjection()
            assertTrue(owner.snapshot().inProgress.single().chooseEpisode)
            withTimeout(5_000) { vm.uiState.first { it.continueWatchingItems.singleOrNull() is ContinueWatchingItem.InProgress } }
            // Missing metadata keeps support visible; arrival must release the identity fallback.
            assertTrue(vm.uiState.value.continueWatchingItems.single().chooseEpisode)
            vm.cwMetaCache["series:${effective.id}"] = CwMetaSummary(effective.id, effective.name,
                null, null, null, null, emptyList(), null, null, null, null,
                effective.videos.map { CwVideoSummary(it.id, it.title, it.released, it.thumbnail,
                    it.season, it.episode, it.overview, episodeIdentity = it.episodeIdentity) }, tvdbOrder = true)
            vm.shuffleHomeRefresh.value = HomeShuffleRefresh(metadata = 1)
            val result = withTimeout(5_000) { vm.uiState.first {
                it.continueWatchingItems.singleOrNull()?.let { card -> !card.chooseEpisode &&
                    (card as? ContinueWatchingItem.InProgress)?.progress?.tvdbContext != null } == true
            } }.continueWatchingItems.single() as ContinueWatchingItem.InProgress
            assertEquals(2, result.displayEpisode) // Provider E1 is DVD E2, never numeric guessing.
            assertEquals(47L, result.progress.position)
            assertEquals(297989L, result.progress.tvdbEpisodeId)
            assertEquals(listOf(raw), rows.value) // Identity decoration does not rewrite tracker progress.
        } finally {
            vm.viewModelScope.cancel()
            directory.deleteRecursively()
        }
    }
}


