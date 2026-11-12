package com.nuvio.tv.livetv.data

import org.junit.Assert.*
import org.junit.Test

class GuidePlaybackBudgetTest {
    @Test fun `playback uses stricter memory budget and deadline preserves safeguards`() {
        assertNull(GuidePlaybackBudget.message(false, false, 0.80, 0))
        assertEquals("Guide update waiting for memory", GuidePlaybackBudget.message(true, false, 0.80, 0))
        val deadline = GuidePlaybackBudget.FINISH_AFTER_MS
        assertEquals("Finishing guide update", GuidePlaybackBudget.message(true, false, 0.70, deadline))
        assertEquals("Guide update paused · buffering", GuidePlaybackBudget.message(true, true, 0.70, deadline))
        assertEquals("Guide update waiting for memory", GuidePlaybackBudget.message(true, true, 0.90, deadline))
        assertFalse(GuidePlaybackBudget.paused(GuidePlaybackBudget.message(true, false, 0.70, 0)))
    }
}
