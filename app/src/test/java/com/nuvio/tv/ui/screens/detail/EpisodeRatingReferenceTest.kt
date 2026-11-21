package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.domain.model.EpisodeIdentity
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.*
import org.junit.Test

class EpisodeRatingReferenceTest {
    private val meta = Meta(id = "tt1234567", type = ContentType.SERIES, name = "Series",
        poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), runtime = null,
        director = emptyList(), cast = emptyList(), videos = emptyList(), country = null, awards = null,
        language = null, links = emptyList())
    private val video = Video("tt1234567:2:7", "Episode", null, null, season = 2, episode = 7, overview = null)
    private val identity = EpisodeIdentity(11, 42, "dvd", EpisodeCoordinates(2, 7), EpisodeCoordinates(1, 4),
        canonicalEpisodeIds = listOf(99))

    @Test fun ordinaryEpisodeUsesCatalogNumbering() {
        val episode = meta.episodeRatingReference(video)!!.episode!!
        assertEquals(2, episode.season)
        assertEquals(7, episode.number)
    }

    @Test fun reorderedEpisodeUsesItsVerifiedAiredIdentity() {
        val episode = meta.episodeRatingReference(video.copy(episodeIdentity = identity))!!.episode!!
        assertEquals(1, episode.season)
        assertEquals(4, episode.number)
        assertEquals(99L, episode.tvdbId)
    }

    @Test fun ambiguousOrUnverifiedEpisodeHasNoSingleRatingDestination() {
        assertNull(meta.episodeRatingReference(video.copy(episodeIdentity = identity.copy(contentVerified = false))))
        assertNull(meta.episodeRatingReference(video.copy(episodeIdentity = identity.copy(canonicalEpisodeIds = listOf(99, 100)))))
        assertNull(meta.episodeRatingReference(video.copy(episode = null)))
    }
}
