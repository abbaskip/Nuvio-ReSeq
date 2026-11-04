package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeEndConfirmationTest {
    private val next = NextEpisodeInfo(
        videoId = "series:1:2",
        season = 1,
        episode = 2,
        title = "Next episode",
        thumbnail = null,
        overview = null,
        released = null,
        hasAired = true,
        unairedMessage = null
    )
    private val ended = PlayerUiState(
        playbackEnded = true,
        nextEpisode = next,
        streamAutoPlayNextEpisodeEnabled = true,
        streamAutoPlayMode = StreamAutoPlayMode.MANUAL
    )

    @Test
    fun `an ignored early card autoplays only when the episode actually ends`() {
        val offered = ended.copy(postPlayMode = PostPlayMode.AutoPlay(next))
        assertFalse(offered.copy(playbackEnded = false).shouldConfirmNextEpisodeOnEnd())
        assertFalse(offered.copy(playbackEnded = false).shouldAutoPlayNextEpisodeOnEnd())
        assertFalse(offered.shouldConfirmNextEpisodeOnEnd())
        assertTrue(offered.shouldAutoPlayNextEpisodeOnEnd())
    }

    @Test
    fun `dismissing the early card still offers confirmation at the end`() {
        val dismissed = ended.copy(postPlayDismissedForCurrentEpisode = true)
        assertFalse(dismissed.copy(playbackEnded = false).shouldConfirmNextEpisodeOnEnd())
        assertTrue(dismissed.shouldConfirmNextEpisodeOnEnd())
        assertFalse(dismissed.shouldAutoPlayNextEpisodeOnEnd())
    }

    @Test
    fun `autoplay still runs at completion if no early card appeared`() {
        assertFalse(ended.shouldConfirmNextEpisodeOnEnd())
        assertTrue(ended.shouldAutoPlayNextEpisodeOnEnd())
    }

    @Test
    fun `autoplay setting controls automatic advance in both manual and automatic source modes`() {
        listOf(StreamAutoPlayMode.MANUAL, StreamAutoPlayMode.FIRST_STREAM).forEach { mode ->
            val state = ended.copy(streamAutoPlayMode = mode)
            assertTrue(state.shouldAutoPlayNextEpisodeOnEnd())
            assertFalse(state.shouldConfirmNextEpisodeOnEnd())
            assertFalse(state.copy(streamAutoPlayNextEpisodeEnabled = false).shouldAutoPlayNextEpisodeOnEnd())
            assertFalse(state.copy(postPlayDismissedForCurrentEpisode = true).shouldAutoPlayNextEpisodeOnEnd())
            assertTrue(state.copy(postPlayDismissedForCurrentEpisode = true).shouldConfirmNextEpisodeOnEnd())
        }
    }

    @Test
    fun `existing confirmation for automatic source selection is preserved with next autoplay off`() {
        assertTrue(
            ended.copy(
                streamAutoPlayNextEpisodeEnabled = false,
                streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM
            ).shouldConfirmNextEpisodeOnEnd()
        )
        assertTrue(
            ended.copy(
                streamAutoPlayNextEpisodeEnabled = false,
                streamAutoPlayPreferBingeGroupForNextEpisode = true
            ).shouldConfirmNextEpisodeOnEnd()
        )
        assertFalse(
            ended.copy(streamAutoPlayNextEpisodeEnabled = false).shouldConfirmNextEpisodeOnEnd()
        )
    }

    @Test
    fun `confirmation does not interrupt a source search or another blocking prompt`() {
        listOf(
            ended.copy(showEpisodesPanel = true),
            ended.copy(showSourcesPanel = true),
            ended.copy(postPlayMode = PostPlayMode.AutoPlay(next, searching = true)),
            ended.copy(postPlayMode = PostPlayMode.StillWatching(next)),
            ended.copy(pendingExitReason = PlayerExitReason.StillWatchingPrompt)
        ).forEach { state ->
            assertFalse(state.shouldConfirmNextEpisodeOnEnd())
            assertFalse(state.shouldAutoPlayNextEpisodeOnEnd())
        }
    }

    @Test
    fun `errors or missing unaired and unavailable episodes cannot advance or offer confirmation`() {
        listOf(
            ended.copy(error = "Playback failed"),
            ended.copy(nextEpisode = null),
            ended.copy(nextEpisode = next.copy(hasAired = false)),
            ended.copy(nextEpisode = next.copy(available = false))
        ).forEach { state ->
            assertFalse(state.shouldAutoPlayNextEpisodeOnEnd())
            assertFalse(state.copy(postPlayDismissedForCurrentEpisode = true).shouldConfirmNextEpisodeOnEnd())
        }
    }
}
