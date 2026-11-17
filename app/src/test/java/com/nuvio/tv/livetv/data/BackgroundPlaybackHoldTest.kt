package com.nuvio.tv.livetv.data

import com.nuvio.tv.core.player.PlayerSessionTracker
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BackgroundPlaybackHoldTest {
    @Test fun `trailer permits first loads while handoff to full playback holds background work`() = runTest {
        val preview = PlayerSessionTracker.openTrailer()
        try {
            assertFalse(PlayerSessionTracker.isSessionOpen.first())
            assertFalse(BackgroundWork.isNuvioPlaying)
            assertFalse(BackgroundWork.nuvioPlaying.first())
            BackgroundWork.awaitIdle()
            val movie = PlayerSessionTracker.open()
            val worker = async(start = CoroutineStart.UNDISPATCHED) { BackgroundWork.awaitIdle() }
            try {
                preview.close()
                assertTrue(BackgroundWork.isNuvioPlaying)
                assertFalse(worker.isCompleted)
            } finally { movie.close() }
            worker.await()
            assertFalse(BackgroundWork.isNuvioPlaying)
        } finally { preview.close() }
    }

    @Test fun `real player sessions hold work without any navigation overlay`() = runTest {
        val previous = PlayerSessionTracker.open()
        val next = PlayerSessionTracker.open()
        try {
            assertTrue(BackgroundWork.isNuvioPlaying)
            assertTrue(BackgroundWork.nuvioPlaying.first())
            val worker = async(start = CoroutineStart.UNDISPATCHED) { BackgroundWork.awaitIdle() }
            assertFalse(worker.isCompleted)
            previous.close()
            assertTrue(BackgroundWork.isNuvioPlaying)
            assertFalse(worker.isCompleted)
            next.close()
            worker.await()
            assertFalse(BackgroundWork.isNuvioPlaying)
        } finally { previous.close(); next.close() }
    }

    @Test fun `synchronous import checkpoint releases its worker during playback and permits retry after exit`() {
        val executor = Executors.newSingleThreadExecutor()
        val session = PlayerSessionTracker.open()
        try {
            val worker = executor.submit<Boolean> {
                try { BackgroundWork.importCheckpoint(); true }
                catch (_: BackgroundWork.PlaybackDeferred) { false }
            }
            assertFalse(worker.get(5, TimeUnit.SECONDS))
            assertTrue(executor.submit<Boolean> { true }.get(5, TimeUnit.SECONDS))
            session.close()
            assertTrue(executor.submit<Boolean> { BackgroundWork.importCheckpoint(); true }.get(5, TimeUnit.SECONDS))
        } finally { session.close(); executor.shutdownNow() }
    }
}
