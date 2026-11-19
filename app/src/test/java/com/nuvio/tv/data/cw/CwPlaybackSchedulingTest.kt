package com.nuvio.tv.data.cw

import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CwPlaybackSchedulingTest {
    @Test fun runningWorkIsCancelledAndOnlyTheLatestRevisionResumesAfterAllPlayersClose() = runTest {
        val source = MutableStateFlow(1)
        val started = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        backgroundScope.launchWhenCwPlaybackIdle {
            source.collectLatest { revision ->
                started += revision
                try { awaitCancellation() } finally { stopped += revision }
            }
        }
        runCurrent()
        assertEquals(listOf(1), started)
        val movie = PlayerSessionTracker.open()
        val next = PlayerSessionTracker.open()
        try {
            runCurrent()
            assertEquals(listOf(1), stopped)
            for (revision in 2..184) source.value = revision
            movie.close()
            runCurrent()
            assertEquals(listOf(1), started)
            PlayerSessionTracker.livePlayback.value = true
            next.close()
            runCurrent()
            assertEquals(listOf(1), started)
            PlayerSessionTracker.livePlayback.value = false
            runCurrent()
            assertEquals(listOf(1, 184), started)
        } finally {
            movie.close()
            next.close()
            PlayerSessionTracker.livePlayback.value = false
        }
    }

    @Test fun periodicJobsAndPlayerCheckpointsDoNotRefreshTheLibraryDuringPlayback() = runTest {
        val cache = mockk<ContinueWatchingEnrichmentCache>()
        val repository = mockk<ContinueWatchingRepository>(relaxed = true)
        val recommendations = TvRecommendationManager(mockk(), mockk(), mockk(), repository)
        val channel = AndroidTvChannelSyncService(mockk(), mockk(), cache, mockk(), mockk(), recommendations)
        val session = PlayerSessionTracker.open()
        try {
            repeat(12) { recommendations.refreshAfterPlayerSave() }
            channel.reconcileFromCache()
            coVerify(exactly = 0) { cache.getSnapshot() }
            coVerify(exactly = 0) { repository.refreshProjection() }
        } finally {
            session.close()
        }
        recommendations.refreshAfterPlayerSave()
        coVerify(exactly = 1) { repository.refreshProjection() }
    }
}
