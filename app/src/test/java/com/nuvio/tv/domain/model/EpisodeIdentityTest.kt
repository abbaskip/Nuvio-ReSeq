package com.nuvio.tv.domain.model

import org.junit.Assert.*
import org.junit.Test

class EpisodeIdentityTest {
    private fun episode(id: Long, number: Int, order: String) = Video(
        id = "provider:$id", title = "Episode $id", released = null, thumbnail = null,
        season = 1, episode = number, overview = null,
        episodeIdentity = EpisodeIdentity(id, 78874, order,
            EpisodeCoordinates(1, number), null)
    )

    @Test fun `identity follows the episode across orders rather than its numeric slot`() {
        val official = episode(297989, 1, "official")
        val dvd = episode(297989, 2, "dvd")
        assertEquals(official.episodeListKey, dvd.episodeListKey)
        assertEquals(1, official.displayEpisode)
        assertEquals(2, dvd.displayEpisode)
        assertNotEquals(dvd.episodeListKey, episode(297994, 2, "dvd").episodeListKey)
    }

    @Test fun `source selection rejects duplicate identities and unresolved mappings`() {
        val first = episode(297989, 1, "official")
        assertFalse(first.hasResolvedSource)
        val mapped = first.copy(episodeIdentity = first.episodeIdentity!!.copy(
            providerVideoId = first.id, providerCoordinates = EpisodeCoordinates(1, 1)))
        assertEquals(mapped, listOf(mapped).singleEpisodeSource())
        assertNull(listOf(mapped, mapped.copy(episode = 2)).singleEpisodeSource())
        assertNull(listOf(mapped, first).singleEpisodeSource())
        assertEquals(mapped, mapped.forEpisodeSource())
    }

    @Test fun `specials have valid coordinates without becoming regular episodes`() {
        assertEquals(0, EpisodeCoordinates(0, 1).season)
        assertTrue(listOf(episode(297989, 1, "official").copy(season = 0)).watchableEpisodes().isEmpty())
    }
}
