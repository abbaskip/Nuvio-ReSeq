package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.DiscoveryQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

class DiscoveryRepositoryTest {
    private val api = mockk<TmdbApi>()
    private val movie = TmdbDiscoverResult(id = 99, title = "A Film", releaseDate = "2020-02-04", voteAverage = 7.2, voteCount = 1500)

    @Test
    fun `real Retrofit request encodes combined filters and parses TMDB response`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"page":1,"total_pages":1,"total_results":1,"results":[{"id":99,"title":"A Film","vote_average":7.2,"vote_count":1500}]}"""))
            val retrofitApi = Retrofit.Builder().baseUrl(server.url("/3/"))
                .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build())).build().create(TmdbApi::class.java)
            val result = DiscoveryRepository(retrofitApi, "test-key").discover(
                DiscoveryQuery(genres = setOf(80, 53), excludedGenres = setOf(27), minimumVotes = "1000", sort = "release_date.asc"), "en", 1)
            val url = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
            assertEquals("/3/discover/movie", url.encodedPath)
            assertEquals("53|80", url.queryParameter("with_genres"))
            assertEquals("27", url.queryParameter("without_genres"))
            assertEquals("1000", url.queryParameter("vote_count.gte"))
            assertEquals("primary_release_date.asc", url.queryParameter("sort_by"))
            assertEquals("tmdb:99", result.items.single().id)
        }
    }

    @Test
    fun `cached results expire after ten minutes`() = runTest {
        var now = 0L
        coEvery { api.advancedDiscover(any(), any(), any(), any(), any()) } returns Response.success(TmdbDiscoverResponse(results = listOf(movie)))
        val repository = DiscoveryRepository(api, "test-key") { now }
        repository.discover(DiscoveryQuery(), "en", 1)
        now = 9L * 60 * 1_000_000_000
        repository.discover(DiscoveryQuery(), "en", 1)
        coVerify(exactly = 1) { api.advancedDiscover(any(), any(), any(), any(), any()) }
        now = 10L * 60 * 1_000_000_000
        repository.discover(DiscoveryQuery(), "en", 1)
        coVerify(exactly = 2) { api.advancedDiscover(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing provider configuration fails before any network call`() = runTest {
        val failed = runCatching { DiscoveryRepository(api, "").discover(DiscoveryQuery(), "en", 1) }.exceptionOrNull()
        assertEquals(DiscoveryFailure.CONFIGURATION, (failed as DiscoveryException).failure)
        coVerify(exactly = 0) { api.advancedDiscover(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid provider credentials are reported as configuration failure`() = runTest {
        coEvery { api.advancedDiscover(any(), any(), any(), any(), any()) } returns Response.error(401, "unauthorized".toResponseBody())
        val failed = runCatching { DiscoveryRepository(api, "test-key").discover(DiscoveryQuery(), "en", 1) }.exceptionOrNull()
        assertEquals(DiscoveryFailure.CONFIGURATION, (failed as DiscoveryException).failure)
    }

    @Test
    fun `page preserves global provider order and does not hydrate individual titles`() = runTest {
        coEvery { api.advancedDiscover(any(), any(), any(), any(), any()) } returns Response.success(
            TmdbDiscoverResponse(results = listOf(movie, movie.copy(id = 2, title = "Older", releaseDate = "1990-01-01")), totalPages = 2, totalResults = 25))
        val repository = DiscoveryRepository(api, "test-key")
        val result = repository.discover(DiscoveryQuery(sort = "vote_average.desc"), "en", 1)
        assertEquals(listOf("tmdb:99", "tmdb:2"), result.items.map { it.id })
        assertEquals("2020-02-04", result.items.first().released)
        assertEquals(1500, result.items.first().voteCount)
        assertTrue(result.hasMore)
        assertEquals(25, result.totalResults)
        coVerify(exactly = 1) { api.advancedDiscover("movie", any(), "en", 1, match { it["sort_by"] == "vote_average.desc" }) }
        coVerify(exactly = 0) { api.getMovieDetails(any(), any(), any()) }
        coVerify(exactly = 0) { api.getMovieExternalIds(any(), any()) }
    }

    @Test
    fun `repeated queries share cached pages including local watched toggle`() = runTest {
        coEvery { api.advancedDiscover(any(), any(), any(), any(), any()) } returns Response.success(TmdbDiscoverResponse(results = listOf(movie)))
        val repository = DiscoveryRepository(api, "test-key")
        repository.discover(DiscoveryQuery(), "en", 1)
        repository.discover(DiscoveryQuery(hideWatched = true), "en", 1)
        coVerify(exactly = 1) { api.advancedDiscover(any(), any(), any(), any(), any()) }
        repository.discover(DiscoveryQuery(), "fr", 1)
        repository.discover(DiscoveryQuery(television = true), "en", 1)
        repository.discover(DiscoveryQuery(), "en", 2)
        repository.discover(DiscoveryQuery(minimumVotes = "1000"), "en", 1)
        coVerify(exactly = 5) { api.advancedDiscover(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rate limited failures are explicit and are not cached`() = runTest {
        coEvery { api.advancedDiscover(any(), any(), any(), any(), any()) } returnsMany listOf(
            Response.error(429, "limited".toResponseBody()), Response.success(TmdbDiscoverResponse(results = listOf(movie))))
        val repository = DiscoveryRepository(api, "test-key")
        val failed = runCatching { repository.discover(DiscoveryQuery(), "en", 1) }.exceptionOrNull()
        assertEquals(DiscoveryFailure.RATE_LIMIT, (failed as DiscoveryException).failure)
        assertEquals(1, repository.discover(DiscoveryQuery(), "en", 1).items.size)
        coVerify(exactly = 2) { api.advancedDiscover(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pagination stops at TMDB page limit and rejects invalid filters before I O`() = runTest {
        coEvery { api.advancedDiscover(any(), any(), any(), any(), any()) } returns Response.success(TmdbDiscoverResponse(results = listOf(movie), totalPages = 900))
        val repository = DiscoveryRepository(api, "test-key")
        assertFalse(repository.discover(DiscoveryQuery(), "en", 500).hasMore)
        assertTrue(runCatching { repository.discover(DiscoveryQuery(), "en", 501) }.isFailure)
        assertTrue(runCatching { repository.discover(DiscoveryQuery(minimumVotes = "bad"), "en", 1) }.isFailure)
        coVerify(exactly = 1) { api.advancedDiscover(any(), any(), any(), any(), any()) }
    }
}
