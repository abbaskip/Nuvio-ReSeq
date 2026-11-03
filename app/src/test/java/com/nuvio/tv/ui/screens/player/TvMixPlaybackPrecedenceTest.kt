package com.nuvio.tv.ui.screens.player

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNull
import org.junit.Test

class TvMixPlaybackPrecedenceTest {
    @Test fun `mix playback does not resolve or preload an ordinary next episode`() {
        val state = MutableStateFlow(PlayerUiState())
        val controller = mockk<PlayerRuntimeController>(relaxed = true) {
            every { _uiState } returns state
            every { isMixPlayback } returns true
            every { metaVideos } returns emptyList()
        }
        controller.recomputeNextEpisode(resetVisibility = false)
        assertNull(state.value.nextEpisode)
        assertNull(state.value.postPlayMode)
        verify(exactly = 0) { controller.episodeShufflePlayback }
        verify(exactly = 0) { controller.streamRepository }
    }
}
