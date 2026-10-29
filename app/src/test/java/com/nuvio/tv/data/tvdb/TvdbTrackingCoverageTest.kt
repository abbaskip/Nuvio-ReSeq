package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.detail.MetaDetailsUiState
import com.nuvio.tv.ui.screens.detail.withTvdbDisplay
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import com.nuvio.tv.data.simkl.*
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.*

internal fun fixtureSimkl(seriesId: Long, vararg episodes: Pair<Long, EpisodeCoordinates>): SimklSyncRepository = mockk {
    coEvery { ensureLoaded() } returns Unit
    every { state } returns MutableStateFlow(SimklSyncState(snapshot = SimklSyncSnapshot(entries = listOf(
        SimklLibraryEntry(show = SimklMedia(ids = mapOf("tvdb" to JsonPrimitive(seriesId))),
            seasons = episodes.groupBy { it.second.season }.map { (season, rows) -> SimklSeason(season,
                rows.map { SimklEpisode(number = it.second.episode, ids = SimklEpisodeIds(it.first)) }) })))))
}

class TvdbTrackingCoverageTest {
    private val orders = officeOrderFixtures()
    private val provider = recordedProvider(orders.getValue("official"), "tt0386676")
    private val alternate = tvdbEpisodeOverlay(provider, 73244, orders.getValue("alternate"))
    private fun target(vararg ids: Long) = TvdbTrackingTarget(TrackingEpisode(5, 1), ids.toSet())

    @Test fun `live public Trakt catalogue supplies verified content coverage for every Extended Cut source`() {
        val text = javaClass.getResourceAsStream("/tvdb/office-trakt-episodes.json")!!.bufferedReader().use { it.readText() }
        val rows = Json.parseToJsonElement(text).jsonArray.flatMap { it.jsonObject.getValue("episodes").jsonArray }
        val extended = tvdbEpisodeOverlay(provider, 73244, orders.getValue("alttwo"))
        val index = TvdbTrackerContentIndex(extended)
        val targets = rows.mapNotNull { value ->
            val row = value.jsonObject
            val ids = row.getValue("ids").jsonObject
            val coverage = index.match(ids["tvdb"]?.jsonPrimitive?.longOrNull,
                row["title"]?.jsonPrimitive?.contentOrNull, row["first_aired"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
            TvdbTrackingTarget(TrackingEpisode(row.getValue("season").jsonPrimitive.int,
                row.getValue("number").jsonPrimitive.int, row["title"]?.jsonPrimitive?.contentOrNull,
                traktId = ids["trakt"]?.jsonPrimitive?.intOrNull), coverage)
        }
        extended.videos.distinctEpisodeSources().forEach { source ->
            val content = source.episodeIdentity!!.trackingEpisodeIds.toSet()
            val matching = targets.filter { it.contentIds.any(content::contains) }
            assertTrue("No complete native Trakt coverage for ${source.title}", matching.flatMap { it.contentIds }.containsAll(content))
            assertTrue("Ambiguous native Trakt coverage for ${source.title}", content.all { id -> matching.count { id in it.contentIds } == 1 })
        }
        assertEquals(4 to 14, targets.single { it.episode.title == "Goodbye Toby" }.episode.let { it.season to it.number })
        assertEquals(5 to 2, targets.single { it.episode.title == "Business Ethics" }.episode.let { it.season to it.number })
    }

    @Test fun `a genuine part cannot scrobble or complete a combined tracker alone`() {
        val combined = listOf(target(1, 2))
        assertTrue(eligibleTvdbTargets(setOf(1), combined, false, emptySet()).isEmpty())
        assertTrue(eligibleTvdbTargets(setOf(1), combined, true, emptySet()).isEmpty())
        assertEquals(combined, eligibleTvdbTargets(setOf(2), combined, true, setOf(1)))
    }

    @Test fun `verified full combined video covers both parts and preserves actual progress`() {
        val combined = listOf(target(1, 2))
        assertEquals(combined, eligibleTvdbTargets(setOf(1, 2), combined, false, emptySet()))
        assertEquals(combined, eligibleTvdbTargets(setOf(1, 2), combined, true, emptySet()))
        val identity = alternate.videos.first { it.title.startsWith("Weight Loss") }.episodeIdentity!!
        val uncertain = WatchProgress("tt0386676", "series", "Office", null, null, null, "tt0386676:5:1", 5, 1,
            "Weight Loss", 1, 1, 1, tvdbEpisodeId = identity.trackingEpisodeId, tvdbEpisodeIds = emptyList())
        assertTrue(uncertain.tvdbWatchedIds.isEmpty())
        assertNull(resolveTvdbProgressVideo(alternate.videos, uncertain))
    }

    @Test fun `combined source fans out only at whole completion without fabricated percentages`() {
        val parts = listOf(target(1), target(2))
        assertTrue(eligibleTvdbTargets(setOf(1, 2), parts, false, emptySet()).isEmpty())
        assertEquals(parts, eligibleTvdbTargets(setOf(1, 2), parts, true, emptySet()))
        assertTrue(eligibleTvdbTargets(setOf(1, 2, 3), parts, true, emptySet()).isEmpty())
    }

    @Test fun `all twenty formerly unmatched Extended Cut rows have verified content and real source references`() {
        val result = tvdbEpisodeOverlay(provider, 73244, orders.getValue("alttwo"))
        assertEquals(194, result.videos.size)
        assertTrue(result.videos.filter { !it.hasResolvedSource }.joinToString { it.title }, result.videos.all { it.hasResolvedSource })
        val lecture = result.videos.single { it.episodeIdentity!!.tvdbEpisodeId == 10718533L }
        assertEquals(listOf("tt0386676:5:14", "tt0386676:5:15"), lecture.episodeIdentity!!.providerSources.map { it.videoId })
        assertEquals(setOf(427605L, 427606L), lecture.episodeIdentity!!.contentEpisodeIds.toSet())
    }

    @Test fun `actual provider references remain native while every order uses its selected search numbers`() {
        val text = javaClass.getResourceAsStream("/tvdb/office-provider-episodes.json")!!.bufferedReader().use { it.readText() }
        val rows = Json.parseToJsonElement(text).jsonArray.map { value ->
            val row = value.jsonObject
            Video(row.getValue("id").jsonPrimitive.content, row.getValue("title").jsonPrimitive.content,
                row["released"]?.jsonPrimitive?.contentOrNull, null, season = row["season"]?.jsonPrimitive?.intOrNull,
                episode = row["episode"]?.jsonPrimitive?.intOrNull, overview = null,
                available = row["available"]?.jsonPrimitive?.booleanOrNull)
        }
        val original = tvdbFixtureMeta(rows).copy(id = "tt0386676")
        for ((type, order) in orders) {
            val result = tvdbEpisodeOverlay(original, 73244, order)
            val misses = result.videos.filter { !it.hasResolvedSource }
            println("Actual Office provider $type: ${result.videos.size - misses.size}/${result.videos.size}; unresolved=" + misses.joinToString { "${it.title} [${it.episodeIdentity!!.tvdbEpisodeId}]" })
            if (type == "alttwo") assertTrue(misses.joinToString { it.title }, misses.isEmpty())
            result.videos.forEach { source ->
                assertEquals("tt0386676:${source.displaySeason}:${source.displayEpisode}", source.id)
                source.episodeIdentity!!.providerSources.forEach { ref ->
                    val raw = rows.single { it.id == ref.videoId }
                    assertEquals(raw.season to raw.episode, ref.coordinates.let { it.season to it.episode })
                    if (raw.available == false) assertEquals(false, source.available)
                }
            }
            if (type == "alternate") {
                assertEquals("tt0386676:5:3", result.videos.single { it.title == "Business Ethics" }.id)
                assertTrue(result.videos.filter { tvdbEpisodeBaseTitle(it.title) == tvdbEpisodeBaseTitle("Goodbye Toby") }
                    .all { it.episodeIdentity!!.providerSources.single().videoId == "tt0386676:4:14" })
            }
        }
    }

    @Test fun `stale Trakt TVDB hits cannot identify unrelated specials and verified combined IDs cover actual parts`() {
        val index = TvdbTrackerContentIndex(alternate)
        assertNull(index.match(335408, "The Anatomy of Jim and Pam's Kiss", "2006-05-11"))
        assertEquals(setOf(335408L, 4075826L), index.match(7934957, "Fun Run", null))
        assertEquals(setOf(7931988L, 4075860L), index.match(363563, "Goodbye Toby", "2008-05-16T01:00:00Z"))
    }

    @Test fun `one watched tracker part cannot mark a combined source or its other display part complete`() {
        val partIds = alternate.videos.first { it.title.startsWith("Weight Loss") }.episodeIdentity!!.trackingEpisodeIds
        val entry = WatchProgress("tt0386676", "series", "Office", null, null, null,
            "tracker-part", 5, 1, null, 1, 1, 1, source = WatchProgress.SOURCE_TRAKT_HISTORY,
            tvdbEpisodeId = partIds.first(), tvdbEpisodeIds = listOf(partIds.first()))
        val projected = reconcileTvdbProgress(alternate, mapOf((5 to 1) to entry))
        assertTrue(projected.getValue(5 to 1).isCompleted())
        assertNull(projected[5 to 2])
        val displayed = MetaDetailsUiState(meta = alternate, episodeProgressMap = projected).withTvdbDisplay()
        assertTrue(5 to 1 in displayed.watchedEpisodes)
        assertFalse(5 to 2 in displayed.watchedEpisodes)
    }
}
