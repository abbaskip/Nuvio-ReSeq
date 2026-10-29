package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.*
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TvdbTrackingCacheTest {
    @Test fun `reentry and display order changes reuse verified catalogue without refetching history or metadata`() = runTest {
        val api = mockk<TraktApi>()
        val auth = mockk<TraktAuthService>()
        coEvery { auth.executeAuthorizedRequest<List<TraktSearchResultDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSearchResultDto>>>().invoke("fixture")
        }
        coEvery { auth.executeAuthorizedRequest<List<TraktSeasonDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSeasonDto>>>().invoke("fixture")
        }
        val show = TraktShowDto("Example", 2000, TraktIdsDto(trakt = 123, imdb = "tt123", tvdb = 456))
        coEvery { api.searchById("fixture", "imdb", "tt123", "show") } returns Response.success(listOf(TraktSearchResultDto(show = show)))
        coEvery { api.getShowSeasons("fixture", "123", "episodes,full") } returns Response.success(listOf(
            TraktSeasonDto(number = 1, episodes = listOf(TraktEpisodeDto(title = "Pilot", season = 1, number = 1,
                firstAired = "2000-01-01", ids = TraktIdsDto(tvdb = 789, trakt = 987))))))
        val raw = tvdbFixtureMeta(emptyList()).copy(id = "tt123", imdbId = "tt123")
        val record = EpisodeContentIdentity(789, "Pilot", "2000-01-01", EpisodeCoordinates(1, 1), listOf(789))
        val meta = raw.copy(episodeOrder = EpisodeOrderStatus(456, "alternate", "alternate", content = listOf(record)))
        val cache = TvdbTraktCatalogue(api, auth)
        val first = cache.targets(1, meta)
        assertEquals(setOf(789L), first.single().contentIds)
        repeat(3) { assertEquals(first, cache.targets(1, meta.copy())) }
        assertEquals(first, cache.targets(1, meta.copy(episodeOrder = meta.episodeOrder!!.copy(selectedType = "alttwo", effectiveType = "alttwo"))))
        // A changed identity catalogue must invalidate correspondence even when its numeric slots did not change.
        val changed = meta.copy(episodeOrder = meta.episodeOrder!!.copy(content = listOf(record.copy(title = "Unrelated", aired = "2010-01-01"))))
        assertTrue(cache.targets(1, changed, emptySet()).isEmpty())
        coVerify(exactly = 1) { api.searchById("fixture", "imdb", "tt123", "show") }
        coVerify(exactly = 1) { api.getShowSeasons("fixture", "123", "episodes,full") }
        confirmVerified(api)
    }
    @Test fun `catalogue capacity evicts cold entries without clearing recently used correspondence`() = runTest {
        val api = mockk<TraktApi>()
        val auth = mockk<TraktAuthService>()
        coEvery { auth.executeAuthorizedRequest<List<TraktSearchResultDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSearchResultDto>>>().invoke("fixture")
        }
        coEvery { auth.executeAuthorizedRequest<List<TraktSeasonDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSeasonDto>>>().invoke("fixture")
        }
        coEvery { api.searchById("fixture", "imdb", any(), "show") } coAnswers {
            val imdb = thirdArg<String>()
            Response.success(listOf(TraktSearchResultDto(show = TraktShowDto("Example", 2000,
                TraktIdsDto(trakt = imdb.removePrefix("tt").toInt(), imdb = imdb, tvdb = 456)))))
        }
        coEvery { api.getShowSeasons("fixture", any(), "episodes,full") } returns Response.success(listOf(
            TraktSeasonDto(number = 1, episodes = listOf(TraktEpisodeDto(title = "Pilot", season = 1, number = 1,
                firstAired = "2000-01-01", ids = TraktIdsDto(tvdb = 789, trakt = 987))))))
        fun meta(id: Int) = tvdbFixtureMeta(emptyList()).copy(id = "tt$id", imdbId = "tt$id",
            episodeOrder = EpisodeOrderStatus(456, "alternate", "alternate", content = listOf(
                EpisodeContentIdentity(789, "Pilot", "2000-01-01", EpisodeCoordinates(1, 1), listOf(789)))))
        val cache = TvdbTraktCatalogue(api, auth)
        val hot = meta(123)
        val first = cache.targets(1, hot)
        for (id in 1000..1065) {
            cache.targets(1, meta(id))
            assertEquals(first, cache.targets(1, hot))
        }
        coVerify(exactly = 1) { api.searchById("fixture", "imdb", "tt123", "show") }
        coVerify(exactly = 1) { api.getShowSeasons("fixture", "123", "episodes,full") }
        cache.targets(1, meta(1000))
        coVerify(exactly = 2) { api.getShowSeasons("fixture", "1000", "episodes,full") }
    }

}
