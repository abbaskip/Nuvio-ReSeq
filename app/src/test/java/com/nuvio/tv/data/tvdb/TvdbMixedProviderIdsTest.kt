package com.nuvio.tv.data.tvdb

import com.nuvio.tv.data.cw.AndroidAtomicRenameRule
import com.nuvio.tv.data.cw.CwMember
import com.nuvio.tv.data.cw.CwProjection
import com.nuvio.tv.data.cw.CwProjectionStore
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.ui.navigation.createContinueWatchingRoute
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

    @get:org.junit.Rule val rename = AndroidAtomicRenameRule()
    @get:org.junit.Rule val directory = org.junit.rules.TemporaryFolder()

    @Test fun `cached alternate order cards launch the same stream as the fresh Details episode`() {
        val meta = tvdbEpisodeOverlay(provider, 73244, orders.getValue("alttwo"))
        val store = CwProjectionStore(directory.root)
        for (number in listOf(17, 18, 3)) {
            val details = meta.videos.single { it.displaySeason == 6 && it.displayEpisode == number }
            val identity = requireNotNull(details.episodeIdentity)
            val fresh = requireNotNull(details.tvdbPlaybackContext())
            if (number == 17) assertEquals(10719652L, identity.tvdbEpisodeId)
            val legacy = CachedNextUpItem(contentId = provider.id,
                contentType = "series", name = "The Office", poster = null, backdrop = null, logo = null,
                videoId = "obsolete-request", season = 6, episode = number, episodeTitle = details.title,
                thumbnail = null, lastWatched = 1, sortTimestamp = 1, tvdbEpisodeId = identity.tvdbEpisodeId,
                tvdbContext = fresh.copy(requestVideoId = "obsolete-request",
                    episodeIds = if (number == 17) listOf(1832421L) else fresh.episodeIds))
            store.write(CwProjection(profileId = 1, source = "TRAKT",
                members = mapOf(provider.id to CwMember(nextUp = legacy))))
            val card = store.read(1, "TRAKT").members.getValue(provider.id).nextUp!!.homeCard()
            val launched = projectTvdbContinueWatching(card, meta.videos) as? ContinueWatchingItem.NextUp
            assertEquals("stream for ${details.title}", details.id, launched?.info?.videoId)
            assertEquals(details.sourceSeason to details.sourceEpisode, launched!!.info.season to launched.info.episode)
            assertEquals(fresh, launched.info.tvdbContext)
            val route = java.net.URLDecoder.decode(createContinueWatchingRoute(launched), "UTF-8")
            assertEquals("stream/${details.id}/series/The Office", route.substringBefore('?'))
            assertTrue(route.contains("&season=${details.sourceSeason}&episode=${details.sourceEpisode}&"))
            assertEquals(fresh, TvdbPlaybackContext.decode(route.substringAfter("&tvdbContext=")))
            val resume = ContinueWatchingItem.InProgress(WatchProgress(provider.id, "series", "The Office",
                null, null, null, legacy.videoId, 6, number, details.title, 64, 100, 1,
                tvdbContext = legacy.tvdbContext, tvdbEpisodeIds = emptyList()))
            val resolved = projectTvdbContinueWatching(resume, meta.videos) as ContinueWatchingItem.InProgress
            assertEquals(details.id, resolved.progress.videoId)
            assertEquals(fresh, resolved.progress.tvdbContext)
            assertEquals(64L, resolved.progress.position)
            assertNull(projectTvdbContinueWatching(card, meta.videos - details))
            assertNull(projectTvdbContinueWatching(card, tvdbEpisodeOverlay(provider, 73244, orders.getValue("official")).videos))
        }
    }
}
