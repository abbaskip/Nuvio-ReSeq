package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.home.*
import org.junit.Assert.*
import org.junit.Test

class TvdbProgressProjectionTest {
    private val meta = tvdbEpisodeOverlay(tvdbFixtureMeta(tvdbFixtureSource()), 78874, tvdbFixtureOrder())
    private fun progress(id: Long? = null) = WatchProgress(meta.id, "series", "Firefly", null, null, null,
        "tt0303461:1:1", 1, 1, null, 50, 100, 1, tvdbEpisodeId = id)

    @Test fun `stable identity wins over an occupied numeric slot`() {
        val record = progress(297994)
        val result = reconcileTvdbProgress(meta, mapOf((1 to 1) to record))
        assertEquals(setOf(1 to 4), result.keys)
        assertEquals("tt0303461:1:4", result[1 to 4]?.videoId)
        assertEquals(1, record.episode)
    }

    @Test fun `unknown identity and numeric-only legacy progress cannot mark another episode`() {
        val raw = mapOf((1 to 1) to progress(), (1 to 2) to progress(123456))
        assertTrue(reconcileTvdbProgress(meta, raw).isEmpty())
        assertEquals(2, raw.size)
        assertSame(raw, reconcileTvdbProgress(meta.copy(episodeOrder = null), raw))
    }

    @Test fun `legacy ID plus matching title and Simkl aired references remain usable`() {
        val legacy = progress().copy(episodeTitle = "The Train Job")
        assertEquals(297989L, reconcileTvdbProgress(meta, mapOf((1 to 1) to legacy))[1 to 2]?.tvdbEpisodeId)
        val simkl = progress().copy(videoId = "simkl:123", episode = 6, trackingProviderId = "simkl")
        assertEquals(297994L, reconcileTvdbProgress(meta, mapOf((1 to 6) to simkl))[1 to 4]?.tvdbEpisodeId)
    }

    @Test fun `Continue Watching searches selected coordinates and retains content identity`() {
        val card = ContinueWatchingItem.InProgress(progress(297994))
        val projected = projectTvdbContinueWatching(card, meta.videos) as ContinueWatchingItem.InProgress
        assertEquals(4, projected.progress.episode)
        assertEquals(4, projected.displayEpisode)
        assertEquals("tt0303461:1:4", projected.progress.videoId)
        assertNull(projectTvdbContinueWatching(ContinueWatchingItem.InProgress(progress()), meta.videos))
    }

    @Test fun `next up follows selected sequence without using equal numeric positions`() {
        val videos = meta.videos.map { video -> CwVideoSummary(video.id, video.title, video.released, video.thumbnail,
            video.season, video.episode, video.overview, episodeIdentity = video.episodeIdentity) }
        val summary = CwMetaSummary(meta.id, meta.name, null, null, null, null, emptyList(), null, null, null, null,
            videos, tvdbOrder = true)
        assertEquals("tt0303461:1:4", resolveNextUpVideoFromMeta(progress(297989), summary, false)?.id)
        assertNull(resolveNextUpVideoFromMeta(progress(), summary, false))
        val reversed = summary.copy(videos = videos.map { it.copy(episodeIdentity = it.episodeIdentity!!.copy(
            displayed = EpisodeCoordinates(1, if (it.episode == 4) 1 else 2))) })
        assertNull(resolveNextUpVideoFromMeta(progress(297989), reversed, false))
    }
    @Test fun `newer partial replay retains its position despite older watched history`() {
        val video = meta.videos.single { it.title == "Shindig" }
        val old = progress(297994).copy(position = 100, lastWatched = 1)
        val replay = old.copy(position = 25, lastWatched = 2, videoId = video.id,
            season = video.season, episode = video.episode, tvdbContext = video.tvdbPlaybackContext())
        val projected = reconcileTvdbProgress(meta, mapOf((1 to 6) to old), listOf(replay)).getValue(1 to 4)
        assertEquals(25L, projected.position)
        assertTrue(projected.isInProgress())
        assertFalse(projected.isCompleted())
        assertTrue(projected.tvdbWatchedIds.isEmpty())
        assertEquals(25L, com.nuvio.tv.ui.screens.stream.streamResumeProgress(projected, false)!!.position)
        assertNull(com.nuvio.tv.ui.screens.stream.streamResumeProgress(projected, true))
    }

    @Test fun `combined replay supersedes old part completions without transferring its position to individual parts`() {
        val orders = officeOrderFixtures()
        val provider = recordedProvider(orders.getValue("official"), "tt0386676")
        val extended = tvdbEpisodeOverlay(provider, 73244, orders.getValue("alttwo"))
        val video = extended.videos.single { it.title.startsWith("Lecture Circuit") && it.episodeIdentity!!.trackingEpisodeIds.size == 2 }
        val context = video.tvdbPlaybackContext()!!
        assertEquals(2, context.providerSources!!.size)
        val business = tvdbEpisodeOverlay(provider, 73244, orders.getValue("alternate")).videos.single { it.title == "Business Ethics" }
        assertEquals(5 to 3, business.season to business.episode)
        assertEquals(EpisodeCoordinates(5, 2), business.tvdbPlaybackContext()!!.providerSources!!.single().coordinates)
        val history = context.episodeIds.mapIndexed { index, id -> progress(id).copy(contentId = provider.id,
            season = 5, episode = 14 + index, position = 100, tvdbEpisodeIds = listOf(id), lastWatched = 1000) }
        val replay = history.first().copy(videoId = video.id, season = video.season, episode = video.episode,
            tvdbEpisodeIds = context.episodeIds, tvdbContext = context, position = 500_000, duration = 2_800_000,
            lastWatched = 2000, source = WatchProgress.SOURCE_LOCAL)
        val projected = reconcileTvdbProgress(extended, emptyMap(), history + replay).getValue(5 to 16)
        assertEquals(500_000L, projected.position)
        assertFalse(projected.isCompleted())
        assertEquals(context.episodeIds.toSet(), projected.tvdbContentIds)
        val completed = replay.copy(position = replay.duration, lastWatched = 3000)
        assertTrue(reconcileTvdbProgress(extended, emptyMap(), history + replay + completed).getValue(5 to 16).isCompleted())
        val split = tvdbEpisodeOverlay(provider, 73244, orders.getValue("alternate"))
        val parts = reconcileTvdbProgress(split, emptyMap(), listOf(replay)).values
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.all { it.position == 0L && !it.isCompleted() })
        val native = mapOf((5 to 14) to history.first())
        assertSame(native, reconcileTvdbProgress(provider, native, listOf(replay)))
    }

}
