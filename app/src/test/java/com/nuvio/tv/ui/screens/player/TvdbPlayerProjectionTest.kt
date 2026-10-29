package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.PlaybackShuffleState
import com.nuvio.tv.data.tvdb.*
import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TvdbPlayerProjectionTest {
    @Test fun `player labels and badges follow identity while searches follow selected coordinates`() {
        val meta = tvdbEpisodeOverlay(tvdbFixtureMeta(tvdbFixtureSource()), 78874, tvdbFixtureOrder())
        val progress = WatchProgress(meta.id, "series", meta.name, null, null, null, "remote-slot", 1, 1,
            "Shindig", 100, 100, 1, tvdbEpisodeId = 297994)
        val shuffle = PlaybackShuffleState(EpisodeShuffleSettings(), setOf(1 to 1), mapOf((1 to 1) to progress))
        val state = PlayerUiState(currentSeason = 1, currentEpisode = 4).withTvdbPlayback(meta.videos, 1, 4, shuffle)
        assertEquals(4, state.displayEpisode)
        assertEquals(4, state.currentEpisode)
        assertEquals(setOf(1 to 4), state.watchedEpisodeKeys)
        assertEquals(setOf(1 to 4), state.episodeWatchProgressMap.keys)
        assertEquals(4, state.withTvdbPlayback(emptyList(), 1, 4, null).displayEpisode)
    }
}
