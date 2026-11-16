package com.nuvio.tv.livetv.data

import com.nuvio.tv.core.player.PlayerSessionTracker
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackgroundWorkPolicyTest {
    @Test fun `live playback uses the same bulk hold while trailers permit first loads`() = runTest {
        PlayerSessionTracker.livePlayback.value = true
        try {
            var resumed = false
            val work = launch { BackgroundWork.awaitIdle(); resumed = true }
            runCurrent()
            assertFalse(resumed)
            try { BackgroundWork.importCheckpoint(); fail("Live playback must defer imports") }
            catch (_: BackgroundWork.PlaybackDeferred) { }
            PlayerSessionTracker.livePlayback.value = false
            work.join()
            assertTrue(resumed)
        } finally { PlayerSessionTracker.livePlayback.value = false }
    }
    @Test fun `paused full player suspends work without occupying its dispatcher and trailer permits first load`() = runTest {
        val trailer = PlayerSessionTracker.openTrailer()
        try {
            BackgroundWork.awaitIdle()
            BackgroundWork.importCheckpoint()
            val player = PlayerSessionTracker.open()
            try {
                var resumed = false
                val work = launch { BackgroundWork.awaitIdle(); resumed = true }
                runCurrent()
                var otherWork = false
                launch { otherWork = true }
                runCurrent()
                assertTrue(otherWork)
                assertFalse(resumed)
                try { BackgroundWork.importCheckpoint(); fail("Expected a safe producer checkpoint") }
                catch (_: BackgroundWork.PlaybackDeferred) { }
                player.close()
                work.join()
                assertTrue(resumed)
            } finally { player.close() }
        } finally { trailer.close() }
    }

    @Test fun `guide writer resumes two seconds after the last navigation`() = runTest {
        val gate = NavigationPause { testScheduler.currentTime * 1_000_000 }
        gate.onNavigation()
        var resumed = false
        launch { gate.awaitIdle(); resumed = true }
        advanceTimeBy(1500)
        gate.onNavigation()
        advanceTimeBy(1999)
        runCurrent()
        assertFalse(resumed)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(resumed)
    }
}
