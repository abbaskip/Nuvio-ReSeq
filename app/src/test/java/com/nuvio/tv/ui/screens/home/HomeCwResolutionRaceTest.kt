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
    @get:org.junit.Rule val androidRename = com.nuvio.tv.data.cw.AndroidAtomicRenameRule()
    private val created = mutableListOf<HomeViewModel>()
    @Before fun setup() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun cleanup() {
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
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
            coEvery { nextUpLease(stored, true) } returns stored.lease.copy(nextUpGeneration = 1)
            every { settledProgress } returns flowOf(ContinueWatchingRepository.SettledProgress(stored.lease,
                stored.members.values.mapNotNull { it.progress }, emptyList(), emptyList()))
            coEvery { enrich(any(), any(), any(), any()) } returns stored
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
            every { watchProgressSource } returns MutableStateFlow(WatchProgressSource.valueOf(stored.source))
        }
        val metadata = mockk<MetaRepository>(relaxed = true) {
            // Hold enrichment after the tracker-derived lightweight cards have been submitted.
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
            coVerify(exactly = 1) { vm.continueWatchingRepository.enrich(any(), any(), any(), false) }
            assertEquals(3, vm.continueWatchingRepository.projection.value!!.members.size)
            assertTrue(vm._uiState.value.continueWatchingItems.isEmpty())
            assertFalse("lightweight completion is not UI publication", vm.initialCwResolved.value)
            assertFalse(observedAtResolution.isCompleted)
            firstEmission.complete(Unit)
            assertEquals(items.map { it.contentId }.toSet(), withTimeout(5_000) { observedAtResolution.await() }.toSet())
            withTimeout(5_000) { publicHome.first { it.continueWatchingItems.size == 3 } }
        } finally { observer.cancel(); vm.viewModelScope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun genuinelyEmptyProjectionResolvesOnlyWhenItPublishes() = runBlocking {
        val firstEmission = CompletableDeferred<Unit>()
        val vm = home(CwProjection(profileId = 1, source = "TRAKT"), firstEmission, CompletableDeferred())
        assertFalse(vm.initialCwResolved.value)
        firstEmission.complete(Unit)
        withTimeout(5_000) { vm.initialCwResolved.first { it } }
        assertTrue(vm._uiState.value.continueWatchingItems.isEmpty())
    }

    @Test fun caughtUpCloudShowWithOnlyFinaleWatchedClearsRestoredPremiere() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("cw-cloud-finale").toFile()
        val id = "tt0108778"
        val finale = WatchProgress(id, "series", "Show", null, null, null, "$id:1:4", 1, 4, "Finale",
            100, 100, System.currentTimeMillis())
        val source = WatchProgressSource.NUVIO_SYNC
        val state = CwMembershipReducer.progress(CwProjection(profileId = 1, source = source.name), listOf(finale))
        val oldCard = state.nextUp.single().copy(videoId = "$id:1:1", season = 1, episode = 1, chooseEpisode = false)
        val store = CwProjectionStore(java.io.File(directory, "cw_enrichment"))
        store.write(CwMembershipReducer.enrich(state, state.lease, nextUp = listOf(oldCard)))
        val progress = mockk<WatchProgressRepository>(relaxed = true) {
            every { allProgress } returns flowOf(listOf(finale))
            every { observeNextUpSeeds() } returns flowOf(listOf(finale))
            every { observeRemoteProgressLoaded() } returns flowOf(true)
            every { watchedItems } returns flowOf(emptyList())
            every { getAllEpisodeProgress(id) } returns flowOf(mapOf((1 to 4) to finale))
            every { watchedEpisodeTimes(id) } returns flowOf(mapOf((1 to 4) to finale.lastWatched))
            every { shouldUseAsNextUpSeed(any(), any()) } returns true
            every { activeProviderContinueWatchingCutoffEpochMs(any(), any()) } returns null
            coEvery { prepareNextUpSeed(any()) } returns finale
        }
        val owner = ContinueWatchingRepository(mockk { every { filesDir } returns directory },
            mockk { every { resolvedActiveProfileId } returns flowOf(1); every { activeProfileId } returns MutableStateFlow(1) },
            mockk { coEvery { getWatchProgressSource(1) } returns source
                every { resolvedWatchProgressSource(1) } returns flowOf(source) }, javax.inject.Provider { progress })
        val vm = home(owner.snapshot(), CompletableDeferred(Unit), CompletableDeferred(), owner, progress)
        try {
            assertEquals(1, owner.projection.value!!.nextUp.single().episode)
            vm.cwMetaCache["series:$id"] = CwMetaSummary(id, "Show", null, null, null, null,
                emptyList(), null, null, null, null, (1..4).map { n ->
                    CwVideoSummary("$id:1:$n", "Episode $n", "2000-01-01", null, 1, n, null)
                })
            vm.setHomeContentActive(true)
            owner.refreshProjection()
            vm.loadContinueWatchingPipeline()
            val hidden = withTimeout(10_000) { owner.projection.filterNotNull().first {
                it.members[id]?.progress?.isCompleted() == true && it.nextUp.isEmpty()
            } }
            assertEquals(listOf(finale), ownedShuffleSeeds(hidden, 1))
            assertTrue(store.read(1, source.name).forPresentation().nextUp.isEmpty())
        } finally {
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun friendsPublishesWhileAnotherSeriesLookupIsStillWaiting() = runBlocking {
        exerciseCommittedHistory(homeVisible = true, slowSibling = true)
    }

    @Test fun unwatchRecomputesBeforeReturningFromDetailsToHome() = runBlocking {
        exerciseCommittedHistory(homeVisible = false, slowSibling = false)
    }

    @Test fun undoUsesRemainingHistoryBeforeRemoteRefreshAndHomeReturn() = runBlocking {
        exerciseCommittedHistory(homeVisible = false, slowSibling = false, undo = true)
    }

    @Test fun completingAwayFromHomeStartsResolutionWithoutAnotherRefresh() = runBlocking {
        exerciseCommittedHistory(homeVisible = false, slowSibling = false, completionOnly = true)
    }

    private suspend fun exerciseCommittedHistory(homeVisible: Boolean, slowSibling: Boolean,
        undo: Boolean = false, completionOnly: Boolean = false) {
        val directory = java.nio.file.Files.createTempDirectory("cw-committed-history").toFile()
        val now = System.currentTimeMillis()
        fun completion(id: String, episode: Int, time: Long) = WatchProgress(id, "series", id,
            null, null, null, "$id:6:$episode", 6, episode, "Episode $episode", 100, 100, time,
            source = WatchProgress.SOURCE_TRAKT_HISTORY)
        val first = completion("tt0108778", 1, now - 1_000)
        val second = completion("tt0108778", 2, now)
        val waiting = completion("slow-series", 1, now - 2_000)
        val rows = MutableStateFlow(listOf(second))
        val seeds = MutableStateFlow(listOf(second) + listOfNotNull(waiting.takeIf { slowSibling }))
        val history = MutableStateFlow(mapOf((6 to 1) to first, (6 to 2) to second))
        val blocked = CompletableDeferred<Unit>()
        val source = mockk<WatchProgressRepository>(relaxed = true) {
            every { allProgress } returns rows
            every { observeNextUpSeeds() } returns seeds
            every { observeRemoteProgressLoaded() } returns flowOf(true)
            every { watchedItems } returns flowOf(emptyList())
            every { isDroppedShow(any()) } returns false
            every { hasActiveTrackingProgressProvider() } returns true
            every { shouldUseAsNextUpSeed(any(), any()) } answers { firstArg<WatchProgress>().isCompleted() }
            every { activeProviderContinueWatchingCutoffEpochMs(any(), any()) } returns null
            every { getAllEpisodeProgress("tt0108778") } returns history
            every { getAllEpisodeProgress("tt0108778", 1) } returns history
            every { watchedEpisodeTimes("tt0108778") } returns history.map { it.mapValues { it.value.lastWatched } }
            coEvery { prepareNextUpSeed(any()) } coAnswers {
                val seed = firstArg<WatchProgress>()
                if (seed.contentId == "slow-series") blocked.await()
                seed
            }
        }
        val owner = ContinueWatchingRepository(
            mockk { every { filesDir } returns directory },
            mockk { every { resolvedActiveProfileId } returns flowOf(1); every { activeProfileId } returns MutableStateFlow(1) },
            mockk {
                coEvery { getWatchProgressSource(1) } returns WatchProgressSource.TRAKT
                every { resolvedWatchProgressSource(1) } returns flowOf(WatchProgressSource.TRAKT)
            }, javax.inject.Provider { source })
        val vm = home(owner.snapshot(), CompletableDeferred(Unit), CompletableDeferred(), owner, source)
        vm.cwMetaCache["series:tt0108778"] = CwMetaSummary("tt0108778", "Friends", null, null, null,
            null, emptyList(), null, null, null, null, (1..4).map { episode ->
                CwVideoSummary("tt0108778:6:$episode", "Episode $episode", "2000-01-01T00:00:00Z",
                    null, 6, episode, null)
            })
        try {
            vm.setHomeContentActive(homeVisible)
            if (completionOnly) {
                rows.value = emptyList(); seeds.value = emptyList(); history.value = emptyMap()
                owner.start()
                withTimeout(5_000) { owner.settledProgress.first() }
                rows.value = listOf(second); seeds.value = listOf(second)
                history.value = mapOf((6 to 1) to first, (6 to 2) to second)
            }
            owner.committedProgress(1, listOf(second))
            if (!completionOnly) owner.refreshProjection()
            vm.loadContinueWatchingPipeline()
            if (slowSibling || completionOnly) {
                val card = withTimeout(5_000) { vm.uiState.first { state ->
                    state.continueWatchingItems.any { it.contentId() == "tt0108778" && !it.chooseEpisode }
                } }.continueWatchingItems.first { it.contentId() == "tt0108778" } as ContinueWatchingItem.NextUp
                assertEquals(3, card.info.episode)
                assertFalse(blocked.isCompleted)
            } else {
                withTimeout(5_000) { vm._uiState.first { state ->
                    state.continueWatchingItems.any { it.contentId() == "tt0108778" && !it.chooseEpisode &&
                        (it as? ContinueWatchingItem.NextUp)?.info?.episode == 3 }
                } }
                // The tracker write already committed. Home must consume that state without
                // another network refresh or a navigation event from the Details screen.
                rows.value = listOf(first)
                // Undo can remove the provider's only aggregate seed; the per-episode
                // history still contains E1 and must supply the new offer immediately.
                seeds.value = if (undo) emptyList() else listOf(first)
                history.value = mapOf((6 to 1) to first)
                if (undo) owner.deviceWatchRemoved(1, DeviceWatchEvent("second", second,
                    second.lastWatched, second.lastWatched, removed = true))
                else owner.historyRemoved(1, "tt0108778", setOf(6 to 2))
                assertFalse("A resolved offer must not turn into a loading placeholder",
                    vm._uiState.value.continueWatchingItems.any { it.chooseEpisode })
                val card = withTimeout(5_000) { vm._uiState.first { state ->
                    state.continueWatchingItems.any { it.contentId() == "tt0108778" && !it.chooseEpisode &&
                        (it as? ContinueWatchingItem.NextUp)?.info?.episode == 2 }
                } }.continueWatchingItems.single() as ContinueWatchingItem.NextUp
                assertEquals(2, card.info.episode)
                assertFalse(vm.homeContentActive.value)
            }
        } finally {
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            directory.deleteRecursively()
        }
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
            every { observeRemoteProgressLoaded() } returns flowOf(true)
            every { observeNextUpSeeds() } returns seeds
            every { watchedItems } returns flowOf(emptyList())
            every { isDroppedShow(any()) } returns false
            every { getAllEpisodeProgress(effective.id, 1) } returns rows.map { entries ->
                entries.associateBy { it.season!! to it.episode!! }
            }
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
            vm.loadContinueWatchingPipeline()
            owner.committedProgress(1, listOf(completed))
            withTimeout(5_000) { vm.uiState.first { it.continueWatchingItems.singleOrNull() is ContinueWatchingItem.NextUp } }
            // This is the real durable removal -> remaining provider progress -> Home flow.
            owner.historyRemoved(1, effective.id, emptySet(), completed.tvdbContentIds)
            seeds.value = emptyList()
            rows.value = listOf(raw)
            owner.refreshProjection()
            assertTrue(owner.snapshot().inProgress.single().chooseEpisode)
            // A background unknown cannot publish a partial resume/placeholder. The
            // settled card is installed once its selected catalogue is available.
            assertTrue(vm.uiState.value.continueWatchingItems.none { it is ContinueWatchingItem.InProgress })
            vm.cwMetaCache["series:${effective.id}"] = CwMetaSummary(effective.id, effective.name,
                null, null, null, null, emptyList(), null, null, null, null,
                effective.videos.map { CwVideoSummary(it.id, it.title, it.released, it.thumbnail,
                    it.season, it.episode, it.overview, episodeIdentity = it.episodeIdentity) }, tvdbOrder = true)
            vm.cwPipelineRefreshTrigger.value++
            val result = withTimeout(5_000) { vm.uiState.first {
                it.continueWatchingItems.singleOrNull()?.let { card -> !card.chooseEpisode &&
                    (card as? ContinueWatchingItem.InProgress)?.progress?.tvdbContext != null } == true
            } }.continueWatchingItems.single() as ContinueWatchingItem.InProgress
            assertEquals(2, result.displayEpisode) // Provider E1 is DVD E2, never numeric guessing.
            assertEquals(47L, result.progress.position)
            assertEquals(297989L, result.progress.tvdbEpisodeId)
            assertEquals(listOf(raw), rows.value) // Identity decoration does not rewrite tracker progress.
        } finally {
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}


