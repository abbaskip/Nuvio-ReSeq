package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.home.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TvdbMixedProviderIdsTest {
    private val orders = officeOrderFixtures()
    private val recorded = Json.parseToJsonElement(javaClass.getResourceAsStream(
        "/tvdb/office-provider-mixed-ids.json")!!.bufferedReader().use { it.readText() }).jsonObject
    private val provider = tvdbFixtureMeta(recorded.getValue("videos").jsonArray.map { value ->
        val row = value.jsonObject
        Video(row.getValue("id").jsonPrimitive.content, row.getValue("title").jsonPrimitive.content,
            row["released"]?.jsonPrimitive?.contentOrNull, null,
            season = row["season"]?.jsonPrimitive?.intOrNull, episode = row["episode"]?.jsonPrimitive?.intOrNull,
            overview = null, available = row["available"]?.jsonPrimitive?.booleanOrNull)
    }).copy(id = "tt0386676", imdbId = "tt0386676")

    @Test fun `a TVDB special cannot disable the IMDb stream requests for every selected Office order`() {
        assertTrue(provider.videos.any { it.id == "tvdb:73244:official:0:14" })
        for ((type, order) in orders) {
            for (parent in listOf(provider, provider.copy(id = "tmdb:2316"))) {
                val result = tvdbEpisodeOverlay(parent, 73244, order)
                assertEquals(order.episodes.size, result.videos.size)
                assertTrue("$type has unmatched episodes", result.videos.all { it.hasResolvedSource })
                result.videos.forEach { video ->
                    assertEquals("tt0386676:${video.displaySeason}:${video.displayEpisode}", video.id)
                    assertEquals(type, video.episodeIdentity!!.orderType)
                }
            }
        }
    }

    @Test fun `Office Extended Cut S6E3 remains playable from both next up and saved progress`() {
        val meta = tvdbEpisodeOverlay(provider, 73244, orders.getValue("alttwo"))
        val video = meta.videos.single { it.displaySeason == 6 && it.displayEpisode == 3 }
        val identity = video.episodeIdentity!!
        assertTrue(identity.trackingEpisodeIds.isNotEmpty())
        val next = ContinueWatchingItem.NextUp(NextUpInfo(contentId = provider.id, contentType = "series",
            name = "The Office", poster = null, backdrop = null, logo = null, videoId = video.id,
            season = 6, episode = 3, episodeTitle = video.title, thumbnail = null, lastWatched = 1,
            sortTimestamp = 1, tvdbEpisodeId = identity.trackingEpisodeId, tvdbContext = video.tvdbPlaybackContext()))
        val progress = ContinueWatchingItem.InProgress(WatchProgress(provider.id, "series", "The Office",
            null, null, null, video.id, 6, 3, video.title, 50, 100, 1,
            tvdbEpisodeId = identity.trackingEpisodeId, tvdbEpisodeIds = identity.trackingEpisodeIds,
            tvdbContext = video.tvdbPlaybackContext()))
        val projectedNext = projectTvdbContinueWatching(next, meta.videos) as? ContinueWatchingItem.NextUp
        val projectedProgress = projectTvdbContinueWatching(progress, meta.videos) as? ContinueWatchingItem.InProgress
        assertNotNull(projectedNext)
        assertNotNull(projectedProgress)
        assertEquals("tt0386676:6:3", projectedNext!!.info.videoId)
        assertEquals(video.tvdbPlaybackContext(), projectedNext.info.tvdbContext)
        assertEquals(50L, projectedProgress!!.progress.position)
        assertEquals(video.tvdbPlaybackContext(), projectedProgress.progress.tvdbContext)
        assertNull(projectTvdbContinueWatching(next.copy(info = next.info.copy(tvdbEpisodeId = Long.MAX_VALUE)), meta.videos))
    }

    @Test fun `mixed unknown parents cannot be resolved by majority or episode coordinates`() {
        val unrelated = provider.copy(id = "opaque-show", imdbId = null,
            videos = provider.videos.map { it.copy(id = it.id.replace("tt0386676", "unrelated")) })
        assertTrue(tvdbEpisodeOverlay(unrelated, 73244, orders.getValue("alttwo")).videos.none { it.hasResolvedSource })
    }

    @Test fun `mixed providers can use the explicit metadata parent without an IMDb alias`() {
        val result = tvdbEpisodeOverlay(provider.copy(imdbId = null), 73244, orders.getValue("alttwo"))
        assertTrue(result.videos.all { it.hasResolvedSource && it.id.startsWith("tt0386676:") })
    }
}
