package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.cw.*
import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.data.tvdb.*
import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingUnwatchTest {
    private val empty = CwProjection(profileId = 1, source = "NUVIO_SYNC")
    // Local watched-history seeds use the series ID for every episode's videoId.
    private fun p(ep: Int, time: Long = ep * 1000L) = WatchProgress("show", "series", "Show",
        null, null, null, "show", 1, ep, null, 100, 100, time)
    private val videos = (1..6).map { CwVideoSummary("show:1:$it", "Episode $it", "2020-01-01", null, 1, it, null) }
    private val meta = CwMetaSummary("show", "Show", null, null, null, null, emptyList(), null, null, null, null, videos)
    private fun history(vararg ps: WatchProgress) = CwMembershipReducer.progress(empty, ps.toList(), seeds = true)
    private fun resolve(state: CwProjection, seed: WatchProgress, catalogue: CwMetaSummary = meta): CwProjection {
        val rebased = CwMembershipReducer.progress(state, listOf(seed), seeds = true)
        assertEquals("remaining committed history must replace the revoked seed", seed, rebased.members[seed.contentId]?.progress)
        val next = resolveNextUpVideoFromMeta(seed, catalogue, false)!!
        val candidate = seed.seriesChoice().copy(videoId = next.id, season = next.season!!, episode = next.episode!!,
            chooseEpisode = false, tvdbEpisodeId = next.episodeIdentity?.trackingEpisodeId,
            tvdbContext = next.asTvdbVideo().tvdbPlaybackContext())
        return CwMembershipReducer.enrich(rebased, rebased.lease, nextUp = listOf(candidate))
    }

    @Test fun `latest unwatch accepts older remaining seed and offers the revoked episode`() {
        val e4 = p(4)
        val e5 = p(5)
        val removed = CwMembershipReducer.historyRemoved(history(e4, e5), "show", setOf(1 to 5), 6000)
        val replayed = CwMembershipReducer.progress(removed, listOf(e5), seeds = true)
        val result = resolve(replayed, e4)
        assertEquals(5, result.nextUp.single().episode)
        assertFalse(result.nextUp.single().chooseEpisode)
        assertEquals(e4.lastWatched, result.nextUp.single().lastWatched)
        assertEquals(result, CwMembershipReducer.progress(result, listOf(e5), seeds = true))
    }

    @Test fun `saved local1 placeholder accepts remaining history without clearing the cache`() {
        val earlier = p(4).copy(videoId = "show:1:4")
        val revoked = p(5).copy(videoId = "show:1:5")
        val saved = history(earlier).copy(
            members = mapOf("show" to CwMember(nextUp = revoked.seriesChoice())),
            ignoredProgress = mapOf("show|show:1:5|" to 6000L, "show|show|" to 6000L))
        assertEquals(saved, CwMembershipReducer.progress(saved, listOf(revoked), seeds = true))
        val result = resolve(saved, earlier)
        assertEquals(5, result.nextUp.single().episode)
        assertFalse(result.nextUp.single().chooseEpisode)
        // The shared legacy key must not suppress another episode's series-only seed.
        assertEquals(p(4), CwMembershipReducer.progress(saved, listOf(p(4)), seeds = true).members["show"]?.progress)
    }

    @Test fun `older unwatch retains the current seed and normal next episode`() {
        val removed = CwMembershipReducer.historyRemoved(history(p(3), p(4), p(5)), "show", setOf(1 to 3), 6000)
        val result = resolve(removed, p(5))
        assertEquals(6, result.nextUp.single().episode)
        assertFalse(result.nextUp.single().chooseEpisode)
    }

    @Test fun `all episodes unwatched removes the card as in 97 and stale history cannot resurrect it`() {
        val removed = CwMembershipReducer.historyRemoved(history(p(4), p(5)), "show", setOf(1 to 4, 1 to 5), 6000)
        val result = CwMembershipReducer.progress(removed, listOf(p(4), p(5)), seeds = true)
        assertTrue(result.members.isEmpty())
        assertTrue(ownedShuffleSeeds(result, 1).isEmpty())
        assertTrue(CwMembershipReducer.enrich(result, empty.lease, nextUp = listOf(p(5).seriesChoice())).members.isEmpty())
        assertEquals(4, CwMembershipReducer.progress(result, listOf(p(4, 7000)), seeds = true).members["show"]?.progress?.episode)
    }

    @Test fun `remaining furthest seed can be older than the revoked latest watch`() {
        // Existing next-up policy supplies E4 when furthest is enabled, E2 when disabled.
        val e4 = p(4, 1000)
        val e2 = p(2, 2000)
        val revoked = p(3, 3000)
        for ((seed, expected) in listOf(e4 to 5, e2 to 3)) {
            val removed = CwMembershipReducer.historyRemoved(history(e4, e2, revoked), "show", setOf(1 to 3), 4000)
            assertEquals(expected, resolve(removed, seed).nextUp.single().episode)
        }
    }

    @Test fun `alternate TVDB order recomputes from retained canonical history`() {
        val effective = tvdbEpisodeOverlay(tvdbFixtureMeta(tvdbFixtureSource()), 78874, tvdbFixtureOrder())
        val catalogue = meta.copy(id = effective.id, name = effective.name, tvdbOrder = true,
            videos = effective.videos.map { CwVideoSummary(it.id, it.title, it.released, it.thumbnail,
                it.season, it.episode, it.overview, episodeIdentity = it.episodeIdentity) })
        val ordered = catalogue.videos.sortedBy { it.episodeIdentity!!.displayed.episode }
        fun seed(index: Int) = ordered[index].let { v -> p(index + 1).copy(contentId = effective.id,
            videoId = v.id, season = v.season, episode = v.episode,
            tvdbContext = v.asTvdbVideo().tvdbPlaybackContext(), tvdbEpisodeId = v.episodeIdentity!!.trackingEpisodeId) }
        val previous = seed(0)
        val latest = seed(1)
        val removed = CwMembershipReducer.historyRemoved(history(previous, latest), effective.id, emptySet(), 6000, latest.tvdbContentIds)
        val result = resolve(removed, previous, catalogue)
        assertFalse(result.nextUp.single().chooseEpisode)
        assertEquals(latest.tvdbContentIds, result.nextUp.single().tvdbContext!!.episodeIds.toSet())
        assertEquals(previous.tvdbContext, result.members[effective.id]?.progress?.tvdbContext)
    }

    @Test fun `shuffle uses remaining membership and offers an unwatched episode after latest unwatch`() {
        val removed = CwMembershipReducer.historyRemoved(history(p(4), p(5)), "show", setOf(1 to 5), 6000)
        val resolved = resolve(removed, p(4))
        val output = applyHomeShuffle(HomeUiState(continueWatchingItems = resolved.nextUp.map { it.homeCard() }),
            EpisodeShuffleProfile(1, true, mapOf("show" to EpisodeShuffleSettings(true, false))),
            mapOf("show" to (1..4).map { 1 to it }.toSet()), EpisodeShuffle(), 1,
            ContinueWatchingSortMode.SPLIT_UPCOMING, emptyList()) { _, _ ->
                videos.take(5).map { Video(it.id, it.title!!, it.released, null, season = it.season, episode = it.episode, overview = null) }
            }
        val card = output.continueWatchingItems.single() as ContinueWatchingItem.NextUp
        assertTrue(card.shufflePlayback)
        assertFalse(card.chooseEpisode)
        assertEquals(5, card.info.episode)
        assertEquals(listOf(p(4)), ownedShuffleSeeds(resolved, 1))
    }
}
