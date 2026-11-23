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
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.EpisodeIdentity
import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.domain.model.tvdbPlaybackContext
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressRepositoryProfileIsolationTest {
    @Test fun `selected watch time survives TVDB translation and every history writer for single and bulk marks`() = runTest {
        val watchedAt = 1_578_000_000_000L
        val providers = TrackingProviderId.entries.map { id -> mockk<TrackingProgressProvider>(relaxed = true) {
            every { providerId } returns id
            every { isAuthenticated } returns flowOf(true)
        } }.toSet()
        val writers = TrackingProviderId.entries.map { id -> mockk<TrackingHistoryWriter>(relaxed = true) {
            every { providerId } returns id
        } }.toSet()
        val tvdb = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>(relaxed = true)
        coEvery { tvdb.identifyProgress(1, any()) } answers { secondArg() }
        coEvery { tvdb.resolveAll(1, any(), any(), completed = true, coverageChangesOnly = false) } answers {
            val display = thirdArg<TrackingMediaReference>()
            listOf(display.copy(episode = display.episode!!.copy(season = 1, number = display.episode!!.number + 10)))
        }
        val h = harness(1, tvdb, providerSet = providers, writers = writers)
        fun episode(number: Int): WatchProgress {
            val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(42, "dvd", 100L + number, 2, number,
                listOf(200L + number), "tt1234567:2:$number")
            return progress("tt1234567", 1, 100).copy(contentType = "series", season = 2, episode = number,
                videoId = context.requestVideoId, tvdbEpisodeIds = context.episodeIds, tvdbContext = context)
        }
        h.repository.markAsCompleted(episode(1), watchedAt = watchedAt)
        h.repository.markAsCompletedBatch(listOf(episode(2), episode(3)), watchedAt = watchedAt)
        coVerify { h.progressPreferences.saveProgress(match { it.lastWatched == watchedAt && it.episode == 1 }, 1) }
        coVerify { h.progressPreferences.saveProgressBatch(match { it.map { p -> p.lastWatched } == listOf(watchedAt, watchedAt) }, 1) }
        for (writer in writers) {
            coVerify { writer.addToHistory(1, match { rows -> rows.singleOrNull()?.let {
                it.watchedAtEpochMs == watchedAt && it.userSelectedWatchTime && it.media.episode?.number == 11
            } == true }) }
            coVerify { writer.addToHistory(1, match { rows -> rows.map { it.media.episode?.number } == listOf(12, 13) &&
                rows.all { it.watchedAtEpochMs == watchedAt && it.userSelectedWatchTime } }) }
        }
    }
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
        // This test changes episode order after startup. Wait for the real local source;
        // the repository no longer emits a provisional local source on construction.
        harness.repository.observeRemoteProgressLoaded().first()
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
            every { harness.progressPreferences.observeDeviceProgress(1, id.name) } returns flowOf(listOf(partial))
            every { harness.progressPreferences.observeAllProgress(1) } returns flowOf(local)
            every { harness.progressPreferences.observeAllRawProgress(1) } returns flowOf(local)
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

    @Test fun `native and ordinary local watched copies cannot restore history absent from selected tracker`() = kotlinx.coroutines.runBlocking {
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
            assertEquals(mapOf("tt123" to setOf(5 to 13)), harness.repository.getWatchedShowEpisodes())
        }
        coVerify(exactly = 0) { harness.watchedPreferences.unmarkAsWatched(any(), any(), any(), any()) }
    }

    @Test fun `unwatch retains older TVDB completion seed behind a newer resume row`() = kotlinx.coroutines.runBlocking {
        val tvdb = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>(relaxed = true)
        every { tvdb.observeSelectedSeries(1) } returns flowOf(setOf(1L))
        val h = harness(1, tvdb)
        every { h.watchedPreferences.allItems } returns flowOf(emptyList())
        val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(1, "dvd", 104, 1, 4, listOf(104), "show:1:4")
        val e4 = progress("show", 100, 100).copy(contentType = "series", season = 1, episode = 4,
            videoId = context.requestVideoId, tvdbContext = context, tvdbEpisodeIds = context.episodeIds)
        val resume = e4.copy(videoId = "show:1:1", episode = 1, position = 47,
            lastWatched = 200, tvdbContext = null, tvdbEpisodeIds = null)
        val e5 = e4.copy(videoId = "show:1:5", episode = 5, lastWatched = 300,
            tvdbEpisodeIds = listOf(105),
            tvdbContext = context.copy(episodeId = 105, episode = 5, episodeIds = listOf(105), requestVideoId = "show:1:5"))
        val records = MutableStateFlow(listOf(e4, resume, e5))
        every { h.progressPreferences.observeAllRawProgress(1) } returns records
        every { h.progressPreferences.observeAllProgress(1) } returns flowOf(listOf(resume))
        kotlinx.coroutines.withTimeout(5_000) {
            assertEquals(listOf(e5), h.repository.observeNextUpSeeds().first())
            records.value = listOf(e4, resume) // committed deletion of E5
            assertEquals(listOf(e4), h.repository.observeNextUpSeeds().first())
        }
        coVerify(exactly = 0) { h.progressPreferences.saveProgress(any(), any()) }
    }

    @Test fun `startup waits for the selected tracker before reporting history readiness`() = kotlinx.coroutines.runBlocking {
        val source = kotlinx.coroutines.CompletableDeferred<WatchProgressSource>()
        val provider = mockk<com.nuvio.tv.core.tracking.TrackingProgressProvider> {
            every { providerId } returns com.nuvio.tv.core.tracking.TrackingProviderId.TRAKT
            every { isAuthenticated } returns flowOf(true)
            every { remoteProgressLoaded } returns flowOf(false)
        }
        val h = harness(1, provider = provider, selectedSource = kotlinx.coroutines.flow.flow { emit(source.await()) })
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            h.repository.observeRemoteProgressLoaded().first()
        }
        source.complete(WatchProgressSource.TRAKT)
        assertEquals(false, kotlinx.coroutines.withTimeout(5_000) { first.await() })
    }

    @Test fun `selected order retains a new Simkl or MDBList pause beside completed history at the same coordinates`() = runTest {
        for (id in listOf(TrackingProviderId.SIMKL, TrackingProviderId.MDBLIST)) {
            val completed = progress("show", 100, 100).copy(contentType = "series", videoId = "show:6:16",
                season = 6, episode = 16, source = WatchProgress.SOURCE_REMOTE_HISTORY, trackingProviderId = id.storageId)
            val pause = completed.copy(position = 0, duration = 0, progressPercent = .5f, lastWatched = 3_000,
                source = if (id == TrackingProviderId.SIMKL) WatchProgress.SOURCE_SIMKL_PLAYBACK else WatchProgress.SOURCE_REMOTE_PLAYBACK)
            val provider = mockk<TrackingProgressProvider>(relaxed = true) {
                every { providerId } returns id
                every { isAuthenticated } returns flowOf(true)
                every { episodeProgress("show") } returns flowOf(mapOf((6 to 16) to completed))
                every { allProgress } returns flowOf(listOf(pause))
            }
            val tvdb = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>(relaxed = true)
            every { tvdb.observeSelection(1, "show") } returns flowOf(com.nuvio.tv.data.tvdb.TvdbOrderPreference(73244, "alttwo"))
            coEvery { tvdb.projectProgress(1, "show", any(), any(), any()) } coAnswers {
                val records = arg<List<WatchProgress>>(3)
                org.junit.Assert.assertTrue(records.contains(completed))
                org.junit.Assert.assertTrue(records.contains(pause))
                mapOf((6 to 17) to pause.copy(episode = 17))
            }
            val h = harness(1, tvdb, provider, WatchProgressSource.valueOf(id.name))
            every { h.progressPreferences.observeEpisodeRecords("show", 1) } returns flowOf(emptyList())
            assertEquals(.5f, h.repository.getAllEpisodeProgress("show").first().getValue(6 to 17).progressPercent)
        }
    }

    @Test fun `partial rewatch removal fans out to every tracker and deletes only the mapped local pause`() = runTest {
        val parent = "tt0386676"
        val video = Video("$parent:6:17", "The Delivery Part 1 (Extended Cut)", null, null, season = 6, episode = 17, overview = null,
            episodeIdentity = EpisodeIdentity(10719652, 73244, "alttwo", EpisodeCoordinates(6, 17), EpisodeCoordinates(6, 16),
                providerVideoId = "$parent:6:17", providerCoordinates = EpisodeCoordinates(6, 17), canonicalTvdbEpisodeId = 7931991))
        val context = requireNotNull(video.tvdbPlaybackContext())
        val resume = progress(parent, 0, 0).copy(contentType = "series", videoId = video.id, season = 6, episode = 17,
            episodeTitle = video.title, lastWatched = 3_000, progressPercent = 1f, tvdbContext = context,
            tvdbEpisodeIds = context.episodeIds, tvdbCompletedEpisodeIds = context.episodeIds)
        val native = resume.copy(videoId = "$parent:6:16", season = 6, episode = 16, tvdbContext = null,
            tvdbEpisodeId = 1832421, tvdbEpisodeIds = null, tvdbCompletedEpisodeIds = null)
        val sibling = native.copy(lastWatched = 2_000)
        val completed = native.copy(lastWatched = 1_000, progressPercent = 100f)
        val target = buildTrackingMediaReference("series", parent, native.videoId, seasonNumber = 6, episodeNumber = 16)
        val tvdb = mockk<com.nuvio.tv.data.tvdb.TvdbTrackingResolver>(relaxed = true)
        coEvery { tvdb.resolveAll(1, any(), any(), any()) } returns listOf(target)
        coEvery { tvdb.projectProgress(1, parent, any(), listOf(native), any()) } returns mapOf((6 to 17) to resume)
        coEvery { tvdb.projectProgress(1, parent, any(), listOf(sibling), any()) } returns mapOf((6 to 18) to
            resume.copy(videoId = "$parent:6:18", episode = 18, tvdbEpisodeIds = listOf(7932001),
                tvdbContext = context.copy(episodeId = 10719653, episode = 18, episodeIds = listOf(7932001))))
        val providers = listOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL, TrackingProviderId.MDBLIST).map { id ->
            mockk<TrackingProgressProvider>(relaxed = true) {
                every { providerId } returns id
                every { isAuthenticated } returns flowOf(true)
            }
        }
        val cw = mockk<com.nuvio.tv.data.cw.ContinueWatchingRepository>(relaxed = true)
        val h = harness(1, tvdb, providerSet = providers.toSet(), cw = cw)
        coEvery { h.progressPreferences.getAllRawEntries(1) } returns
            mapOf("pause" to native, "other-part" to sibling, "completed" to completed)
        h.repository.removeEpisodePartialWatch(parent, video, resume)
        providers.forEach { provider ->
            coVerify(exactly = 1) { provider.removeEpisodePause(target, resume) }
            coVerify(exactly = 0) { provider.removeProgress(any(), any(), any()) }
        }
        coVerify(exactly = 1) { h.progressPreferences.removeMatchingProgress(mapOf("pause" to native), 1) }
        coVerify(exactly = 0) { h.watchedPreferences.unmarkAsWatched(any(), any(), any(), any()) }
        coVerify(exactly = 1) { cw.partialWatchRemoved(1, resume) }
    }

    @Test fun `a failed tracker pause removal retains the local pause for retry`() = runTest {
        val provider = mockk<TrackingProgressProvider>(relaxed = true) {
            every { providerId } returns TrackingProviderId.SIMKL
            every { isAuthenticated } returns flowOf(true)
            coEvery { removeEpisodePause(any(), any()) } throws java.io.IOException("fixture failure")
        }
        val h = harness(1, providerSet = setOf(provider))
        val video = Video("show:1:2", "Episode", null, null, season = 1, episode = 2, overview = null)
        val resume = progress("show", 1, 100).copy(contentType = "series", videoId = video.id, season = 1, episode = 2)
        coEvery { h.progressPreferences.getAllRawEntries(1) } returns mapOf("pause" to resume)
        org.junit.Assert.assertTrue(runCatching { h.repository.removeEpisodePartialWatch("show", video, resume) }.isFailure)
        coVerify(exactly = 0) { h.progressPreferences.removeMatchingProgress(any(), any()) }
    }

    @Test fun `CW and Details read only each selected tracker even with foreign mirrored pauses locally`() = kotlinx.coroutines.runBlocking {
        for (selected in TrackingProviderId.entries) {
            val providers = TrackingProviderId.entries.map { id ->
                val row = progress("show", 30, 100).copy(contentType = "series", season = 1, episode = id.ordinal + 1,
                    videoId = "show:1:${id.ordinal + 1}", lastWatched = 100 + id.ordinal.toLong(),
                    poster = "poster", backdrop = "backdrop", episodeTitle = "Episode", trackingProviderId = id.storageId)
                mockk<TrackingProgressProvider> {
                    every { providerId } returns id
                    if (id == selected) {
                        every { isAuthenticated } returns flowOf(true)
                        every { remoteProgressLoaded } returns flowOf(true)
                        every { allProgress } returns flowOf(listOf(row))
                        every { nextUpSeeds } returns flowOf(listOf(row.copy(position = 100)))
                        every { episodeProgress("show") } returns flowOf(mapOf((1 to row.episode!!) to row))
                        every { episodeProgress("foreign") } returns flowOf(emptyMap())
                        every { watchedItems } returns flowOf(emptyList())
                        coEvery { watchedShowEpisodes() } returns mapOf("show" to setOf(1 to row.episode!!))
                    }
                    // Every other progress/auth getter is deliberately unstubbed: any read fails.
                }
            }
            val h = harness(1, progressSource = WatchProgressSource.valueOf(selected.name), providerSet = providers.toSet())
            val foreign = progress("foreign", 99, 100).copy(source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
                trackingProviderId = "simkl", tvdbEpisodeIds = listOf(42))
            every { h.progressPreferences.observeAllProgress(1) } returns flowOf(listOf(foreign))
            every { h.progressPreferences.observeAllRawProgress(1) } returns flowOf(listOf(foreign))
            every { h.watchedPreferences.allItems } returns flowOf(listOf(com.nuvio.tv.domain.model.WatchedItem("foreign", "series", "Foreign", 9, 9, 999)))
            kotlinx.coroutines.withTimeout(5000) {
                assertEquals(listOf(selected.storageId), h.repository.allProgress.first().map { it.trackingProviderId })
                assertEquals(listOf(selected.ordinal + 1), h.repository.observeNextUpSeeds().first().map { it.episode })
                assertEquals(setOf(1 to selected.ordinal + 1), h.repository.getAllEpisodeProgress("show").first().keys)
                assertEquals(mapOf("show" to setOf(1 to selected.ordinal + 1)), h.repository.getWatchedShowEpisodes())
                assertEquals(emptyMap<Pair<Int, Int>, Long>(), h.repository.watchedEpisodeTimes("foreign").first())
            }
            providers.filter { it.providerId != selected }.forEach { other ->
                io.mockk.verify(exactly = 0) { other.allProgress }
                io.mockk.verify(exactly = 0) { other.episodeProgress(any()) }
                io.mockk.verify(exactly = 0) { other.nextUpSeeds }
                io.mockk.verify(exactly = 0) { other.isAuthenticated }
            }
        }
    }

    @Test fun `disconnected selected tracker does not fall back to cloud local progress`() = kotlinx.coroutines.runBlocking {
        val provider = mockk<TrackingProgressProvider> {
            every { providerId } returns TrackingProviderId.TRAKT
            every { isAuthenticated } returns flowOf(false)
            every { remoteProgressLoaded } returns flowOf(true)
            every { allProgress } returns flowOf(emptyList())
        }
        val h = harness(1, provider = provider, progressSource = WatchProgressSource.TRAKT)
        every { h.progressPreferences.observeAllProgress(1) } returns flowOf(listOf(progress("foreign", 50, 100)))
        kotlinx.coroutines.withTimeout(5000) {
            assertEquals(false, h.repository.observeRemoteProgressLoaded().first())
            assertEquals(emptyList<WatchProgress>(), h.repository.allProgress.first())
        }
    }

    private fun harness(activeProfileId: Int,
        tvdb: com.nuvio.tv.data.tvdb.TvdbTrackingResolver? = null,
        provider: com.nuvio.tv.core.tracking.TrackingProgressProvider? = null,
        progressSource: WatchProgressSource = WatchProgressSource.NUVIO_SYNC,
        selectedSource: kotlinx.coroutines.flow.Flow<WatchProgressSource> = flowOf(progressSource),
        providerSet: Set<TrackingProgressProvider> = setOfNotNull(provider),
        cw: com.nuvio.tv.data.cw.ContinueWatchingRepository? = null, writers: Set<TrackingHistoryWriter> = emptySet()): Harness {
        val activeProfile = MutableStateFlow(activeProfileId)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.resolvedActiveProfileId } returns activeProfile
        every { profileManager.activeProfileId } returns activeProfile

        val progressPreferences = mockk<WatchProgressPreferences>(relaxed = true)
        coEvery { progressPreferences.getAllRawEntries(any()) } returns emptyMap()
        every { progressPreferences.observeDeviceProgress(any(), any()) } returns flowOf(emptyList())
        val watchedPreferences = mockk<WatchedItemsPreferences>(relaxed = true)
        val mutationStore = mockk<WatchStateMutationStore>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns false
        val traktSettings = mockk<TraktSettingsDataStore>(relaxed = true)
        every { traktSettings.watchProgressSource } returns MutableStateFlow(progressSource)
        coEvery { traktSettings.getWatchProgressSource(any()) } returns progressSource
        every { traktSettings.resolvedWatchProgressSource(any()) } returns selectedSource

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = progressPreferences,
            traktSettingsDataStore = traktSettings,
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true) {
                every { nextUpFromFurthestEpisode } returns MutableStateFlow(true)
            },
            watchProgressSyncService = mockk<WatchProgressSyncService>(relaxed = true),
            watchedItemsPreferences = watchedPreferences,
            watchedItemsSyncService = mockk<WatchedItemsSyncService>(relaxed = true),
            authManager = authManager,
            metaRepository = mockk<MetaRepository>(relaxed = true),
            tmdbService = mockk<TmdbService>(relaxed = true),
            profileManager = profileManager,
            trackingProgressProviders = TrackingProgressProviderRegistry(providerSet),
            trackingHistoryWriters = TrackingHistoryWriterRegistry(writers),
            mutationStore = mutationStore,
            serverWatched = mockk(relaxed = true),
            tvdbTracking = tvdb,
            cwRepository = cw
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
