package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.domain.model.EpisodeIdentity
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePartialWatchTest {
    private val parent = "tt0386676"
    private val video = Video("$parent:6:11", "Shareholder Meeting", null, null,
        season = 6, episode = 11, overview = null)
    private val pause = WatchProgress(parent, "series", "The Office", null, null, video.id,
        6, 11, video.title, 0, 0, 1L, progressPercent = 47.18f)

    @Test fun percentageOnlyRewatchCanBeRemovedWithoutMatchingCompletedOrSiblingRecords() {
        assertTrue(pause.matchesPartialEpisode(parent, video))
        assertTrue(pause.copy(progressPercent = 1f).matchesPartialEpisode(parent, video))
        assertFalse(pause.copy(progressPercent = 100f).matchesPartialEpisode(parent, video))
        assertFalse(pause.copy(progressPercent = 0f).matchesPartialEpisode(parent, video))
        assertFalse(pause.copy(episode = 10).matchesPartialEpisode(parent, video))
        assertFalse(pause.copy(contentId = "another-series").matchesPartialEpisode(parent, video))
    }

    @Test fun selectedOrderUsesVerifiedIdentityEvenWhenTwoRecordsShareTheSameEpisodeNumber() {
        val selected = video.copy(episodeIdentity = EpisodeIdentity(10719611, 73244, "alttwo",
            EpisodeCoordinates(6, 11), EpisodeCoordinates(6, 11), canonicalTvdbEpisodeId = 1271801))
        assertTrue(pause.copy(tvdbEpisodeId = 1271801).matchesPartialEpisode(parent, selected))
        assertFalse(pause.copy(tvdbEpisodeId = 1832421).matchesPartialEpisode(parent, selected))
        assertFalse(pause.copy(tvdbEpisodeId = 1271801, tvdbCompletedEpisodeIds = listOf(1271801))
            .matchesPartialEpisode(parent, selected))
    }
}
