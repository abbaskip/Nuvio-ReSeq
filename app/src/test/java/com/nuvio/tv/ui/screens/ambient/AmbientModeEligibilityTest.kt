package com.nuvio.tv.ui.screens.ambient

import org.junit.Assert.*
import org.junit.Test

class AmbientModeEligibilityTest {
    @Test fun `IPTV preview and full screen cannot be covered by ambient mode`() {
        listOf("livetv", "livetv/player", "home").forEach { route ->
            assertFalse(ambientModeAllowed(route, false, false, true))
        }
    }
    @Test fun `ambient mode becomes eligible again when IPTV viewing ends`() {
        assertTrue(ambientModeAllowed("livetv", false, false, false))
        assertTrue(ambientModeAllowed("home", false, false, false))
    }
    @Test fun `movie playback trailers and auto next keep their existing exemptions`() {
        assertFalse(ambientModeAllowed("player/stream/title", false, false, false))
        assertFalse(ambientModeAllowed("home", false, true, false))
        assertFalse(ambientModeAllowed("home", true, false, false))
    }
}
