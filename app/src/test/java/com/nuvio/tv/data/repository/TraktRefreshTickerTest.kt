package com.nuvio.tv.data.repository

import com.nuvio.tv.core.player.PlayerSessionTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TraktRefreshTickerTest {
    @Test fun `player sessions suppress timed ticks while explicit refreshes still arrive`() = runTest {
        val explicit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var calls = 0
        backgroundScope.launch { traktRefreshEvents(explicit) { 60_000L }.collect { calls++ } }
        runCurrent()
        assertEquals(1, calls)
        val player = PlayerSessionTracker.open()
        try {
            advanceTimeBy(180_001)
            assertEquals(1, calls)
            explicit.emit(Unit)
            runCurrent()
            assertEquals(2, calls)
        } finally {
            player.close()
        }
        advanceTimeBy(60_000)
        assertEquals(3, calls)
    }
}
