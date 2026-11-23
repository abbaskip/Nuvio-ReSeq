package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktHiddenItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveCountDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowEpisodeProgressDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowProgressResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowSeasonProgressDto
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedShowSeasonDto
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedShowEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktLastActivitiesMediaDto
import com.nuvio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktProgressServiceFailureTest {
    private val traktApi = mockk<TraktApi>()

    @Test
    fun `batch history sends the selected date for each episode`() = runBlocking {
        val service = createService()
        val first = WatchProgress("tt0386676", "series", "The Office", null, null, null, "tt0386676:1:1",
            1, 1, null, 1L, 1L, 946_684_800_000L)
        val second = first.copy(episode = 2, videoId = "tt0386676:1:2", lastWatched = first.lastWatched + 86_400_000L)
        val request = io.mockk.slot<com.nuvio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto>()
        coEvery { traktApi.addHistory(any(), capture(request)) } returns
            Response.success(TraktHistoryAddResponseDto(added = TraktHistoryRemoveCountDto(episodes = 2)))
        service.markSeasonWatchedBatch(listOf(first, second))
        val timestamps = request.captured.shows.orEmpty().single().seasons.orEmpty().single().episodes.orEmpty().map {
            java.time.Instant.parse(it.watchedAt).toEpochMilli()
        }
        assertEquals(listOf(first.lastWatched, second.lastWatched), timestamps)
    }

    @Test
    fun `failed playback fetch keeps the previous continue watching snapshot`() = runBlocking {
        val service = createService()
        withTimeout(TIMEOUT_MS) {
            service.observeAllProgress().first { items -> items.any { it.contentId == MOVIE_ID } }
        }

        val failed = CompletableDeferred<Unit>()
        coEvery { traktApi.getPlayback(any(), "movies", any(), any()) } answers {
            failed.complete(Unit)
            Response.error(503, "".toResponseBody())
        }
        service.refreshNow()
        withTimeout(TIMEOUT_MS) { failed.await() }
        delay(SETTLE_MS)

        val current = service.observeAllProgress().first()
        assertTrue(current.any { it.contentId == MOVIE_ID })
    }

    @Test
    fun `failed dropped-shows fetch keeps shows hidden from progress`() = runBlocking {
        val service = createService()
        withTimeout(TIMEOUT_MS) {
            while (!service.isShowHiddenFromProgress(DROPPED_SHOW_ID)) delay(20)
        }

        val failed = CompletableDeferred<Unit>()
        coEvery { traktApi.getHiddenItems(any(), any(), any(), any(), any()) } answers {
            failed.complete(Unit)
            Response.error(503, "".toResponseBody())
        }
        // Exercise the awaited CW refresh. A queued signal during the initial
        // onStart emission can be coalesced before the signal collector subscribes.
        service.refreshForContinueWatching()
        withTimeout(TIMEOUT_MS) { failed.await() }
        delay(SETTLE_MS)

        assertTrue(service.isShowHiddenFromProgress(DROPPED_SHOW_ID))
    }

    @Test
    fun `TVDB canonical first waits for the real snapshot then reuses an empty loaded snapshot`() = runBlocking {
        val service = createService()
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { traktApi.getShowProgressWatched(any(), any()) } coAnswers {
            requested.complete(Unit)
            release.await()
            Response.success(com.nuvio.tv.data.remote.dto.trakt.TraktShowProgressResponseDto(seasons = emptyList()))
        }
        val first = async { service.observeCanonicalEpisodeProgress("tt0000003").first() }
        withTimeout(TIMEOUT_MS) { requested.await() }
        delay(100)
        assertTrue("Not-loaded must not be emitted as empty history", !first.isCompleted)
        release.complete(Unit)
        assertTrue(withTimeout(TIMEOUT_MS) { first.await() }.isEmpty())
        assertTrue(withTimeout(TIMEOUT_MS) { service.observeCanonicalEpisodeProgress("tt0000003").first() }.isEmpty())
        io.mockk.coVerify(exactly = 1) { traktApi.getShowProgressWatched(any(), any()) }
    }

    @Test
    fun `cancelled initial history fetch can be retried immediately`() = runBlocking {
        val service = createService()
        val requested = CompletableDeferred<Unit>()
        coEvery { traktApi.getShowProgressWatched(any(), any()) } coAnswers {
            requested.complete(Unit)
            CompletableDeferred<Unit>().await()
            error("cancelled request must not finish")
        }
        val first = async { service.observeCanonicalEpisodeProgress("tt0000003").first() }
        withTimeout(TIMEOUT_MS) { requested.await() }
        first.cancel()
        first.join()
        coEvery { traktApi.getShowProgressWatched(any(), any()) } returns Response.success(
            TraktShowProgressResponseDto(seasons = listOf(TraktShowSeasonProgressDto(6,
                listOf(TraktShowEpisodeProgressDto(1, true, "2026-11-18T01:00:00Z"))))))
        val retried = withTimeout(TIMEOUT_MS) { service.observeCanonicalEpisodeProgress("tt0000003").first() }
        assertEquals(setOf(6 to 1), retried.keys)
        coVerify(exactly = 2) { traktApi.getShowProgressWatched(any(), any()) }
    }

    @Test
    fun `removing a rewatch deletes only the latest matching pause and never removes history`() = runBlocking {
        val service = createService()
        val showId = "tt0386676"
        val show = TraktShowDto(title = "The Office", ids = TraktIdsDto(imdb = showId))
        val ep = TraktEpisodeDto(title = "The Delivery", season = 6, number = 16, ids = TraktIdsDto(trakt = 182129))
        val old = TraktPlaybackItemDto(31, "episode", 10f, "2026-11-10T01:00:00.000Z", show = show, episode = ep)
        val latest = old.copy(id = 32, pausedAt = "2026-11-14T01:10:51.000Z", progress = 47.18f)
        val sibling = latest.copy(id = 33, episode = ep.copy(number = 10, ids = TraktIdsDto(trakt = 182123)))
        coEvery { traktApi.getPlayback(any(), "episodes", page = 1, limit = 100) } returns
            Response.success(listOf(sibling, latest, old))
        coEvery { traktApi.deletePlayback(any(), 32) } returns Response.success(Unit)
        val resume = WatchProgress(contentId = showId, contentType = "series", name = "The Office",
            poster = null, backdrop = null, logo = null, videoId = "$showId:6:16", season = 6, episode = 16,
            episodeTitle = ep.title, position = 0, duration = 0, lastWatched = 1L, progressPercent = 47.18f,
            traktEpisodeId = 182129)
        val target = buildTrackingMediaReference("series", showId, resume.videoId, seasonNumber = 6, episodeNumber = 16)
        service.removeEpisodePause(target, resume)
        coVerify(exactly = 1) { traktApi.deletePlayback(any(), 32) }
        coVerify(exactly = 0) { traktApi.deletePlayback(any(), 31) }
        coVerify(exactly = 0) { traktApi.deletePlayback(any(), 33) }
        coVerify(exactly = 0) { traktApi.removeHistory(any(), any()) }
    }

    @Test
    fun `committed tracker unwatch updates the canonical history read by selected order Home`() = runBlocking {
        val service = createService()
        val show = "tt0000003"
        // Native E3 (display E5) was watched most recently. E7 was watched first.
        val episodes = listOf(7, 4, 3).mapIndexed { index, number ->
            TraktShowEpisodeProgressDto(number, true, "2026-01-0${index + 1}T00:00:00Z")
        }
        val tracker = MutableStateFlow(episodes)
        coEvery { traktApi.getShowProgressWatched(any(), any()) } answers {
            Response.success(TraktShowProgressResponseDto(seasons = listOf(TraktShowSeasonProgressDto(1, tracker.value))))
        }
        coEvery { traktApi.getWatchedShows(any(), any(), any(), any()) } answers {
            val watched = tracker.value.map { TraktWatchedShowEpisodeDto(it.number, 1, it.lastWatchedAt) }
            Response.success(listOf(TraktWatchedShowItemDto(show = TraktShowDto(ids = TraktIdsDto(imdb = show)),
                seasons = listOf(TraktWatchedShowSeasonDto(1, watched)))), okhttp3.Headers.headersOf("X-Pagination-Page-Count", "1"))
        }
        coEvery { traktApi.removeHistory(any(), any()) } answers {
            tracker.value = tracker.value.filterNot { it.number == 3 }
            Response.success(TraktHistoryRemoveResponseDto(deleted = TraktHistoryRemoveCountDto(episodes = 1)))
        }
        coEvery { traktApi.addHistory(any(), any()) } answers {
            tracker.value = episodes
            Response.success(TraktHistoryAddResponseDto(added = TraktHistoryRemoveCountDto(episodes = 1)))
        }
        // Finish the initial bulk fetch before the individual show is opened.
        withTimeout(TIMEOUT_MS) { service.observeRemoteProgressLoaded().first { it } }
        val history = service.observeCanonicalEpisodeProgress(show)
        val initial = withTimeout(TIMEOUT_MS) { history.first() }
        assertEquals(setOf(1 to 3, 1 to 4, 1 to 7), initial.keys)
        assertEquals(3, service.observeWatchedShowSeeds().first().single().episode)
        service.removeFromHistory(show, season = 1, episode = 3, mappingResolved = true)
        assertEquals(setOf(1 to 4, 1 to 7), withTimeout(TIMEOUT_MS) { history.first() }.keys)
        assertEquals(4, service.observeWatchedShowSeeds().first().single().episode)
        assertEquals(setOf(1 to 4, 1 to 7), service.getWatchedShowEpisodes()[show])
        service.markAsWatched(initial.getValue(1 to 3).copy(traktEpisodeId = 103), title = null, year = null)
        assertEquals(initial.keys, history.first().keys)
        assertEquals(3, service.observeWatchedShowSeeds().first().single().episode)
        io.mockk.coVerify(exactly = 1) { traktApi.removeHistory(any(), any()) }
    }

    @Test fun `native episode reads keep the previous completion alongside the current rewatch`() = runBlocking {
        val show = TraktShowDto(ids = TraktIdsDto(imdb = "tt0386676", trakt = 447))
        val episode = TraktEpisodeDto(number = 16, season = 6, ids = TraktIdsDto(trakt = 182129))
        val old = "2015-05-01T00:00:00Z"
        val pause = TraktPlaybackItemDto(77, "episode", 49.75f, "2026-11-15T12:59:06Z", show = show, episode = episode)
        val service = createService(listOf(pause))
        withTimeout(TIMEOUT_MS) { service.observeRemoteProgressLoaded().first { it } }
        val season = TraktShowSeasonProgressDto(6, listOf(TraktShowEpisodeProgressDto(16, true, old)))
        coEvery { traktApi.getShowProgressWatched(any(), any()) } returns Response.success(TraktShowProgressResponseDto(
            seasons = listOf(season)))
        val records = withTimeout(TIMEOUT_MS) { service.observeCanonicalWatchRecords("tt0386676").first { it.size == 2 } }
        assertEquals(77L, records.single { it.source == WatchProgress.SOURCE_TRAKT_PLAYBACK }.traktPlaybackId)
        assertEquals(java.time.Instant.parse(old).toEpochMilli(), records.single { it.isCompleted() }.lastWatched)
    }

    @Test fun `remove last watch deletes one Trakt play and replaying the action leaves the earlier watch alone`() = runBlocking {
        val service = createService()
        val episode = TraktEpisodeDto(number = 16, season = 6, ids = TraktIdsDto(trakt = 182129))
        val date = "2026-11-15T12:59:06Z"
        val earlier = com.nuvio.tv.data.remote.dto.trakt.TraktHistoryItemDto(3000000001L, "2015-05-01T00:00:00Z", episode = episode)
        val latest = earlier.copy(id = 3000000002L, watchedAt = date)
        var plays = listOf(latest, earlier)
        coEvery { traktApi.getHistoryById(any(), "episodes", "182129") } answers { Response.success(plays) }
        coEvery { traktApi.removeHistoryPlay(any(), match { it.ids == listOf(3000000002L) }) } answers {
            plays = listOf(earlier)
            Response.success(com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveResponseDto(
                deleted = com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveCountDto(episodes = 1)))
        }
        coEvery { traktApi.getShowProgressWatched(any(), any()) } returns Response.success(TraktShowProgressResponseDto())
        val event = WatchProgress("tt0386676", "series", "The Office", null, null, null, "tt0386676:6:17", 6, 17,
            "The Delivery Part 1", 1, 1, java.time.Instant.parse(date).toEpochMilli(),
            source = WatchProgress.SOURCE_TRAKT_HISTORY, traktEpisodeId = 182129)
        service.removeLastEpisodeWatch(1, event)
        assertTrue(runCatching { service.removeLastEpisodeWatch(1, event) }.isFailure)
        assertEquals(listOf(earlier), plays)
        coVerify(exactly = 1) { traktApi.removeHistoryPlay(any(), any()) }
        coVerify(exactly = 0) { traktApi.removeHistory(any(), any()) }
        coVerify(exactly = 0) { traktApi.deletePlayback(any(), any()) }
    }

    @Test fun `a projected complete first part removes its dated pause and preserves every historical play`() = runBlocking {
        val service = createService()
        val show = TraktShowDto(ids = TraktIdsDto(imdb = "tt0386676", trakt = 447))
        val episode = TraktEpisodeDto(number = 16, season = 6, ids = TraktIdsDto(trakt = 182129))
        val stamp = "2026-11-15T12:59:06Z"
        val row = TraktPlaybackItemDto(77, "episode", 49.75f, stamp, show = show, episode = episode)
        coEvery { traktApi.getPlayback(any(), "episodes", page = 1, limit = 100) } returns Response.success(listOf(row, row.copy(id = 66)))
        coEvery { traktApi.deletePlayback(any(), 77) } returns Response.success(Unit)
        coEvery { traktApi.getShowProgressWatched(any(), any()) } returns Response.success(TraktShowProgressResponseDto())
        val event = WatchProgress("tt0386676", "series", "The Office", null, null, null, "tt0386676:6:17", 6, 17,
            "The Delivery Part 1", 1, 1, java.time.Instant.parse(stamp).toEpochMilli(), progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK, traktPlaybackId = 77, traktEpisodeId = 182129)
        service.removeLastEpisodeWatch(1, event)
        assertTrue(runCatching { service.removeLastEpisodeWatch(1, event.copy(lastWatched = event.lastWatched + 1000)) }.isFailure)
        coVerify(exactly = 1) { traktApi.deletePlayback(any(), 77) }
        coVerify(exactly = 0) { traktApi.deletePlayback(any(), 66) }
        coVerify(exactly = 0) { traktApi.removeHistoryPlay(any(), any()) }
        coVerify(exactly = 0) { traktApi.removeHistory(any(), any()) }
    }

    @Test fun `device undo waits for confirmation then deletes only its dated rewatch history id`() = runBlocking {
        val service = createService()
        withTimeout(TIMEOUT_MS) { service.observeRemoteProgressLoaded().first { it } }
        coEvery { traktApi.getPlayback(any(), "episodes", page = 1, limit = 100) } returns Response.success(emptyList())
        val show = TraktShowDto(ids = TraktIdsDto(imdb = "tt0312173"))
        val ep = TraktEpisodeDto(season = 3, number = 2, ids = TraktIdsDto(trakt = 555))
        val stamp = "2026-11-17T01:00:00Z"
        val time = java.time.Instant.parse(stamp).toEpochMilli()
        val old = com.nuvio.tv.data.remote.dto.trakt.TraktUserEpisodeHistoryItemDto(id = 9001, watchedAt = "2015-01-01T00:00:00Z", show = show, episode = ep)
        val latest = old.copy(id = 9002, watchedAt = stamp)
        val progress = WatchProgress("tt0312173", "series", "Friends", null, null, null, "tt0312173:3:2", 3, 2,
            null, 100, 100, time, deviceWatchEventId = "own")
        val target = buildTrackingMediaReference("series", progress.contentId, progress.videoId, seasonNumber = 3, episodeNumber = 2)
        val event = com.nuvio.tv.core.tracking.TrackingWatchEvent("own", progress, time - 30_000, time + 2_000)
        assertTrue(!service.removeDatedWatchEvent(target, event))
        var records = listOf(latest, old)
        coEvery { traktApi.getEpisodeHistory(any(), any(), any(), any(), any()) } answers { Response.success(records) }
        coEvery { traktApi.removeHistoryPlay(any(), match { it.ids == listOf(9002L) }) } answers {
            records = listOf(old)
            Response.success(TraktHistoryRemoveResponseDto(deleted = TraktHistoryRemoveCountDto(episodes = 1)))
        }
        coEvery { traktApi.getShowProgressWatched(any(), any()) } returns Response.success(TraktShowProgressResponseDto())
        assertTrue(service.removeDatedWatchEvent(target, event))
        assertEquals(listOf(old), records)
        coVerify(exactly = 1) { traktApi.removeHistoryPlay(any(), match { it.ids == listOf(9002L) }) }
        coVerify(exactly = 0) { traktApi.removeHistory(any(), any()) }
    }

    @Test fun `device first-part undo removes its combined 50 percent pause and leaves old history untouched`() = runBlocking {
        val service = createService()
        withTimeout(TIMEOUT_MS) { service.observeRemoteProgressLoaded().first { it } }
        val show = TraktShowDto(ids = TraktIdsDto(imdb = "tt0386676"))
        val episode = TraktEpisodeDto(season = 6, number = 16, ids = TraktIdsDto(trakt = 182129))
        val stamp = "2026-11-17T01:00:00Z"
        val time = java.time.Instant.parse(stamp).toEpochMilli()
        val pause = TraktPlaybackItemDto(77, "episode", 50f, stamp, show = show, episode = episode)
        coEvery { traktApi.getPlayback(any(), "episodes", page = 1, limit = 100) } returns Response.success(listOf(pause, pause.copy(id = 66, pausedAt = "2015-01-01T00:00:00Z")))
        coEvery { traktApi.deletePlayback(any(), 77) } returns Response.success(Unit)
        coEvery { traktApi.getShowProgressWatched(any(), any()) } returns Response.success(TraktShowProgressResponseDto())
        val progress = WatchProgress("tt0386676", "series", "Office", null, null, null, "tt0386676:6:17", 6, 17,
            "The Delivery Part 1", 1, 1, time, deviceWatchEventId = "own")
        assertTrue(service.removeDatedWatchEvent(buildTrackingMediaReference("series", progress.contentId, "tt0386676:6:16",
            seasonNumber = 6, episodeNumber = 16), com.nuvio.tv.core.tracking.TrackingWatchEvent("own", progress,
            time - 60_000, time + 2_000, completionExpected = false)))
        coVerify(exactly = 1) { traktApi.deletePlayback(any(), 77) }
        coVerify(exactly = 0) { traktApi.deletePlayback(any(), 66) }
        coVerify(exactly = 0) { traktApi.removeHistoryPlay(any(), any()) }
        coVerify(exactly = 0) { traktApi.removeHistory(any(), any()) }
    }

    private fun createService(episodePlayback: List<TraktPlaybackItemDto> = emptyList()): TraktProgressService {
        coEvery { traktApi.getLastActivities(any()) } returns Response.success(
            TraktLastActivitiesResponseDto(
                movies = TraktLastActivitiesMediaDto(watchedAt = "2026-01-01T00:00:00.000Z"),
                episodes = TraktLastActivitiesMediaDto(watchedAt = "2026-01-01T00:00:00.000Z")
            )
        )
        coEvery { traktApi.getPlayback(any(), "movies", any(), any()) } returns Response.success(
            listOf(
                TraktPlaybackItemDto(
                    id = 1L,
                    type = "movie",
                    progress = 40f,
                    pausedAt = "2026-01-01T00:00:00.000Z",
                    movie = TraktMovieDto(title = "Movie", ids = TraktIdsDto(trakt = 1, imdb = MOVIE_ID))
                )
            )
        )
        coEvery { traktApi.getPlayback(any(), "episodes", any(), any()) } returns Response.success(episodePlayback)
        coEvery { traktApi.getEpisodeHistory(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getHiddenItems(any(), any(), any(), any(), any()) } returns Response.success(
            listOf(TraktHiddenItemDto(show = TraktShowDto(ids = TraktIdsDto(trakt = 2, imdb = DROPPED_SHOW_ID))))
        )
        coEvery { traktApi.getWatched(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getWatchedShows(any(), any(), any(), any()) } returns Response.success(emptyList())

        val authService = mockk<TraktAuthService>()
        every { authService.isCircuitClosed() } returns true
        coEvery { authService.executeAuthorizedRequest<Any?>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Any?>>().invoke("Bearer test")
        }
        coEvery { authService.executeAuthorizedWriteRequest<Any?>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Any?>>().invoke("Bearer test")
        }

        coEvery { authService.executeAuthorizedWriteRequest<Any?>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Any?>>().invoke("Bearer test")
        }

        val metaRepository = mockk<MetaRepository>()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any()) } returns emptyFlow()
        val settings = mockk<TraktSettingsDataStore>()
        every { settings.continueWatchingDaysCap } returns
            flowOf(TraktSettingsDataStore.DEFAULT_CONTINUE_WATCHING_DAYS_CAP)
        val layout = mockk<LayoutPreferenceDataStore>()
        every { layout.nextUpFromFurthestEpisode } returns MutableStateFlow(false)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return TraktProgressService(
            appContext = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthService = authService,
            metaRepository = metaRepository,
            tmdbService = mockk<TmdbService>(relaxed = true),
            traktSettingsDataStore = settings,
            layoutPreferenceDataStore = layout,
            traktEpisodeMappingService = mockk(relaxed = true),
            profileManager = profileManager,
            watchedSeriesStateHolder = watchedSeries
        )
    }

    private companion object {
        const val MOVIE_ID = "tt0000001"
        const val DROPPED_SHOW_ID = "tt0000002"
        const val TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 500L
    }
}
