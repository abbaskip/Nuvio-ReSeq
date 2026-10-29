package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.remote.api.*
import com.nuvio.tv.data.remote.dto.trakt.*
import com.nuvio.tv.data.repository.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.detail.MetaDetailsUiState
import com.nuvio.tv.ui.screens.detail.withTvdbDisplay
import com.nuvio.tv.ui.screens.player.PlayerNextEpisodeRules
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

internal fun officeOrderFixtures(): Map<String, TvdbEpisodeOrder> {
    val text = TvdbCombinedEpisodeTest::class.java.getResourceAsStream("/tvdb/office-all-orders.json")!!.bufferedReader().use { it.readText() }
    val rows = Json.parseToJsonElement(text).jsonObject.mapValues { (_, value) -> value.jsonArray.map { parseTvdbEpisode(it.jsonObject) } }
    return rows.mapValues { (type, episodes) -> TvdbEpisodeOrder(type, type, episodes, rows.getValue("official"), relatedOrders = rows) }
}

class TvdbCombinedEpisodeTest {
    private val orders = officeOrderFixtures()
    private val original = recordedProvider(orders.getValue("official"), "tt0386676")
    private val alternate = tvdbEpisodeOverlay(original, 73244, orders.getValue("alternate"))

    @Test fun `all seven complete Office orders retain unique display keys and selected stream requests`() {
        assertEquals(setOf("official", "dvd", "absolute", "alternate", "regional", "altdvd", "alttwo"), orders.keys)
        val standard = original.videos.associateBy { it.id }
        for ((type, order) in orders) {
            val result = tvdbEpisodeOverlay(original, 73244, order)
            assertEquals(order.episodes.size, result.videos.size)
            assertEquals(result.videos.size, result.videos.map { it.episodeListKey }.distinct().size)
            val mapped = result.videos.filter { it.hasResolvedSource }
            assertTrue("No verified sources for $type", mapped.isNotEmpty())
            mapped.forEach { video ->
                assertEquals(video.displaySeason to video.displayEpisode, video.sourceSeason to video.sourceEpisode)
                assertEquals("tt0386676:${video.displaySeason}:${video.displayEpisode}", video.id)
                video.episodeIdentity!!.providerSources.forEach { ref -> assertTrue(ref.videoId in standard) }
            }
            println("Office order $type: ${mapped.size}/${result.videos.size} mapped to verified standard identities")
        }
    }

    @Test fun `both Weight Loss parts retain distinct search slots while native combined history marks both watched`() {
        val parts = alternate.videos.filter { it.title.startsWith("Weight Loss") }
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.id == "tt0386676:5:${it.displayEpisode}" && it.episodeIdentity!!.trackingEpisodeIds.size == 1 })
        val progress = WatchProgress(original.id, "series", "The Office", null, null, null,
            "tt0386676:5:1", 5, 1, "Weight Loss", 100, 100, 1, tvdbEpisodeId = 389875)
        val reconciled = reconcileTvdbProgress(alternate, mapOf((5 to 1) to progress))
        assertEquals(setOf(5 to 1, 5 to 2), reconciled.keys)
        val display = MetaDetailsUiState(meta = alternate, episodesForSeason = alternate.videos,
            watchedEpisodes = reconciled.keys, episodeProgressMap = reconciled).withTvdbDisplay()
        assertTrue(display.watchedEpisodes.containsAll(setOf(5 to 1, 5 to 2)))
        assertEquals("tt0386676:5:2", PlayerNextEpisodeRules.resolveNextEpisode(alternate.watchableEpisodes(), 5, 1)?.id)
        assertEquals(2, parts.distinctEpisodeSources().size)
        val stored = mapOf(7931974L to TvdbSourceReference(7931974, "tt0386676:5:1", EpisodeCoordinates(5, 1), "weight loss", 389875),
            7931977L to TvdbSourceReference(7931977, "tt0386676:5:1", EpisodeCoordinates(5, 1), "weight loss", 389875))
        assertTrue(tvdbEpisodeOverlay(original, 73244, orders.getValue("alternate"), stored).videos
            .filter { it.title.startsWith("Weight Loss") }.all { it.hasResolvedSource })
    }

    @Test fun `Extended Cut display ID resolves to the underlying aired episode without changing request coordinates`() {
        val extended = tvdbEpisodeOverlay(original, 73244, orders.getValue("alttwo"))
        val pilot = extended.videos.single { it.title == "Pilot (Extended Cut)" }
        val standardPilot = orders.getValue("official").episodes.single { it.title == "Pilot" }
        assertTrue(pilot.hasResolvedSource)
        assertEquals("tt0386676:1:1", pilot.id)
        assertEquals(10718410L, pilot.episodeIdentity!!.tvdbEpisodeId)
        assertEquals(standardPilot.id, pilot.episodeIdentity!!.trackingEpisodeId)
        assertEquals(EpisodeCoordinates(1, 1), pilot.episodeIdentity!!.aired)
    }

    @Test fun `Extended Cut articles and equivalent Part syntax resolve but combined ranges are not guessed`() {
        val links = tvdbEpisodeCorrespondence(orders.getValue("alttwo"))
        assertEquals(306095L, links.getValue(10718425).id) // Fire → The Fire
        assertEquals(335408L, links.getValue(10718498).id) // Part 1 → (1)
        assertEquals(4075826L, links.getValue(10718499).id)
        assertEquals(361116L, links.getValue(10718512).id) // Chair Model → The Chair Model
        assertEquals(1602631L, links.getValue(10719651).id)
        assertEquals(191, links.size)
        assertNull(links[10718533]) // Lecture Circuit spans two standard records.
        assertNull(links[10719843]) // Classy Christmas
        assertNull(links[10719873]) // Search Committee
    }

    @Test fun `incomplete part groups and ambiguous title anchors remain unresolved while known IDs win`() {
        val order = orders.getValue("alternate")
        val partial = order.copy(episodes = order.episodes.filterNot { it.id == 7931977L })
        assertNull(tvdbEpisodeCorrespondence(partial)[7931974])
        val duplicated = order.copy(officialEpisodes = order.officialEpisodes + order.officialEpisodes.single { it.id == 389875L }.copy(id = 90000001))
        assertNull(tvdbEpisodeCorrespondence(duplicated)[7931974])
        val known = order.officialEpisodes.single { it.id == 390588L }
        val renamed = order.copy(episodes = listOf(known.copy(title = "Weight Loss (Extended Cut)")))
        assertEquals(390588L, tvdbEpisodeCorrespondence(renamed).getValue(390588).id)
    }

    @Test fun `split display entries resolve to real Trakt Simkl and MDBList identities without independent part writes`() = runTest {
        val store = mockk<TvdbOrderStore> { coEvery { selected(1, any()) } returns TvdbOrderPreference(73244, "alternate") }
        val overlay = mockk<TvdbEpisodeResolver> { coEvery { apply(1, original) } returns alternate }
        val source = mockk<MetaRepositoryImpl> { every { getCachedMeta("series", original.id) } returns original }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val api = mockk<TraktApi>()
        val auth = mockk<TraktAuthService>()
        coEvery { auth.executeAuthorizedRequest<List<TraktSearchResultDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSearchResultDto>>>().invoke("fixture")
        }
        // Confirmed using Trakt's public read-only metadata search, not user history.
        coEvery { api.searchById("fixture", "tvdb", "389875", "episode") } returns Response.success(listOf(
            TraktSearchResultDto(show = TraktShowDto(ids = TraktIdsDto(tvdb = 73244)),
                episode = TraktEpisodeDto(title = "Weight Loss", season = 5, number = 1,
                ids = TraktIdsDto(tvdb = 389875, trakt = 182087)))))
        val tmdb = mockk<TmdbApi>()
        val tmdbService = mockk<TmdbService> { every { apiKey() } returns "fixture" }
        coEvery { tmdb.findByExternalId("389875", "fixture", "tvdb_id") } returns Response.success(
            TmdbFindResponse(tvEpisodeResults = listOf(TmdbFindResult(44, name = "Weight Loss", showId = 2316, seasonNumber = 5, episodeNumber = 1))))
        coEvery { tmdb.getTvExternalIds(2316, "fixture") } returns Response.success(TmdbExternalIdsResponse(2316, tvdbId = 73244))
        coEvery { api.searchById("fixture", "imdb", "tt0386676", "show") } returns Response.success(listOf(
            TraktSearchResultDto(show = TraktShowDto(ids = TraktIdsDto(tvdb = 73244, trakt = 100)))))
        coEvery { auth.executeAuthorizedRequest<List<TraktSeasonDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSeasonDto>>>().invoke("fixture")
        }
        coEvery { api.getShowSeasons("fixture", "100", "episodes,full") } returns Response.success(listOf(
            TraktSeasonDto(number = 5, episodes = listOf(TraktEpisodeDto(title = "Weight Loss", season = 5, number = 1,
                ids = TraktIdsDto(tvdb = 389875, trakt = 182087))))))
        val resolver = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, api, auth, tmdb, tmdbService,
            simkl = dagger.Lazy { fixtureSimkl(73244, 389875L to EpisodeCoordinates(5, 1)) })
        for (part in alternate.videos.filter { it.title.startsWith("Weight Loss") }) {
            val reference = buildTrackingMediaReference("series", original.id, part.id, "The Office",
                seasonNumber = part.sourceSeason, episodeNumber = part.sourceEpisode, episodeTitle = part.title)
            for (provider in TrackingProviderId.entries) {
                assertTrue(resolver.resolveAll(1, provider, reference, completed = false).isNullOrEmpty())
                val combined = reference.copy(catalog = reference.catalog?.copy(tvdbEpisodeIds = listOf(7931974L, 7931977L)))
                assertNull(resolver.resolve(1, provider, combined))

            }
        }
        coVerify(exactly = 1) { api.getShowSeasons(any(), "100", "episodes,full") }
        coVerify(exactly = 0) { api.addHistory(any(), any()) }
    }
}
