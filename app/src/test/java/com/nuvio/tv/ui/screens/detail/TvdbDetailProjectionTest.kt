package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.data.tvdb.*
import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TvdbDetailProjectionTest {
    @Test fun `watched progress follows the matched episode without rewriting stored coordinates`() {
        val meta = tvdbEpisodeOverlay(tvdbFixtureMeta(tvdbFixtureSource()), 78874, tvdbFixtureOrder())
        val progress = WatchProgress(meta.id, "series", "Firefly", null, null, null,
            "tt0303461:1:6", 1, 6, "Shindig", 100, 100, 1, tvdbEpisodeId = 297994)
        val original = MetaDetailsUiState(meta = meta, episodesForSeason = meta.videos,
            watchedEpisodes = setOf(1 to 4), episodeProgressMap = reconcileTvdbProgress(meta, mapOf((1 to 6) to progress)))
        val displayed = original.withTvdbDisplay()
        assertEquals(setOf(1 to 4), displayed.watchedEpisodes)
        assertEquals(progress.position, displayed.episodeProgressMap[1 to 4]?.position)
        assertEquals(297994L, displayed.episodeProgressMap[1 to 4]?.tvdbEpisodeId)
        assertEquals(setOf(1 to 4), original.watchedEpisodes)
        assertEquals(6, progress.episode)
        assertEquals(4, displayed.episodesForSeason[1].episode)
        assertEquals(4, displayed.episodesForSeason[1].forEpisodeSource().episode)
    }
}
