package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.*
import com.nuvio.tv.data.repository.*
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

internal fun kitchenOrders(): Map<String, TvdbEpisodeOrder> {
    val json = TvdbCrossSeriesTest::class.java.getResourceAsStream("/tvdb/kitchen-orders.json")!!.bufferedReader().use { it.readText() }
    val lists = Json.parseToJsonElement(json).jsonObject.mapValues { (_, value) -> value.jsonArray.map { parseTvdbEpisode(it.jsonObject) } }
    return lists.mapValues { (type, rows) -> TvdbEpisodeOrder(type, type, rows, lists.getValue("official"), relatedOrders = lists) }
}

class TvdbCrossSeriesTest {
    private val orders = kitchenOrders()
    private val raw = recordedProvider(orders.getValue("official"), "tt0983514")
    private val alternate = tvdbEpisodeOverlay(raw, 80552, orders.getValue("alternate"))
    private val store = mockk<TvdbOrderStore> { coEvery { selected(1, any()) } returns TvdbOrderPreference(80552, "alternate") }
    private val overlay = mockk<TvdbEpisodeResolver> { coEvery { apply(1, raw) } returns alternate }
    private val source = mockk<MetaRepositoryImpl> { every { getCachedMeta("series", raw.id) } returns raw }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    private val api = mockk<TraktApi>()
    private val auth = mockk<TraktAuthService>()
    private val originalShow = TraktShowDto("Kitchen Nightmares", 2007,
        TraktIdsDto(trakt = 11242, imdb = "tt0983514", tmdb = 11294, tvdb = 80552))
    private val revivalShow = TraktShowDto("Kitchen Nightmares", 2023,
        TraktIdsDto(trakt = 210181, imdb = "tt32436778", tmdb = 235884))
    private val resolver = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, api, auth, mockk(), mockk())

    private fun seasons(year: Int): List<TraktSeasonDto> {
        val json = javaClass.getResourceAsStream("/tvdb/kitchen-trakt-catalogues.json")!!.bufferedReader().use { it.readText() }
        val show = Json.parseToJsonElement(json).jsonArray.single { it.jsonObject.getValue("year").jsonPrimitive.int == year }.jsonObject
        return show.getValue("seasons").jsonArray.map { value ->
            val season = value.jsonObject
            TraktSeasonDto(number = season.getValue("number").jsonPrimitive.int,
                episodes = season.getValue("episodes").jsonArray.map { item ->
                    val row = item.jsonObject
                    val ids = row.getValue("ids").jsonObject
                    TraktEpisodeDto(title = row["title"]?.jsonPrimitive?.contentOrNull,
                        season = row.getValue("season").jsonPrimitive.int, number = row.getValue("number").jsonPrimitive.int,
                        firstAired = row["first_aired"]?.jsonPrimitive?.contentOrNull,
                        ids = TraktIdsDto(trakt = ids["trakt"]?.jsonPrimitive?.intOrNull,
                            tvdb = ids["tvdb"]?.jsonPrimitive?.intOrNull, tmdb = ids["tmdb"]?.jsonPrimitive?.intOrNull))
                })
        }
    }

    private fun prepare() {
        coEvery { auth.executeAuthorizedRequest<List<TraktSearchResultDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSearchResultDto>>>().invoke("fixture")
        }
        coEvery { auth.executeAuthorizedRequest<List<TraktSeasonDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSeasonDto>>>().invoke("fixture")
        }
        coEvery { api.searchById("fixture", "imdb", raw.id, "show") } returns Response.success(listOf(TraktSearchResultDto(show = originalShow)))
        coEvery { api.getShowSeasons("fixture", "11242", "episodes,full") } returns Response.success(seasons(2007))
        coEvery { api.getShowSeasons("fixture", "210181", "episodes,full") } returns Response.success(seasons(2023))
        coEvery { api.searchById("fixture", "tvdb", any(), "episode", extended = "full") } answers {
            val id = thirdArg<String>().toInt()
            val episode = seasons(2023).flatMap { it.episodes.orEmpty() }.singleOrNull { it.ids?.tvdb == id }
            Response.success(episode?.let { listOf(TraktSearchResultDto(show = revivalShow, episode = it)) }.orEmpty())
        }
    }

    @Test fun `real HTTP episode lookup requests full metadata required by cross-series date verification`() = runTest {
        fun resource(name: String) = javaClass.getResourceAsStream("/tvdb/$name")!!.bufferedReader().use { it.readText() }
        val shows = Json.parseToJsonElement(resource("kitchen-trakt-catalogues.json")).jsonArray
        val original = shows.single { it.jsonObject.getValue("year").jsonPrimitive.int == 2007 }.jsonObject
        val revival = shows.single { it.jsonObject.getValue("year").jsonPrimitive.int == 2023 }.jsonObject
        val links = Json.parseToJsonElement(resource("kitchen-trakt-episode-links.json")).jsonArray
            .single { it.jsonObject.getValue("tvdbEpisodeId").jsonPrimitive.long == 9893344L }.jsonObject.getValue("hits").toString()
        val minimal = resource("kitchen-trakt-minimal-episode-link.json")
        val observed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                observed += request.path.orEmpty()
                val body = when {
                    request.path.orEmpty().startsWith("/search/imdb/") -> "[{\"show\":$original}]"
                    request.path.orEmpty().startsWith("/shows/11242/") -> original.getValue("seasons").toString()
                    request.path.orEmpty().startsWith("/shows/210181/") -> revival.getValue("seasons").toString()
                    request.path.orEmpty().startsWith("/search/tvdb/9893344") ->
                        if (request.requestUrl?.queryParameter("extended") == "full") links else minimal
                    else -> "[]"
                }
                return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        try {
            val api = retrofit2.Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(
                    com.squareup.moshi.Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()))
                .build().create(TraktApi::class.java)
            val auth = mockk<TraktAuthService>()
            coEvery { auth.executeAuthorizedRequest<List<TraktSearchResultDto>>(any()) } coAnswers {
                firstArg<suspend (String) -> Response<List<TraktSearchResultDto>>>().invoke("fixture")
            }
            coEvery { auth.executeAuthorizedRequest<List<TraktSeasonDto>>(any()) } coAnswers {
                firstArg<suspend (String) -> Response<List<TraktSeasonDto>>>().invoke("fixture")
            }
            val isolated = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, api, auth, mockk(), mockk())
            val target = isolated.resolve(1, TrackingProviderId.TRAKT,
                buildTrackingMediaReference("series", raw.id, "tt0983514:8:1", seasonNumber = 8, episodeNumber = 1))
            assertEquals(210181L, target?.ids?.trakt)
            assertTrue(observed.any { it.startsWith("/search/tvdb/9893344?") && "extended=full" in it })
        } finally { server.shutdown() }
    }

    @Test fun `revival searches selected S8E1 but Trakt receives independently verified revival S1E1 and parent`() = runTest {
        prepare()
        val video = alternate.videos.single { it.episodeIdentity!!.tvdbEpisodeId == 9893344L }
        assertEquals("tt0983514:8:1", video.id)
        val request = buildTrackingMediaReference("series", raw.id, video.id,
            seasonNumber = 8, episodeNumber = 1, episodeTitle = video.title)
        val destination = resolver.resolve(1, TrackingProviderId.TRAKT, request)
        assertNotNull("A verified revival episode must not be rejected solely because its parent has no TVDB series ID", destination)
        assertEquals(210181L, destination!!.ids.trakt)
        assertEquals("tt32436778", destination.ids.imdb)
        assertNull(destination.ids.tvdb)
        assertEquals(1 to 1, destination.episode!!.let { it.season to it.number })
        assertEquals(2023, destination.year)
        assertEquals("tt32436778", destination.catalog!!.contentId)
        assertEquals("tt32436778:1:1", destination.catalog!!.videoId)
        assertEquals("tt0983514:8:1", request.catalog!!.videoId)
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val mapper = mockk<TraktEpisodeMappingService>()
        TraktTrackingScrobbler(service, mapper).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(destination, 50.0))
        coVerify(exactly = 1) { service.scrobbleStart(match { item -> item is TraktScrobbleItem.Episode &&
            item.showIds.trakt == 210181 && item.showIds.imdb == "tt32436778" && item.showIds.tvdb == null && item.season == 1 && item.number == 1 }, 50f) }
        confirmVerified(mapper)
        val history = mockk<TraktProgressService>(relaxed = true)
        val writer = TraktTrackingHistoryWriter(history, profiles)
        writer.addToHistory(1, listOf(TrackingHistoryItem(destination, 1)))
        coVerify(exactly = 1) { history.markAsWatched(match { it.contentId == "tt32436778" && it.season == 1 &&
            it.episode == 1 && it.traktEpisodeId == 10918421 }, any(), any()) }
        writer.removeFromHistory(1, listOf(destination))
        coVerify(exactly = 1) { history.removeFromHistory("tt32436778", "tt32436778:1:1", 1, 1, mappingResolved = true) }
        coVerify(exactly = 0) { api.addHistory(any(), any()) }
    }

    @Test fun `both real TVDB orders resolve verified revival episodes and withhold the remaining unverified title typo`() = runTest {
        prepare()
        for ((type, order) in orders) {
            val meta = tvdbEpisodeOverlay(raw, 80552, order)
            coEvery { overlay.apply(1, raw) } returns meta
            val revival = meta.videos.filter { it.released.orEmpty() >= "2023-01-01" }
            assertEquals(33, revival.size)
            for (video in revival) {
                val request = buildTrackingMediaReference("series", raw.id, video.id,
                    seasonNumber = video.season, episodeNumber = video.episode, episodeTitle = video.title)
                assertEquals("${raw.id}:${video.displaySeason}:${video.displayEpisode}", video.id)
                val target = resolver.resolve(1, TrackingProviderId.TRAKT, request)
                if (video.episodeIdentity!!.tvdbEpisodeId == 11961406L) {
                    // Public Trakt currently has no TVDB ID and spells Pizzeria as Pizzaria.
                    // Date alone is insufficient: preserve this explicit unresolved exception.
                    assertNull(target)
                    continue
                }
                assertNotNull("$type ${video.title}", target)
                assertEquals(210181L, target!!.ids.trakt)
                assertEquals(video.displaySeason!! - if (type == "official") 6 else 7, target.episode!!.season)
                assertNull(target.ids.tvdb)
            }
        }
    }

    private fun progress(parent: String, episodeId: Int, complete: Boolean) = WatchProgress(parent, "series", "Kitchen Nightmares",
        null, null, null, "$parent:1:1", 1, 1, null, if (complete) 100 else 50, 100, 1,
        source = WatchProgress.SOURCE_TRAKT_HISTORY, traktEpisodeId = episodeId)

    @Test fun `native original and revival S1E1 remain distinct for watched progress resume and Continue Watching`() = runTest {
        prepare()
        val original = progress("tt0983514", 457325, true)
        val revival = progress("tt32436778", 10918421, false)
        val result = resolver.projectProgress(1, raw.id, emptyMap(), listOf(original, revival))
        assertTrue(result.getValue(1 to 1).isCompleted())
        assertFalse(result.getValue(8 to 1).isCompleted())
        assertEquals(50L, result.getValue(8 to 1).resolveResumePosition(100))
        assertEquals(9893344L, result.getValue(8 to 1).tvdbEpisodeId)
        val card = com.nuvio.tv.ui.screens.home.ContinueWatchingItem.InProgress(result.getValue(8 to 1))
        val projected = com.nuvio.tv.ui.screens.home.projectTvdbContinueWatching(card, alternate.videos)
            as com.nuvio.tv.ui.screens.home.ContinueWatchingItem.InProgress
        assertEquals("tt0983514:8:1", projected.progress.videoId)
        assertEquals(listOf(9893344L), projected.progress.tvdbContext!!.episodeIds)
        assertEquals("tt32436778:1:1", revival.videoId)
        assertNull(resolver.projectProgress(1, raw.id, emptyMap(), listOf(original))[8 to 1])
        val unrelated = revival.copy(contentId = "tt9999999", traktShowId = 99999)
        assertNull(resolver.projectProgress(1, raw.id, emptyMap(), listOf(unrelated))[8 to 1])
        coEvery { store.selected(1, any()) } returns null
        val native = mapOf((1 to 1) to revival)
        assertSame(native, resolver.projectProgress(1, revival.contentId, native))
    }

    @Test fun `selected progress loads existing native snapshots for both verified parents without collapsing S1E1`() = runTest {
        prepare()
        val original = progress("tt0983514", 457325, true)
        val revival = progress("tt32436778", 10918421, false)
        val native = mockk<TraktProgressService> {
            every { observeCanonicalEpisodeProgress("tt0983514") } returns flowOf(mapOf((1 to 1) to original))
            every { observeCanonicalEpisodeProgress("tt32436778") } returns flowOf(mapOf((1 to 1) to revival))
        }
        val isolated = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, api, auth, mockk(), mockk(),
            traktProgress = dagger.Lazy { native })
        val records = isolated.canonicalTraktRecords(1, raw.id)!!.first()
        assertEquals(setOf("tt0983514", "tt32436778"), records.map { it.contentId }.toSet())
        val result = isolated.projectProgress(1, raw.id, emptyMap(), records)
        assertTrue(result.getValue(1 to 1).isCompleted())
        assertEquals(50L, result.getValue(8 to 1).position)
    }

    @Test fun `native revival provider references and restrictions do not collide with original S1E1`() {
        val revival = tvdbFixtureMeta(listOf(Video("tt32436778:1:1", "Bel Aire", "2023-09-25", "revival-image",
            season = 1, episode = 1, overview = null, available = false))).copy(id = "tt32436778")
        val old = mapOf(335862L to TvdbSourceReference(335862, "tt0983514:1:1", EpisodeCoordinates(1, 1)),
            9893344L to TvdbSourceReference(9893344, "tt0983514:7:1", EpisodeCoordinates(7, 1)))
        val result = tvdbEpisodeOverlay(revival, 80552, orders.getValue("alternate"), old)
        val video = result.videos.single { it.episodeIdentity!!.tvdbEpisodeId == 9893344L }
        assertEquals("tt32436778:8:1", video.id)
        assertEquals("tt32436778:1:1", video.episodeIdentity!!.providerVideoId)
        assertEquals(false, video.available)
        assertEquals("revival-image", video.thumbnail)
    }

    @Test fun `a foreign parent with the same episode ID but incompatible air date is rejected`() = runTest {
        prepare()
        val row = seasons(2023).flatMap { it.episodes.orEmpty() }.single { it.ids?.tvdb == 9893344 }
        coEvery { api.searchById("fixture", "tvdb", "9893344", "episode", extended = "full") } returns Response.success(listOf(
            TraktSearchResultDto(show = revivalShow, episode = row.copy(firstAired = "2010-01-01T00:00:00Z"))))
        val video = alternate.videos.single { it.episodeIdentity!!.tvdbEpisodeId == 9893344L }
        assertNull(resolver.resolve(1, TrackingProviderId.TRAKT, buildTrackingMediaReference("series", raw.id, video.id,
            seasonNumber = 8, episodeNumber = 1)))
    }

    @Test fun `MDBList cross-series mapping requires reverse episode ID proof and keeps its own TMDB parent`() = runTest {
        val tmdb = mockk<com.nuvio.tv.data.remote.api.TmdbApi>()
        val tmdbService = mockk<com.nuvio.tv.core.tmdb.TmdbService> { every { apiKey() } returns "fixture" }
        coEvery { tmdb.findByExternalId("9893344", "fixture", "tvdb_id") } returns Response.success(
            com.nuvio.tv.data.remote.api.TmdbFindResponse(tvEpisodeResults = listOf(
                com.nuvio.tv.data.remote.api.TmdbFindResult(4763028, name = "Bel Aire", showId = 235884,
                    seasonNumber = 1, episodeNumber = 1, airDate = "2023-09-25"))))
        coEvery { tmdb.getTvExternalIds(235884, "fixture") } returns Response.success(
            com.nuvio.tv.data.remote.api.TmdbExternalIdsResponse(235884, imdbId = "tt32436778"))
        coEvery { tmdb.getTvEpisodeExternalIds(235884, 1, 1, "fixture") } returns Response.success(
            com.nuvio.tv.data.remote.api.TmdbExternalIdsResponse(4763028, tvdbId = 9893344))
        val isolated = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, api, auth, tmdb, tmdbService)
        val reference = buildTrackingMediaReference("series", raw.id, "tt0983514:8:1", seasonNumber = 8, episodeNumber = 1)
        val target = isolated.resolve(1, TrackingProviderId.MDBLIST, reference)!!
        assertEquals(235884L, target.ids.tmdb)
        assertEquals("tt32436778", target.ids.imdb)
        assertNull(target.ids.tvdb)
        assertEquals(1 to 1, target.episode!!.let { it.season to it.number })
        coEvery { tmdb.getTvEpisodeExternalIds(235884, 1, 1, "fixture") } returns Response.success(
            com.nuvio.tv.data.remote.api.TmdbExternalIdsResponse(4763028, tvdbId = 335862))
        assertNull(isolated.resolve(1, TrackingProviderId.MDBLIST, reference))
        confirmVerified(api, auth)
    }

    @Test fun `Simkl uses independently synced episode and TVDB coordinate proof without Trakt assistance`() = runTest {
        val snapshot = com.nuvio.tv.data.simkl.SimklSyncSnapshot(entries = listOf(
            com.nuvio.tv.data.simkl.SimklLibraryEntry(show = com.nuvio.tv.data.simkl.SimklMedia(
                ids = mapOf("imdb" to JsonPrimitive("tt32436778"), "simkl" to JsonPrimitive(222))),
                seasons = listOf(com.nuvio.tv.data.simkl.SimklSeason(1, listOf(
                    com.nuvio.tv.data.simkl.SimklEpisode(1, tvdb = com.nuvio.tv.data.simkl.SimklEpisodeMapping(7, 1),
                        ids = com.nuvio.tv.data.simkl.SimklEpisodeIds(9893344))))))))
        val simkl = mockk<com.nuvio.tv.data.simkl.SimklSyncRepository> {
            coEvery { ensureLoaded() } returns Unit
            every { state } returns MutableStateFlow(com.nuvio.tv.data.simkl.SimklSyncState(snapshot = snapshot))
        }
        val isolated = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, api, auth, mockk(), mockk(), simkl = dagger.Lazy { simkl })
        val target = isolated.resolve(1, TrackingProviderId.SIMKL,
            buildTrackingMediaReference("series", raw.id, "tt0983514:8:1", seasonNumber = 8, episodeNumber = 1))!!
        assertEquals("tt32436778", target.ids.imdb)
        assertEquals(222L, target.ids.simkl)
        assertNull(target.ids.tvdb)
        assertEquals(1 to 1, target.episode!!.let { it.season to it.number })
        confirmVerified(api, auth)
    }
}
