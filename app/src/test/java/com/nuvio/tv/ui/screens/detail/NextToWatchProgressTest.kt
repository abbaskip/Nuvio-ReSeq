package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class NextToWatchProgressTest {

    @Test fun normalSeriesResolvesFromTheCommittedEpisodeWhenTheCatalogueBecomesAvailable() = kotlinx.coroutines.test.runTest {
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        val resolver = SeriesNextToWatch(context)
        for (show in listOf("Ghost Wars", "Love It or List It Vancouver", "Mary Berry's Quick Cooking")) {
            val completed = progress("video4", 7, 4, 100, 100, 2_000, 100f)
            val older = progress("video1", 7, 1, 47, 100, 1_000, 47f)
            val history = mapOf((7 to 4) to completed, (7 to 1) to older)
            suspend fun offer(videos: List<com.nuvio.tv.domain.model.Video>) =
                resolver.resolve(show, "series", show, videos, false, null, history, setOf(7 to 4), false) { emptyMap() }
            assertEquals(null, offer(emptyList()).nextEpisode)
            val videos = (1..6).map { n -> com.nuvio.tv.domain.model.Video("video$n", "Episode $n",
                null, null, season = 7, episode = n, overview = null) }
            assertEquals("video5", offer(videos).nextVideoId)
        }
    }

    @Test
    fun `resume half watched earlier season wins over completed later season mark`() {
        val s4Completed = progress(
            videoId = "s4e1",
            season = 4,
            episode = 1,
            position = 1000L,
            duration = 1000L,
            lastWatched = 1_000L,
            progressPercent = 100f
        )
        val s1Half = progress(
            videoId = "s1e2",
            season = 1,
            episode = 2,
            position = 500L,
            duration = 1000L,
            lastWatched = 2_000L,
            progressPercent = 50f
        )

        val anchored = resolveNextToWatchLatestProgress(
            latestProgress = s4Completed,
            progressEntries = listOf(s4Completed, s1Half),
            isResumable = { !it.isCompleted() && it.progressPercentage >= 0.02f }
        )

        assertEquals(s1Half, anchored)
    }

    @Test
    fun `furthest completed stays when there is no resumable progress`() {
        val s4Completed = progress(
            videoId = "s4e1",
            season = 4,
            episode = 1,
            position = 1000L,
            duration = 1000L,
            lastWatched = 1_000L,
            progressPercent = 100f
        )

        val anchored = resolveNextToWatchLatestProgress(
            latestProgress = s4Completed,
            progressEntries = listOf(s4Completed),
            isResumable = { !it.isCompleted() && it.progressPercentage >= 0.02f }
        )

        assertEquals(s4Completed, anchored)
    }

    private fun progress(
        videoId: String,
        season: Int,
        episode: Int,
        position: Long,
        duration: Long,
        lastWatched: Long,
        progressPercent: Float
    ) = WatchProgress(
        contentId = "tt123",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = progressPercent
    )
}
