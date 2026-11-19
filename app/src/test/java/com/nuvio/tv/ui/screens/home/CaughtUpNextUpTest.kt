package com.nuvio.tv.ui.screens.home

import android.content.Context
import com.nuvio.tv.data.cw.CwMembershipReducer
import com.nuvio.tv.data.cw.CwProjection
import com.nuvio.tv.data.cw.presentChangesFrom
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.detail.SeriesNextToWatch
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CaughtUpNextUpTest {
    private val episodes = (1..5).map { n ->
        Video("show:1:$n", "Episode $n", null, null, season = 1, episode = n, overview = null)
    }
    private val finale = WatchProgress("show", "series", "Show", null, null, null,
        "show:1:4", 1, 4, "Finale", 100, 100, 4_000)
    private val resolver = SeriesNextToWatch(mockk<Context>(relaxed = true))

    @Test fun caughtUpWithEarlierGapsAndNewRelease() = runTest {
        val history = mapOf((1 to 4) to finale)
        for (furthest in listOf(true, false)) {
            for (ordered in listOf(true, false)) {
                suspend fun offer(count: Int) = resolver.resolve("show", "series", "Show", episodes.take(count),
                    ordered, null, history, history.keys, furthest) { mapOf((1 to 4) to 4_000L) }

                assertEquals(finale, offer(4).completedFinalEpisode)
                assertEquals(5, offer(5).nextEpisode)
                assertNull(offer(5).completedFinalEpisode)
            }
        }
    }

    @Test fun caughtUpClearsSavedOfferAndRetainsShuffleHistory() {
        val start = CwMembershipReducer.progress(CwProjection(profileId = 1, source = "NUVIO_SYNC"), listOf(finale))
        val wrongCard = start.nextUp.single().copy(season = 1, episode = 1, videoId = "show:1:1", chooseEpisode = false)
        val shown = CwMembershipReducer.enrich(start, start.lease, nextUp = listOf(wrongCard))
        assertEquals(1, shown.nextUp.single().episode)
        val caughtUp = CwMembershipReducer.caughtUp(shown, shown.lease, "show").presentChangesFrom(shown)
        assertNull(caughtUp.presentedMembers?.get("show")?.nextUp)
        assertEquals(listOf(finale), ownedShuffleSeeds(caughtUp, 1))
        assertEquals(shown.completed, caughtUp.completed)
        assertTrue(CwMembershipReducer.enrich(caughtUp, shown.lease, nextUp = listOf(wrongCard)).nextUp.isEmpty())

        val resume = finale.copy(position = 40, lastWatched = 5_000)
        val rewatch = CwMembershipReducer.progress(caughtUp, listOf(resume))
        assertSame(rewatch, CwMembershipReducer.caughtUp(rewatch, shown.lease, "show"))
        assertEquals(40L, rewatch.inProgress.single().position)
    }
}
