package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class ThermalHeadroomPollerTest {
    @Test fun transientFailuresBackOffAndRecoverWithoutPermanentShutdown() {
        val sampler = ThermalHeadroomPoller()
        var calls = 0
        fun read(): Float { calls++; return if (calls <= 4) Float.NaN else 0.7f }
        for (at in 0L..200_000L step 1_000L) sampler.sample(at, ::read)
        assertEquals(0.7f, sampler.sample(200_001L, ::read))
        assertTrue(calls in 5..8)
    }
    @Test fun successfulReadIsCachedForAtLeastTenSeconds() {
        val sampler = ThermalHeadroomPoller()
        var calls = 0
        repeat(10) { sampler.sample(it * 1_000L) { calls++; 0.4f } }
        assertEquals(1, calls)
        sampler.sample(10_000L) { calls++; 0.5f }
        assertEquals(2, calls)
    }
}
