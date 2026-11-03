package com.nuvio.tv.domain.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TvMixTest {
    private fun episode(id: String, n: Int) = Video("$id:1:$n", "Episode $n", "2020-01-01", null,
        season = 1, episode = n, overview = null)
    private fun meta(id: String) = Meta(id = id, type = ContentType.SERIES, name = id,
        poster = null, posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), runtime = null, director = emptyList(),
        cast = emptyList(), videos = emptyList(), country = null, awards = null, language = null, links = emptyList())

    @Test fun `show probability is independent of episode count and only one show is fetched`() = runTest {
        val shows = listOf(TvMixShow("short", "Short"), TvMixShow("long", "Long"))
        val random = Random(742)
        var short = 0
        var calls = 0
        val metadata = shows.associate { it.id to meta(it.id) }
        val episodes = mapOf("short" to listOf(episode("short", 1)), "long" to (1..300).map { episode("long", it) })
        repeat(2000) {
            val result = selectTvMixEpisode(shows, null, random) { show ->
                calls++; metadata.getValue(show.id) to episodes.getValue(show.id)
            }!!
            if (result.meta.id == "short") short++
        }
        assertTrue("Both shows should receive roughly half the selections: $short", short in 900..1100)
        assertEquals(2000, calls)
    }

    @Test fun `remix and continuation avoid the same episode including across single episode shows`() = runTest {
        val shows = listOf(TvMixShow("a", "A"), TvMixShow("b", "B"))
        repeat(30) { seed ->
            val result = selectTvMixEpisode(shows, "a" to "a:1:1", Random(seed)) {
                meta(it.id) to listOf(episode(it.id, 1))
            }!!
            assertEquals("b", result.meta.id)
        }
        val sameShow = selectTvMixEpisode(shows.take(1), "a" to "a:1:1") {
            meta("a") to listOf(episode("a", 1), episode("a", 2))
        }!!
        assertEquals(2, sameShow.video.episode)
    }

    @Test fun `single episode mix can repeat when no alternative exists`() = runTest {
        val ep = episode("a", 1)
        assertEquals(ep, selectTvMixEpisode(listOf(TvMixShow("a", "A")), "a" to ep.id) {
            meta("a") to listOf(ep)
        }?.video)
    }

    @Test fun `future unavailable and unresolved episodes cannot be selected or repeated`() = runTest {
        val unavailable = episode("a", 1).copy(available = false)
        val future = episode("a", 2).copy(released = "2999-01-01")
        val unresolved = episode("a", 3).copy(episodeIdentity = EpisodeIdentity(3, 1, "dvd",
            EpisodeCoordinates(1, 3), null, usesSelectedOrderForStreams = true))
        assertNull(selectTvMixEpisode(listOf(TvMixShow("a", "A")), "a" to unavailable.id) {
            meta("a") to listOf(unavailable, future, unresolved)
        })
    }

    @Test fun `unavailable shows are tried once and an empty mix terminates`() = runTest {
        val shows = (1..5).map { TvMixShow("$it", "Show $it") }
        val calls = mutableListOf<String>()
        assertNull(selectTvMixEpisode(shows, null) { calls += it.id; null })
        assertEquals(shows.map { it.id }.toSet(), calls.toSet())
        assertEquals(5, calls.size)
        assertNull(selectTvMixEpisode(emptyList(), null) { error("Must not fetch") })
    }

    @Test fun `duplicate aliases are not added and watched episodes are allowed by default`() {
        val first = TvMixShow("tmdb:1", "Show", imdbId = "tt123")
        val mix = TvMix("mix", "Sitcoms").adding(first).adding(TvMixShow("tt123", "Same show"))
        assertEquals(listOf(first), mix.shows)
        assertFalse(mix.unwatchedOnly)
        assertEquals("tvmix:mix", mix.preview().id)
        assertEquals("tvmix", mix.preview().apiType)
        assertEquals("tvmix-art:", mix.preview().poster)
    }
}
