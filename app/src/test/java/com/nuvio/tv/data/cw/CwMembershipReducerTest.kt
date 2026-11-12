package com.nuvio.tv.data.cw

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.TvdbPlaybackContext
import org.junit.Assert.*
import org.junit.Test

class CwMembershipReducerTest {
    private val empty = CwProjection(profileId = 1, source = "TRAKT", epoch = 1)
    private fun progress(id: String = "show", time: Long = 1_000, episode: Int = 1, complete: Boolean = false) =
        WatchProgress(id, "series", id, null, null, null, "$id:1:$episode", 1, episode, null,
            if (complete) 100 else 20, 100, time)
    private fun state(vararg updates: WatchProgress) = CwMembershipReducer.progress(empty, updates.toList())
    private fun offer(p: WatchProgress, episode: Int) = p.seriesChoice().copy(
        videoId = "${p.contentId}:1:$episode", season = 1, episode = episode, chooseEpisode = false)

    @Test fun `partial or empty refresh retains all supported shows through five minute sequence`() {
        val cached = state(progress("a"), progress("b"), progress("c", complete = true))
        var current = cached
        listOf(0L, 60_000, 120_000, 300_000).forEach {
            current = CwMembershipReducer.progress(current, listOf(progress("a")))
            current = CwMembershipReducer.enrich(current, current.lease, nextUp = emptyList())
            assertEquals(cached.members.keys, current.members.keys)
        }
    }

    @Test fun `off Home completion atomically replaces finished episode with series choice`() {
        val p = progress()
        val before = state(p)
        val after = CwMembershipReducer.progress(before, listOf(p.copy(position = 100, lastWatched = 2_000)))
        assertTrue(after.inProgress.isEmpty())
        assertTrue(after.nextUp.single().chooseEpisode)
        assertNotEquals(p.videoId, after.nextUp.single().videoId)
        assertTrue(after.members.getValue("show").progress!!.isCompleted())
    }

    @Test fun `proven next offer replaces fallback but never the finished episode`() {
        val p = progress(complete = true)
        val current = state(p)
        assertEquals(current, CwMembershipReducer.enrich(current, current.lease, nextUp = listOf(offer(p, 1))))
        val resolved = CwMembershipReducer.enrich(current, current.lease, nextUp = listOf(offer(p, 2)))
        assertEquals(2, resolved.nextUp.single().episode)
        assertFalse(resolved.nextUp.single().chooseEpisode)
    }

    @Test fun `a different completion invalidates an offer resolved from an earlier seed`() {
        val first = progress(complete = true)
        val initial = state(first)
        val offered = CwMembershipReducer.enrich(initial, initial.lease, nextUp = listOf(offer(first, 2)))
        val after = CwMembershipReducer.progress(offered, listOf(progress(time = 2_000, episode = 3, complete = true)))
        assertTrue(after.nextUp.single().chooseEpisode)
        assertEquals(3, after.nextUp.single().seedEpisode)
    }

    @Test fun `current lease cannot offer an older completed episode after a newer completion`() {
        val first = progress(complete = true)
        val latest = progress(time = 2_000, episode = 2, complete = true)
        val after = state(first, latest)
        assertEquals(after, CwMembershipReducer.enrich(after, after.lease, nextUp = listOf(offer(latest, 1))))
        val unwatched = CwMembershipReducer.historyRemoved(after, "show", setOf(1 to 1), 3_000)
        val replay = CwMembershipReducer.progress(unwatched, listOf(first))
        assertEquals(unwatched, replay)
        assertEquals(1, CwMembershipReducer.enrich(unwatched, unwatched.lease, nextUp = listOf(offer(latest, 1))).nextUp.single().episode)
        val rewatch = CwMembershipReducer.progress(unwatched, listOf(progress(time = 4_000)))
        assertEquals(1, rewatch.inProgress.single().episode)
    }

    @Test fun `TVDB unwatch revokes only verified IDs without using display coordinates`() {
        val combined = progress(complete = true).copy(tvdbContext = TvdbPlaybackContext(123, "dvd", 40, 5, 8, listOf(40, 41), "combined"))
        val latest = progress(time = 2_000, episode = 2, complete = true)
        val after = state(combined, latest)
        val unwatched = CwMembershipReducer.historyRemoved(after, "show", emptySet(), 3_000, setOf(40))
        val firstPart = offer(latest, 7).copy(tvdbEpisodeId = 40)
        val secondPart = firstPart.copy(tvdbEpisodeId = 41)
        assertEquals(40L, CwMembershipReducer.enrich(unwatched, unwatched.lease, nextUp = listOf(firstPart)).nextUp.single().tvdbEpisodeId)
        assertEquals(unwatched, CwMembershipReducer.enrich(unwatched, unwatched.lease, nextUp = listOf(secondPart)))
        assertEquals(unwatched, CwMembershipReducer.progress(unwatched, listOf(combined)))
    }

    @Test fun `identity timeout missing key unavailable metadata and cancellation have no deletion input`() {
        val pending = state(progress(complete = true))
        listOf("timeout", "401", "503", "cancelled", "missing key", "trim").forEach { _ ->
            assertEquals(pending, CwMembershipReducer.enrich(pending, pending.lease))
        }
    }

    @Test fun `late metadata cannot restore completed episode or old progress`() {
        val p = progress()
        val before = state(p)
        val completed = CwMembershipReducer.progress(before, listOf(p.copy(position = 100, lastWatched = 2_000)))
        assertEquals(completed, CwMembershipReducer.enrich(completed, before.lease, inProgress = listOf(p.cached())))
        assertEquals(completed, CwMembershipReducer.progress(completed, listOf(p)))
    }

    @Test fun `duplicate completion and lifecycle refresh are idempotent`() {
        val p = progress(complete = true)
        val first = state(p)
        assertEquals(first, CwMembershipReducer.progress(first, listOf(p, p)))
        assertEquals(first, CwMembershipReducer.progress(first, emptyList()))
    }

    @Test fun `absent external result never invents a completion`() {
        val before = state(progress())
        val after = CwMembershipReducer.progress(before, emptyList())
        assertEquals(before, after)
        assertFalse(after.members.getValue("show").progress!!.isCompleted())
    }

    @Test fun `user removal survives stale tracker and enrichment then actual rewatch restores it`() {
        val p = progress()
        val before = state(p)
        val hidden = CwMembershipReducer.remove(before, before.lease, CwRemovalProof(CwRemovalReason.USER_REMOVAL, setOf("show"), 2_000))
        assertTrue(hidden.members.isEmpty())
        assertEquals(hidden, CwMembershipReducer.progress(hidden, listOf(p)))
        assertEquals(hidden, CwMembershipReducer.enrich(hidden, before.lease, inProgress = listOf(p.cached())))
        assertEquals(setOf("show"), CwMembershipReducer.progress(hidden, listOf(p.copy(lastWatched = 3_000))).members.keys)
    }

    @Test fun `authoritative absence cannot remove support committed after refresh began`() {
        val before = state(progress())
        val newer = CwMembershipReducer.progress(before, listOf(progress(time = 2_000)))
        val proof = CwRemovalProof(CwRemovalReason.AUTHORITATIVE_ABSENCE, setOf("show"), 3_000)
        assertEquals(newer, CwMembershipReducer.remove(newer, before.lease, proof))
        assertTrue(CwMembershipReducer.remove(newer, newer.lease, proof).members.isEmpty())
    }

    @Test fun `profile source and order epochs reject late publications`() {
        val p = progress(complete = true)
        val before = state(p)
        val offered = listOf(offer(p, 2))
        listOf(before.copy(profileId = 2), before.copy(source = "SIMKL"),
            CwMembershipReducer.invalidate(before, setOf("show"))).forEach { switched ->
            assertEquals(switched, CwMembershipReducer.enrich(switched, before.lease, nextUp = offered))
        }
    }

    @Test fun `combined TVDB episode cannot be offered after any completed coverage overlaps`() {
        val p = progress(complete = true).copy(tvdbContext = TvdbPlaybackContext(123, "dvd", 40, 1, 1, listOf(40, 41), "combined"))
        val before = state(p)
        val next = offer(p, 2).copy(tvdbEpisodeId = 41)
        assertEquals(before, CwMembershipReducer.enrich(before, before.lease, nextUp = listOf(next)))
        val safe = next.copy(tvdbEpisodeId = 42)
        assertEquals(42L, CwMembershipReducer.enrich(before, before.lease, nextUp = listOf(safe)).nextUp.single().tvdbEpisodeId)
    }

    @Test fun `display identity never replaces tracking identity`() {
        val p = progress().copy(tvdbContext = TvdbPlaybackContext(123, "dvd", 40, 2, 7, listOf(40), "display"))
        val before = state(p)
        val enriched = p.cached().copy(name = "Enriched")
        val after = CwMembershipReducer.enrich(before, before.lease, inProgress = listOf(enriched))
        assertEquals(p, after.members.getValue("show").progress)
        assertEquals(p.tvdbContext, after.inProgress.single().tvdbContext)
        assertEquals(p.videoId, after.inProgress.single().videoId)
    }

    @Test fun `visibility settings do not delete membership and unresolved choice stays on launcher`() {
        val before = state(progress(complete = true))
        assertTrue(CwMembershipReducer.visible(before, 0, setOf("show"), false, 2_000, true).members.isEmpty())
        assertEquals(before.members, CwMembershipReducer.visible(before, 0, emptySet(), false, 2_000, true).members)
    }

    @Test fun `completed movie removes only that movie`() {
        val current = state(progress("show"), progress("movie").copy(contentType = "movie", season = null, episode = null))
        val after = CwMembershipReducer.progress(current, listOf(progress("movie", time = 2_000, complete = true).copy(contentType = "movie")))
        assertEquals(setOf("show"), after.members.keys)
    }

    @Test fun `enrichment cannot rewind a newer playback position`() {
        val current = state(progress(time = 2_000).copy(position = 50))
        val after = CwMembershipReducer.enrich(current, current.lease, inProgress = listOf(progress().cached().copy(name = "Art")))
        assertEquals(50L, after.inProgress.single().position)
        assertEquals(2_000L, after.inProgress.single().lastWatched)
    }
}
