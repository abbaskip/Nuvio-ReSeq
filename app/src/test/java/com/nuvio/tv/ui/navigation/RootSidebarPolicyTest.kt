package com.nuvio.tv.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSidebarPolicyTest {
    @Test fun `a retained fullscreen flag cannot disable Home or general navigation`() {
        val roots = rootSidebarRoutes(discoverInSidebar = true, livePlaybackFullscreen = true)
        for (screen in listOf(Screen.Home, Screen.OnDemand, Screen.Search, Screen.Library, Screen.Settings, Screen.Discover)) {
            assertTrue("${screen.route} must still have its sidebar and Back handler", screen.route in roots)
        }
        assertFalse(Screen.LiveTv.route in roots)
        assertFalse(Screen.Sports.route in roots)
    }

    @Test fun `returning to preview restores both playback root sidebars`() {
        val roots = rootSidebarRoutes(discoverInSidebar = true, livePlaybackFullscreen = false)
        assertTrue(Screen.LiveTv.route in roots)
        assertTrue(Screen.Sports.route in roots)
        assertTrue(Screen.Home.route in roots)
    }

    @Test fun `hiding Discover does not remove the other root menus`() {
        for (fullscreen in listOf(false, true)) {
            val roots = rootSidebarRoutes(discoverInSidebar = false, livePlaybackFullscreen = fullscreen)
            assertFalse(Screen.Discover.route in roots)
            assertTrue(Screen.Home.route in roots)
            assertTrue(Screen.Settings.route in roots)
        }
    }
}
