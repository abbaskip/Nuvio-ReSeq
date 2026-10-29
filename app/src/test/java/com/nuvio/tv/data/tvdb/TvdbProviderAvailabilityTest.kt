package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class TvdbProviderAvailabilityTest {
    @Test fun `Office split Weight Loss searches selected numbers while Business Ethics retains verified native references`() {
        val (series, order) = recordedTvdbOrder("office", "alternate")
        val stream = mockk<Stream>()
        val source = listOf(
            Video("tt0386676:5:1", "Weight Loss", "2008-09-25T12:00:00Z", "provider-combined-image",
                season = 5, episode = 1, overview = null, available = true),
            Video("tt0386676:5:2", "Business Ethics", "2008-10-09T12:00:00Z", "provider-ethics-image",
                streams = listOf(stream), season = 5, episode = 2, overview = null, available = true))
        val result = tvdbEpisodeOverlay(tvdbFixtureMeta(source).copy(id = "tt0386676"), series, order)
        val split = result.videos.filter { it.title.startsWith("Weight Loss") }
        assertEquals(2, split.size)
        assertTrue(split.all { it.hasResolvedSource && it.available == true && it.id == "tt0386676:5:${it.episode}" })
        assertTrue(split.all { it.episodeIdentity!!.trackingEpisodeIds.size == 1 })
        assertEquals(2, split.map { it.episodeListKey }.distinct().size)
        val ethics = result.videos.single { it.title == "Business Ethics" }
        assertTrue(ethics.hasResolvedSource)
        assertEquals(true, ethics.available)
        assertEquals("tt0386676:5:3", ethics.id)
        assertEquals("tt0386676:5:2", ethics.episodeIdentity!!.providerVideoId)
        assertEquals(3, ethics.forEpisodeDisplay().sourceEpisode)
        assertEquals(3, ethics.displayEpisode)
        assertEquals("provider-ethics-image", ethics.thumbnail)
        assertTrue(ethics.streams.isEmpty())
    }

    @Test fun `resolved provider restrictions and unknown availability are preserved`() {
        for (available in listOf(true, false, null)) {
            val original = tvdbFixtureSource().map { it.copy(available = available, thumbnail = "provider-art") }
            val order = tvdbFixtureOrder().let { it.copy(episodes = it.episodes.map { episode -> episode.copy(image = null) }) }
            val result = tvdbEpisodeOverlay(tvdbFixtureMeta(original), 78874, order)
            assertTrue(result.videos.all { it.hasResolvedSource && it.available == available && it.thumbnail == "provider-art" })
            assertEquals(setOf("tt0303461:1:2", "tt0303461:1:4"), result.videos.map { it.id }.toSet())
        }
    }

    @Test fun `TVDB artwork takes precedence when supplied`() {
        val result = tvdbEpisodeOverlay(tvdbFixtureMeta(tvdbFixtureSource().map { it.copy(thumbnail = "provider-art") }),
            78874, tvdbFixtureOrder())
        assertEquals("tvdb-train", result.videos.single { it.title == "The Train Job" }.thumbnail)
    }
}
