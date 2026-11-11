package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingOrderRegressionTest {
    @Test fun newestEpisodesKeepIdentityAndStableTiesAcrossMixedHistory() {
        val random = kotlin.random.Random(719)
        val items = List(7000) { i ->
            val type = listOf("movie", "series", "TV", "Series")[i % 4]
            WatchProgress("show-" + random.nextInt(100), type, "Title", null, null, null,
                "video-$i", 1, i, "Episode $i", 500, 2000, random.nextLong(20), tvdbEpisodeId = i.toLong())
        }
        val (episodes, movies) = items.partition { it.contentType != "movie" }
        val expected = (movies + episodes.sortedByDescending { it.lastWatched }.distinctBy { it.contentId })
            .sortedByDescending { it.lastWatched }
        assertEquals(expected, deduplicateInProgress(items))
    }
}
