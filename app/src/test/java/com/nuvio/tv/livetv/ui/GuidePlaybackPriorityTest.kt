package com.nuvio.tv.livetv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidePlaybackPriorityTest {
    @Test fun heldNavigationIsLimitedDuringPlaybackWithoutPostponingTheNextStep() {
        val gate = GuideNavigationRepeatGate()
        assertFalse(gate.shouldConsume(20, false, 1000, true))
        assertTrue(gate.shouldConsume(20, true, 1030, true))
        assertTrue(gate.shouldConsume(20, true, 1099, true))
        assertFalse(gate.shouldConsume(20, true, 1100, true))
        assertTrue(gate.shouldConsume(20, true, 1150, true))
        assertFalse(gate.shouldConsume(20, true, 1200, true))
    }

    @Test fun separatePressesAndDirectionChangesAlwaysRespondImmediately() {
        val gate = GuideNavigationRepeatGate()
        assertFalse(gate.shouldConsume(20, false, 1000, true))
        assertFalse(gate.shouldConsume(20, false, 1010, true))
        assertFalse(gate.shouldConsume(19, true, 1020, true))
    }

    @Test fun pausedOrStoppedPlaybackDoesNotLimitHeldNavigation() {
        val gate = GuideNavigationRepeatGate()
        assertFalse(gate.shouldConsume(20, false, 1000, true))
        assertFalse(gate.shouldConsume(20, true, 1010, false))
        assertFalse(gate.shouldConsume(20, true, 1020, false))
    }
}
