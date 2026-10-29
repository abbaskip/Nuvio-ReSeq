package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.remote.api.*
import com.nuvio.tv.data.remote.dto.trakt.*
import com.nuvio.tv.data.repository.*
import com.nuvio.tv.domain.model.tvdbPlaybackContext
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TvdbTrackingResolverTest {
    private val store = mockk<TvdbOrderStore> {
        coEvery { selected(1, any()) } returns TvdbOrderPreference(78874, "dvd")
    }
    private val overlay = mockk<TvdbEpisodeResolver>()
    private val source = mockk<MetaRepositoryImpl>()
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    private val trakt = mockk<TraktApi>()
    private val auth = mockk<TraktAuthService>()
    private val tmdb = mockk<TmdbApi>()
    private val tmdbService = mockk<TmdbService>()
    private val resolver = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, trakt, auth, tmdb, tmdbService,
        simkl = dagger.Lazy { fixtureSimkl(78874, 297994L to com.nuvio.tv.domain.model.EpisodeCoordinates(2, 9)) })
    private val original = tvdbFixtureMeta(tvdbFixtureSource())
    private val effective = tvdbEpisodeOverlay(original, 78874, tvdbFixtureOrder())
    private val reference = buildTrackingMediaReference("series", original.id, "tt0303461:1:4", "Firefly",
        seasonNumber = 1, episodeNumber = 4, episodeTitle = "Shindig")

    private suspend fun assertLiveScrobbleDelivery(provider: TrackingProviderId, expected: TrackingMediaReference) {
        val adapter = mockk<TrackingScrobbler>(relaxed = true)
        every { adapter.providerId } returns provider
        val registry = mockk<TrackingProviderRegistry> {
            every { connectedScrobblers() } returns listOf(adapter)
        }
        val coordinator = TrackingScrobbleCoordinator(registry, resolver)
        for ((action, percent) in listOf(TrackingScrobbleAction.START to 0.0,
            TrackingScrobbleAction.PAUSE to 37.5, TrackingScrobbleAction.STOP to 37.5)) {
            assertTrue(coordinator.scrobble(action, TrackingScrobbleEvent(reference, percent)).isEmpty())
            coVerify(exactly = 1) { adapter.scrobble(action, TrackingScrobbleEvent(expected, percent)) }
        }
        assertEquals("tt0303461:1:4", reference.catalog!!.videoId)
    }

    private fun prepare() {
        every { source.getCachedMeta("series", original.id) } returns original
        coEvery { overlay.apply(1, original) } returns effective
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `MDBList scrobble mapping scans a large library off the caller UI thread`() = runTest {
        prepare()
        val ui = kotlinx.coroutines.newSingleThreadContext("test-player-ui")
        val native = com.nuvio.tv.data.mdblist.MdbListMedia(com.nuvio.tv.data.mdblist.MdbListIds(
            imdb = original.id, tvdb = 78874, tmdb = 1437))
        val relevant = com.nuvio.tv.data.mdblist.MdbListWatchedRecord(
            com.nuvio.tv.data.mdblist.MdbListItemType.EPISODE, native, "2026-01-01", 1, 6, "Shindig",
            episodeTmdbId = 349207, episodeTvdbId = 297994)
        val unrelated = (1..20_000).map { relevant.copy(episodeTvdbId = 10_000_000L + it, episodeTitle = "Unrelated $it") }
        val mdb = mockk<com.nuvio.tv.data.mdblist.MdbListSyncRepository>()
        coEvery { mdb.ensureLoaded() } coAnswers {
            assertFalse("Library work ran on player UI thread", Thread.currentThread().name.contains("test-player-ui"))
            null
        }
        every { mdb.state } returns MutableStateFlow(com.nuvio.tv.data.mdblist.MdbListSyncState(
            snapshot = com.nuvio.tv.data.mdblist.MdbListSyncSnapshot(1, watched = unrelated + relevant)))
        val isolated = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, trakt, auth, tmdb, tmdbService,
            mdbList = dagger.Lazy { mdb })
        try {
            val target = kotlinx.coroutines.withContext(ui) { isolated.resolve(1, TrackingProviderId.MDBLIST, reference) }
            assertNotNull(target)
            assertEquals(1 to 6, target!!.episode!!.let { it.season to it.number })
            assertEquals(original.id, target.catalog!!.contentId)
            // No public metadata requests or account mutations are needed for verified synced correspondence.
            confirmVerified(trakt, auth, tmdb, tmdbService)
        } finally { ui.close() }
    }

    @Test fun `captured Provider Default bypasses TVDB even after the selected order changes`() = runTest {
        val captured = reference.copy(catalog = reference.catalog!!.copy(providerDefault = true))
        assertSame(captured, resolver.resolve(1, TrackingProviderId.TRAKT, captured))
        confirmVerified(store, overlay, source, trakt, auth, tmdb, tmdbService)
    }

    @Test fun `captured TVDB identity resolves in its own order after returning to Provider Default`() = runTest {
        prepare()
        coEvery { store.selected(1, any()) } returns null
        coEvery { store.knownSeries(1, any()) } returns 78874
        coEvery { overlay.forOrder(1, original, TvdbOrderPreference(78874, "dvd")) } returns effective
        val video = effective.videos.single { it.title == "Shindig" }
        val captured = reference.copy(catalog = reference.catalog!!.copy(tvdbContext = video.tvdbPlaybackContext()))
        val result = resolver.resolve(1, TrackingProviderId.SIMKL, captured)!!
        assertEquals(2 to 9, result.episode!!.let { it.season to it.number })
        assertEquals("tt0303461:1:4", captured.catalog!!.videoId)
        coVerify(exactly = 1) { overlay.forOrder(1, original, TvdbOrderPreference(78874, "dvd")) }
    }

    @Test fun `short combined playback never reaches destination resolution or tracking`() = runTest {
        val guarded = reference.copy(catalog = reference.catalog!!.copy(tvdbCoverageVerified = false))
        assertTrue(resolver.resolveAll(1, TrackingProviderId.TRAKT, guarded, completed = true)!!.isEmpty())
        confirmVerified(store, overlay, source, trakt, auth, tmdb, tmdbService)
    }

    @Test fun `provider default does no online identity resolution`() = runTest {
        coEvery { store.selected(1, any()) } returns null
        assertSame(reference, resolver.resolve(1, TrackingProviderId.TRAKT, reference))
        confirmVerified(overlay, source, trakt, auth, tmdb, tmdbService)
    }

    @Test fun `local identity annotation requires a corroborating title and never guesses remote identities`() = runTest {
        coEvery { store.sources(1, 78874) } returns mapOf(297994L to TvdbSourceReference(297994,
            "tt0303461:1:6", com.nuvio.tv.domain.model.EpisodeCoordinates(1, 6), "shindig"))
        val progress = com.nuvio.tv.domain.model.WatchProgress(original.id, "series", "Firefly", null, null, null,
            "tt0303461:1:6", 1, 6, "Shindig", 50, 100, 1)
        assertEquals(297994L, resolver.identifyProgress(1, progress).tvdbEpisodeId)
        assertNull(resolver.identifyProgress(1, progress.copy(episodeTitle = "Different episode")).tvdbEpisodeId)
        assertNull(resolver.identifyProgress(1, progress.copy(source = "trakt_history")).tvdbEpisodeId)
        confirmVerified(overlay, source, trakt, auth, tmdb, tmdbService)
    }

    @Test fun `Simkl receives verified aired numbering and stable episode ID`() = runTest {
        prepare()
        val result = resolver.resolve(1, TrackingProviderId.SIMKL, reference)!!
        assertEquals(297994L, result.episode?.tvdbId)
        assertEquals(9, result.episode?.number)
        assertEquals(2, result.episode?.season)
        assertEquals("simkl", result.episode?.numberingProvider)
        assertEquals("78874", result.ids.tvdb)
        assertLiveScrobbleDelivery(TrackingProviderId.SIMKL, result)
    }

    @Test fun `Trakt search must confirm the exact TVDB episode`() = runTest {
        prepare()
        coEvery { auth.executeAuthorizedRequest<List<TraktSearchResultDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSearchResultDto>>>().invoke("fixture")
        }
        coEvery { trakt.searchById("fixture", "imdb", original.id, "show") } returns Response.success(listOf(
            TraktSearchResultDto(show = TraktShowDto(ids = TraktIdsDto(tvdb = 78874, trakt = 100)))))
        coEvery { trakt.getShowSeasons("fixture", "100", "episodes,full") } returns Response.success(listOf(
            TraktSeasonDto(number = 2, episodes = listOf(TraktEpisodeDto(title = "Shindig", season = 2, number = 9,
                ids = TraktIdsDto(tvdb = 297994, trakt = 42))))))
        val result = resolver.resolve(1, TrackingProviderId.TRAKT, reference)!!
        assertEquals(2, result.episode?.season)
        assertEquals(9, result.episode?.number)
        assertEquals(42, result.episode?.traktId)
        assertEquals("trakt", result.episode?.numberingProvider)
        assertLiveScrobbleDelivery(TrackingProviderId.TRAKT, result)
    }

    @Test fun `MDBList uses TMDB episode coordinates only after parent confirmation`() = runTest {
        prepare()
        every { tmdbService.apiKey() } returns "fixture"
        coEvery { tmdb.findByExternalId("297994", "fixture", "tvdb_id") } returns Response.success(
            TmdbFindResponse(tvEpisodeResults = listOf(TmdbFindResult(44, name = "Shindig", showId = 1437, seasonNumber = 1, episodeNumber = 4))))
        coEvery { tmdb.getTvExternalIds(1437, "fixture") } returns Response.success(TmdbExternalIdsResponse(1437, tvdbId = 78874))
        val result = resolver.resolve(1, TrackingProviderId.MDBLIST, reference)!!
        assertEquals(4, result.episode?.number)
        assertEquals(44L, result.episode?.tmdbId)
        assertFalse(result.episode!!.usesTvdbSeasonMapping)
        assertLiveScrobbleDelivery(TrackingProviderId.MDBLIST, result)
    }

    @Test fun `an ID collision with another show or unrelated content never scrobbles`() = runTest {
        prepare()
        coEvery { auth.executeAuthorizedRequest<List<TraktSearchResultDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSearchResultDto>>>().invoke("fixture")
        }
        coEvery { trakt.searchById("fixture", "imdb", original.id, "show") } returns Response.success(listOf(
            TraktSearchResultDto(show = TraktShowDto(ids = TraktIdsDto(tvdb = 78874, trakt = 100)))))
        coEvery { trakt.getShowSeasons("fixture", "100", "episodes,full") } returns Response.success(listOf(
            TraktSeasonDto(number = 0, episodes = listOf(TraktEpisodeDto(title = "An unrelated special", season = 0, number = 100,
                ids = TraktIdsDto(tvdb = 297994, trakt = 999))))))
        assertNull(resolver.resolve(1, TrackingProviderId.TRAKT, reference))
        coVerify(exactly = 1) { trakt.getShowSeasons("fixture", "100", "episodes,full") }
    }

    @Test fun `failed overlay never submits fallback numeric tracking`() = runTest {
        prepare()
        coEvery { overlay.apply(1, original) } returns original
        assertNull(resolver.resolve(1, TrackingProviderId.TRAKT, reference))
        confirmVerified(trakt, auth, tmdb)
    }

    @Test fun `missing Simkl correspondence withholds alternate episode numbers`() = runTest {
        prepare()
        val guarded = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, trakt, auth, tmdb, tmdbService,
            simkl = dagger.Lazy { fixtureSimkl(78874) })
        assertNull(guarded.resolve(1, TrackingProviderId.SIMKL, reference))
    }

    @Test fun `MDBList rejects a same-show TVDB collision with unrelated episode content`() = runTest {
        prepare()
        every { tmdbService.apiKey() } returns "fixture"
        coEvery { tmdb.findByExternalId("297994", "fixture", "tvdb_id") } returns Response.success(
            TmdbFindResponse(tvEpisodeResults = listOf(TmdbFindResult(44, name = "Unrelated special", showId = 1437, seasonNumber = 0, episodeNumber = 100))))
        coEvery { tmdb.getTvExternalIds(1437, "fixture") } returns Response.success(TmdbExternalIdsResponse(1437, tvdbId = 78874))
        assertNull(resolver.resolve(1, TrackingProviderId.MDBLIST, reference))
    }

    @Test fun `resume repository projection cannot transfer a remote slot to another episode`() = runTest {
        prepare()
        val progress = com.nuvio.tv.domain.model.WatchProgress(original.id, "series", "Firefly", null, null, null,
            "tt0303461:1:1", 1, 1, null, 50, 100, 1, tvdbEpisodeId = 297994)
        val result = resolver.projectProgress(1, original.id, mapOf((1 to 1) to progress))
        assertEquals(297994L, result[1 to 4]?.tvdbEpisodeId)
        assertNull(result[1 to 1])
        coEvery { overlay.apply(1, original) } returns original
        assertTrue(resolver.projectProgress(1, original.id, mapOf((1 to 1) to progress)).isEmpty())
    }
}
