package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressRepositoryProfileIsolationTest {
    @Test fun `switching from TVDB to Provider Default restores native progress without restarting or resubscribing`() = runTest {
        val tvdb = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>()
        val selection = MutableStateFlow<com.nuvio.tv.data.tvdb.TvdbOrderPreference?>(com.nuvio.tv.data.tvdb.TvdbOrderPreference(73244, "alternate"))
        every { tvdb.observeSelection(1, "tt0386676") } returns selection
        val native = progress("tt0386676", 100, 100).copy(contentType = "series", videoId = "tt0386676:4:14",
            season = 4, episode = 14, source = WatchProgress.SOURCE_TRAKT_HISTORY)
        val translated = native.copy(videoId = "translated", episode = 18)
        coEvery { tvdb.projectProgress(1, "tt0386676", any(), any(), any()) } returns mapOf((4 to 18) to translated)
        coEvery { tvdb.identifyProgress(1, any()) } answers { secondArg() }
        val harness = harness(1, tvdb)
        every { harness.progressPreferences.observeEpisodeRecords("tt0386676", 1) } returns flowOf(emptyList())
        every { harness.progressPreferences.getAllEpisodeProgress("tt0386676", 1) } returns flowOf(mapOf((4 to 14) to native))
        val values = mutableListOf<Map<Pair<Int, Int>, WatchProgress>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { harness.repository.getAllEpisodeProgress("tt0386676", 1).collect { values += it } }
        runCurrent()
        assertEquals(translated, values.last()[4 to 18])
        selection.value = null
        runCurrent()
        assertEquals(mapOf((4 to 14) to native), values.last())
    }
    @Test
    fun `TVDB playback removal uses verified destination coordinates`() = runTest {
        val provider = mockk<com.nuvio.tv.core.tracking.TrackingProgressProvider>(relaxed = true)
        every { provider.providerId } returns com.nuvio.tv.core.tracking.TrackingProviderId.TRAKT
        every { provider.isAuthenticated } returns flowOf(true)
        val tvdb = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>()
        coEvery { tvdb.resolve(1, provider.providerId, any()) } answers {
            thirdArg<com.nuvio.tv.core.tracking.TrackingMediaReference>().copy(
                episode = com.nuvio.tv.core.tracking.TrackingEpisode(2, 9, tvdbId = 297994, numberingProvider = "trakt"))
        }
        coEvery { tvdb.isSelected(any(), any()) } returns false
        val harness = harness(1, tvdb, provider)
        harness.repository.removeProgress("tt0303461", 1, 6)
        coVerify(exactly = 1) { provider.removeProgress("tt0303461", 2, 9) }
        coVerify(exactly = 0) { provider.removeProgress("tt0303461", 1, 6) }
        coVerify(exactly = 1) { harness.progressPreferences.removeProgress("tt0303461", 1, 6, 1) }
    }

    @Test
    fun `unresolved TVDB playback removal keeps remote records untouched`() = runTest {
        val provider = mockk<com.nuvio.tv.core.tracking.TrackingProgressProvider>(relaxed = true)
        every { provider.providerId } returns com.nuvio.tv.core.tracking.TrackingProviderId.TRAKT
        every { provider.isAuthenticated } returns flowOf(true)
        val tvdb = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>()
        coEvery { tvdb.resolve(1, provider.providerId, any()) } returns null
        coEvery { tvdb.isSelected(any(), any()) } returns false
        val harness = harness(1, tvdb, provider)
        harness.repository.removeProgress("tt0303461", 1, 6)
        coVerify(exactly = 0) { provider.removeProgress(any(), any(), any()) }
        coVerify(exactly = 1) { harness.progressPreferences.removeProgress("tt0303461", 1, 6, 1) }
    }
    @Test fun `automatic completion cannot bypass ambiguous combined duration coverage`() = runTest {
        val harness = harness(1)
        val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(73244, "alttwo", 10718533, 5, 16,
            listOf(427605, 427606), "tt0386676:5:16", 44)
        val incomplete = progress("tt0386676", 22 * 60_000L, 22 * 60_000L).copy(contentType = "series",
            videoId = context.requestVideoId, season = 5, episode = 16, tvdbContext = context,
            tvdbEpisodeIds = context.episodeIds, tvdbCoverageVerified = false)
        harness.repository.markAsCompleted(incomplete, 1, broadcastTrackingHistory = false)
        coVerify(exactly = 1) { harness.progressPreferences.saveProgress(incomplete, 1) }
        coVerify(exactly = 0) { harness.watchedPreferences.markAsWatched(any(), any()) }
        coVerify(exactly = 0) { harness.watchedPreferences.markAsWatchedBatch(any(), any()) }
    }

    @Test
    fun `explicit save writes and queues only its playback profile`() = runTest {
        val harness = harness(activeProfileId = 2)
        val progress = progress("item", position = 2_000L, duration = 10_000L)

        harness.repository.saveProgress(progress, profileId = 1, syncRemote = true)

        coVerify(exactly = 1) {
            harness.mutationStore.queueProgressUpserts(mapOf("item" to progress), 1)
        }
        coVerify(exactly = 1) {
            harness.progressPreferences.saveProgress(progress, profileId = 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.saveProgress(progress, profileId = 2)
        }
    }

    @Test
    fun `completion queues progress and watched mutations for one profile`() = runTest {
        val harness = harness(activeProfileId = 2)
        val progress = progress("item", position = 2_000L, duration = 10_000L)

        harness.repository.markAsCompleted(
            progress = progress,
            profileId = 1,
            broadcastTrackingHistory = false
        )

        coVerify(exactly = 1) {
            harness.mutationStore.queueProgressUpserts(
                match { entries ->
                    entries.keys == setOf("item") &&
                        entries.getValue("item").position == 10_000L &&
                        entries.getValue("item").duration == 10_000L
                },
                1
            )
        }
        coVerify(exactly = 1) {
            harness.mutationStore.queueWatchedUpserts(
                match { items -> items.single().contentId == "item" },
                1
            )
        }
        coVerify(exactly = 1) {
            harness.watchedPreferences.markAsWatched(
                match { it.contentId == "item" },
                profileId = 1
            )
        }
    }

    @Test
    fun `profile switch during delete key lookup cannot redirect local deletion`() = runTest {
        val harness = harness(activeProfileId = 1)
        val progress = progress("item", position = 2_000L, duration = 10_000L)
        coEvery { harness.progressPreferences.getAllRawEntries(1) } coAnswers {
            harness.activeProfile.value = 2
            mapOf("item" to progress)
        }

        harness.repository.removeProgress("item")

        coVerify(exactly = 1) {
            harness.mutationStore.queueProgressDeletes(listOf("item"), 1)
        }
        coVerify(exactly = 1) {
            harness.progressPreferences.removeProgress("item", null, null, 1)
        }
        coVerify(exactly = 0) {
            harness.progressPreferences.removeProgress("item", null, null, 2)
        }
    }

    @Test
    fun `optimistic watched toggle only applies to the profile that made it`() = runTest {
        val harness = harness(activeProfileId = 1)
        every { harness.progressPreferences.allProgress } returns flowOf(emptyList())
        every { harness.watchedPreferences.allItems } returns flowOf(emptyList())

        harness.repository.applyOptimisticWatchedMovie(setOf("tt0000001"), add = true)
        assertEquals(setOf("tt0000001"), harness.repository.observeWatchedMovieIds().first())

        harness.activeProfile.value = 2
        assertEquals(emptySet<String>(), harness.repository.observeWatchedMovieIds().first())

        harness.activeProfile.value = 1
        assertEquals(setOf("tt0000001"), harness.repository.observeWatchedMovieIds().first())
    }

    @Test
    fun `reverting an optimistic toggle after a profile switch clears it`() = runTest {
        val harness = harness(activeProfileId = 1)
        every { harness.progressPreferences.allProgress } returns flowOf(emptyList())
        every { harness.watchedPreferences.allItems } returns flowOf(emptyList())

        harness.repository.applyOptimisticWatchedMovie(setOf("tt0000001"), add = true)
        harness.activeProfile.value = 2
        harness.repository.revertOptimisticWatchedMovie(setOf("tt0000001"), add = true)

        harness.activeProfile.value = 1
        assertEquals(emptySet<String>(), harness.repository.observeWatchedMovieIds().first())
    }

    @Test fun `combined completion writes only verified native watched records with one completion timestamp`() = runTest {
        val tvdb = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>()
        coEvery { tvdb.nativeCompletedEpisodes(1, any()) } returns listOf(
            com.nuvio.tv.domain.model.EpisodeCoordinates(5, 14) to listOf(101L),
            com.nuvio.tv.domain.model.EpisodeCoordinates(5, 15) to listOf(102L))
        val harness = harness(1, tvdb)
        val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(1, "alttwo", 103, 5, 16,
            listOf(101, 102), "tt123:5:16", 44)
        val completed = progress("tt123", 2_800_000, 2_800_000).copy(contentType = "series",
            videoId = context.requestVideoId, season = 5, episode = 16, tvdbContext = context,
            tvdbEpisodeIds = context.episodeIds)
        harness.repository.markAsCompleted(completed, 1, broadcastTrackingHistory = false)
        coVerify(exactly = 1) { harness.watchedPreferences.markAsWatchedBatch(match { items ->
            items.map { it.season to it.episode }.toSet() == setOf(5 to 14, 5 to 15) &&
                items.map { it.watchedAt }.distinct().size == 1
        }, 1) }
        coVerify(exactly = 0) { harness.watchedPreferences.markAsWatched(any(), any()) }
        coVerify(exactly = 1) { harness.mutationStore.queueWatchedUpserts(match { items ->
            items.size == 2 && items.none { it.episode == 16 }
        }, 1) }
    }

    @Test fun `TVDB local completions cannot override refreshed tracker seeds or partial resume`() = kotlinx.coroutines.runBlocking {
        for (id in com.nuvio.tv.core.tracking.TrackingProviderId.entries) {
            val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(1, "alternate", 103, 5, 16,
                listOf(101, 102), "tt123:5:16", 44)
            val completed = progress("tt123", 100, 100).copy(contentType = "series", season = 5, episode = 16,
                videoId = context.requestVideoId, lastWatched = 3000, tvdbContext = context,
                poster = "poster", backdrop = "backdrop", episodeTitle = "Combined")
            val partial = completed.copy(position = 40, lastWatched = 4000)
            val native = completed.copy(videoId = "tt123:5:15", episode = 15, tvdbContext = null,
                source = WatchProgress.SOURCE_REMOTE_HISTORY, trackingProviderId = id.storageId, lastWatched = 2000)
            val seeds = MutableStateFlow(listOf(native))
            val provider = mockk<com.nuvio.tv.core.tracking.TrackingProgressProvider>(relaxed = true)
            every { provider.providerId } returns id
            every { provider.isAuthenticated } returns flowOf(true)
            every { provider.nextUpSeeds } returns seeds
            every { provider.allProgress } returns seeds
            every { provider.episodeProgress("tt123") } answers { flowOf(mapOf((5 to 13) to native.copy(episode = 13))) }
            every { provider.retainsLocalProgress(any()) } returns false
            val tvdb = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>(relaxed = true)
            every { tvdb.observeSelectedSeries(1) } returns flowOf(setOf(1L))
            every { tvdb.observeSelection(1, "tt123") } returns flowOf(com.nuvio.tv.data.tvdb.TvdbOrderPreference(1, "alternate"))
            every { tvdb.canonicalTraktRecords(1, "tt123", any()) } returns seeds
            coEvery { tvdb.projectProgress(1, "tt123", any(), any(), any()) } answers {
                arg<List<WatchProgress>>(3).associateBy { it.season!! to it.episode!! }
            }
            val harness = harness(1, tvdb, provider, WatchProgressSource.valueOf(id.name))
            val local = listOf(completed, partial)
            every { harness.progressPreferences.observeAllProgress(1) } returns flowOf(local)
            every { harness.progressPreferences.observeEpisodeRecords("tt123", 1) } returns flowOf(local)
            // Wait for the selected provider rather than asserting against initial local fallback.
            kotlinx.coroutines.withTimeout(5000) {
                val before = harness.repository.observeNextUpSeeds().first { it.any { row -> row.trackingProviderId == id.storageId } }
                assertEquals(listOf(native), before)
                seeds.value = listOf(native.copy(episode = 13, videoId = "tt123:5:13", lastWatched = 1000))
                val after = harness.repository.observeNextUpSeeds().first { it.singleOrNull()?.episode == 13 }
                assertEquals(13, after.single().episode)
                val visible = harness.repository.allProgress.first { it.any { row -> row.trackingProviderId == id.storageId } }
                assertEquals(listOf(partial), visible.filter { it.tvdbContext != null })
                val episodes = harness.repository.getAllEpisodeProgress("tt123", 1).first { it[5 to 13] != null }
                assertEquals(partial, episodes[5 to 16])
            }
            coVerify(exactly = 0) { harness.progressPreferences.saveProgress(any(), any()) }
            assertEquals(100L, completed.position)
        }
    }

    @Test fun `TVDB completed records respect Nuvio and unsupported-provider fallback`() {
        val completed = progress("tt123", 100, 100)
        val provider = mockk<com.nuvio.tv.core.tracking.TrackingProgressProvider>()
        every { provider.retainsLocalProgress("tt123") } returns false
        org.junit.Assert.assertFalse(provider.retainsTvdbLocalProgress(completed))
        org.junit.Assert.assertTrue(provider.retainsTvdbLocalProgress(completed.copy(position = 40)))
        org.junit.Assert.assertTrue((null as com.nuvio.tv.core.tracking.TrackingProgressProvider?).retainsTvdbLocalProgress(completed))
        every { provider.retainsLocalProgress("tt123") } returns true
        org.junit.Assert.assertTrue(provider.retainsTvdbLocalProgress(completed))
    }

    @Test fun `TVDB native watched copies cannot restore deleted provider history or change ordinary fallback`() = kotlinx.coroutines.runBlocking {
        val provider = mockk<com.nuvio.tv.core.tracking.TrackingProgressProvider>(relaxed = true)
        every { provider.providerId } returns com.nuvio.tv.core.tracking.TrackingProviderId.TRAKT
        every { provider.isAuthenticated } returns flowOf(true)
        every { provider.retainsLocalProgress(any()) } returns false
        every { provider.retainsLocalWatchedEpisode(any()) } returns true
        val native = progress("tt123", 100, 100).copy(season = 5, episode = 13)
        every { provider.episodeProgress("tt123") } returns flowOf(mapOf((5 to 13) to native))
        every { provider.allProgress } returns flowOf(listOf(native))
        coEvery { provider.watchedShowEpisodes() } returns mapOf("tt123" to setOf(5 to 13))
        val harness = harness(1, provider = provider, progressSource = WatchProgressSource.TRAKT)
        coEvery { harness.watchedPreferences.getAllItems(1) } returns listOf(
            com.nuvio.tv.domain.model.WatchedItem("tt123", "series", "Example", 5, 14, 2000, tvdbEpisodeIds = listOf(101)),
            com.nuvio.tv.domain.model.WatchedItem("tt123", "series", "Example", 5, 20, 1000))
        kotlinx.coroutines.withTimeout(5000) {
            harness.repository.getProviderEpisodeProgress("tt123", 5, 13, 1).first { it == native }
            assertEquals(mapOf("tt123" to setOf(5 to 13, 5 to 20)), harness.repository.getWatchedShowEpisodes())
        }
        coVerify(exactly = 0) { harness.watchedPreferences.unmarkAsWatched(any(), any(), any(), any()) }
    }

    private fun harness(activeProfileId: Int,
        tvdb: com.nuvio.tv.data.tvdb.TvdbTrackingResolver? = null,
        provider: com.nuvio.tv.core.tracking.TrackingProgressProvider? = null,
        progressSource: WatchProgressSource = WatchProgressSource.NUVIO_SYNC): Harness {
        val activeProfile = MutableStateFlow(activeProfileId)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile

        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true)
        coEvery { progressPreferences.getAllRawEntries(any()) } returns emptyMap()
        val watchedPreferences = mockk<WatchedItemsPreferences>(relaxed = true)
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns false
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true)
        every { traktSettings.watchProgressSource } returns MutableStateFlow(progressSource)

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = watchedPreferences,
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = authManager,
            metaRepository = mockk<MetaRepository>(relaxed = true),
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(setOfNotNull(provider)),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(emptySet()),
            mutationStore = mutationStore,
            serverWatched = mockk(relaxed = true),
            tvdbTracking = tvdb
        )
        return Harness(
            repository = repository,
            activeProfile = activeProfile,
            progressPreferences = progressPreferences,
            watchedPreferences = watchedPreferences,
            mutationStore = mutationStore
        )
    }

    private fun progress(contentId: String, position: Long, duration: Long) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = 100L
    )

    private data class Harness(
        val repository: WatchProgressRepositoryImpl,
        val activeProfile: MutableStateFlow<Int>,
        val progressPreferences: WatchProgressPreferences,
        val watchedPreferences: WatchedItemsPreferences,
        val mutationStore: WatchStateMutationStore
    )
}
