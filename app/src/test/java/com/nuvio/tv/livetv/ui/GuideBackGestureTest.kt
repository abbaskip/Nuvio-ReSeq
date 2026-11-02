package com.nuvio.tv.livetv.ui

import org.junit.Assert.*
import org.junit.Test

class GuideBackGestureTest {
    @Test fun shortTapIsNotAHold() {
        assertFalse(isGuideBackHold(0, false))
        assertFalse(isGuideBackHold(120, false))
        assertFalse(isGuideBackHold(499, false))
    }
    @Test fun platformLongPressCanArriveBeforeTheFallbackTimeout() {
        assertTrue(isGuideBackHold(400, true))
    }
    @Test fun remoteWithoutRepeatsIsRecognizedOnRelease() {
        assertTrue(isGuideBackHold(500, false))
        assertTrue(isGuideBackHold(1200, false))
    }
}
