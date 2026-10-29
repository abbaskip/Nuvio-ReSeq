package com.nuvio.tv.ui.screens.home

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class TvdbContinueWatchingPreviewTest {
    private val context = mockk<Context>(relaxed = true) {
        every { getString(any()) } returns "Label"
        every { getString(R.string.season_episode_format, *anyVararg()) } answers {
            val coordinates = secondArg<Array<Any>>()
            "S${coordinates[0]} E${coordinates[1]}"
        }
    }

    @Test fun `Home preview and subtitle use alternate numbers while retaining playback coordinates`() {
        val progress = WatchProgress("tt0386676", "series", "The Office", null, null, null,
            "tt0386676:5:14", 5, 14, "Lecture Circuit (1)", 10, 100, 1)
        val inProgress = ContinueWatchingItem.InProgress(progress, displaySeason = 5, displayEpisode = 16)
        val nextUp = ContinueWatchingItem.NextUp(NextUpInfo(contentId = progress.contentId,
            contentType = "series", name = "The Office", poster = null, backdrop = null, logo = null,
            videoId = progress.videoId, season = 5, episode = 14, episodeTitle = progress.episodeTitle,
            thumbnail = null, lastWatched = 1, sortTimestamp = 1, displaySeason = 5, displayEpisode = 16))
        for (item in listOf(inProgress, nextUp)) {
            val carousel = buildContinueWatchingItem(item, true, false, airsDateTemplate = "%s",
                upcomingLabel = "Upcoming", context = context)
            assertEquals("S5 E16", carousel.subtitle)
            assertEquals("S5 E16 · Lecture Circuit (1)", carousel.heroPreview.contentTypeText)
            assertEquals(ModernPayload.ContinueWatching(item), carousel.payload)
        }
        assertEquals(14, progress.episode)
        assertEquals(14, nextUp.info.episode)
    }

    @Test fun `retained TVDB cards cannot bypass identity verification after returning to Provider Default`() {
        val progress = WatchProgress("tt0386676", "series", "Office", null, null, null,
            "tt0386676:5:3", 5, 3, "Business Ethics", 10, 100, 1)
        val context = com.nuvio.tv.domain.model.TvdbPlaybackContext(73244, "alternate", 349248,
            5, 3, listOf(349248), progress.videoId)
        assertFalse(ContinueWatchingItem.InProgress(progress).hasTvdbPlaybackIdentity())
        assertTrue(ContinueWatchingItem.InProgress(progress.copy(tvdbContext = context)).hasTvdbPlaybackIdentity())
        val next = NextUpInfo(contentId = progress.contentId, contentType = "series", name = "Office",
            poster = null, backdrop = null, logo = null, videoId = progress.videoId, season = 5, episode = 3,
            episodeTitle = progress.episodeTitle, thumbnail = null, lastWatched = 1, sortTimestamp = 1)
        assertFalse(ContinueWatchingItem.NextUp(next).hasTvdbPlaybackIdentity())
        val retained = ContinueWatchingItem.NextUp(next.copy(tvdbEpisodeId = 349248))
        assertTrue(retained.hasTvdbPlaybackIdentity())
        assertEquals(349248L, listOf(retained).toCachedNextUpSnapshot(emptySet()).single().tvdbEpisodeId)
    }

    @Test fun `provider default continues displaying original source numbers`() {
        val progress = WatchProgress("tt0386676", "series", "The Office", null, null, null,
            "tt0386676:5:14", 5, 14, "Lecture Circuit (1)", 10, 100, 1)
        val carousel = buildContinueWatchingItem(ContinueWatchingItem.InProgress(progress), true, false,
            airsDateTemplate = "%s", upcomingLabel = "Upcoming", context = context)
        assertEquals("S5 E14", carousel.subtitle)
        assertEquals("S5 E14 · Lecture Circuit (1)", carousel.heroPreview.contentTypeText)
    }
}
