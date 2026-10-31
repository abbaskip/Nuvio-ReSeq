package com.nuvio.tv.ui.screens.ambient

internal fun ambientModeAllowed(
    route: String?,
    hasAutoNextOverlay: Boolean,
    trailerPlaying: Boolean,
    liveTvPlaying: Boolean
): Boolean = route?.startsWith("player/") != true &&
    !hasAutoNextOverlay && !trailerPlaying && !liveTvPlaying
