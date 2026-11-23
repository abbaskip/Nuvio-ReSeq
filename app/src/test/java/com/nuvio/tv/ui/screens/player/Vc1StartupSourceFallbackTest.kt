package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Vc1StartupSourceFallbackTest {
    @Test fun earlyFailuresIncludeTunneledReadyBeforeConfirmedPlayback() {
        assertTrue(vc1StartupSourceFallbackAllowed("a", "a", false, false, false))
        assertTrue(vc1StartupSourceFallbackAllowed("a", "a", false, true, true))
        assertFalse(vc1StartupSourceFallbackAllowed("a", "a", false, true, false))
    }

    @Test fun manualSourcesAndEnginePreferenceKeepTheirRecoveryPath() {
        assertFalse(vc1StartupSourceFallbackAllowed("a", null, false, false, false))
        assertFalse(vc1StartupSourceFallbackAllowed("b", "a", false, true, true))
        assertFalse(vc1StartupSourceFallbackAllowed("", "", false, false, false))
        assertFalse(vc1StartupSourceFallbackAllowed("a", "a", true, true, true))
    }
}
