package com.nuvio.tv.ui.navigation

/** A shared playback flag must never remove navigation from unrelated root screens. */
internal fun rootSidebarRoutes(discoverInSidebar: Boolean, livePlaybackFullscreen: Boolean): Set<String> = buildSet {
    add(Screen.Home.route)
    add(Screen.Search.route)
    add(Screen.Library.route)
    add(Screen.Settings.route)
    if (discoverInSidebar) add(Screen.Discover.route)
    if (!livePlaybackFullscreen) {
        add(Screen.LiveTv.route)
        add(Screen.Sports.route)
    }
}
