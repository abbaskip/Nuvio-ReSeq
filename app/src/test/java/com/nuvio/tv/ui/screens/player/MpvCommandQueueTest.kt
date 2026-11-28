package com.nuvio.tv.ui.screens.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MpvCommandQueueTest {
    @Test fun blockedEngineDoesNotDelayCloseOrCommandsForReplacement() {
        val old = MpvCommandQueue()
        val fresh = MpvCommandQueue()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val replacementPlayed = CountDownLatch(1)
        val staleCommands = AtomicInteger()
        try {
            old.execute {
                entered.countDown()
                unblock.await(5, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            old.execute { staleCommands.incrementAndGet() }
            old.close { closed.countDown() }
            old.execute { staleCommands.incrementAndGet() }
            fresh.execute { replacementPlayed.countDown() }
            assertTrue(replacementPlayed.await(2, TimeUnit.SECONDS))
            assertEquals(1L, closed.count)
            unblock.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(0, staleCommands.get())
        } finally {
            unblock.countDown()
            old.close {}
            fresh.close {}
        }
    }

    @Test fun unavailablePropertiesAreClearedAndReplacementStartsEmpty() {
        val changes = AtomicInteger()
        val state = MpvObservedProperties { changes.incrementAndGet() }
        state.eventProperty("idle-active", true)
        state.eventProperty("demuxer-cache-duration", 3.5)
        state.eventProperty("video-params/w", "3840")
        assertEquals(true, state.flag("idle-active"))
        assertEquals(3.5, state.double("demuxer-cache-duration")!!, 0.001)
        assertEquals(3840, state.int("video-params/w"))
        state.eventProperty("video-params/w")
        assertNull(state.int("video-params/w"))
        assertEquals(2, changes.get())
        assertNull(MpvObservedProperties {}.flag("idle-active"))
    }
}
