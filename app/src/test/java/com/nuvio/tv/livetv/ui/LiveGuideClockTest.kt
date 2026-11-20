package com.nuvio.tv.livetv.ui

import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.livetv.data.BackgroundWork
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveGuideClockTest {
    @Test fun visibleClockCrossesAMinuteWithBulkWorkStillHeld() = runTest {
        val previous = PlayerSessionTracker.livePlayback.value
        PlayerSessionTracker.livePlayback.value = true
        try {
            val bulk = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { BackgroundWork.awaitIdle() }
            val times = liveGuideClock { 59_999L + testScheduler.currentTime }.take(3).toList()
            assertEquals(listOf(59_999L, 60_000L, 90_000L), times)
            assertFalse(bulk.isCompleted)
        } finally {
            PlayerSessionTracker.livePlayback.value = previous
        }
    }
}
