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
import java.util.concurrent.TimeoutException

class BackgroundPlaybackHoldTest {
    @Test fun `trailer holds background work across handoff to full playback`() = runTest {
        val preview = PlayerSessionTracker.openTrailer()
        try {
            assertFalse(PlayerSessionTracker.isSessionOpen.first())
            assertTrue(BackgroundWork.isNuvioPlaying)
            assertTrue(BackgroundWork.nuvioPlaying.first())
            val worker = async(start = CoroutineStart.UNDISPATCHED) { BackgroundWork.awaitIdle() }
            val movie = PlayerSessionTracker.open()
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

    @Test fun `synchronous import checkpoint resumes after playback exits`() {
        val executor = Executors.newSingleThreadExecutor()
        val session = PlayerSessionTracker.open()
        try {
            val worker = executor.submit<Boolean> {
                BackgroundWork.awaitPlaybackIdleBlocking()
                true
            }
            try {
                worker.get(100, TimeUnit.MILLISECONDS)
                fail("Import checkpoint returned during playback")
            } catch (_: TimeoutException) { /* Still held. */ }
            session.close()
            assertTrue(worker.get(5, TimeUnit.SECONDS))
        } finally { session.close(); executor.shutdownNow() }
    }
}
