package com.nuvio.tv.ui.screens.player

internal fun vc1StartupSourceFallbackAllowed(
    url: String,
    autoSelectedUrl: String?,
    engineSwitchEnabled: Boolean,
    renderedFirstFrame: Boolean,
    tunneledFrameAssumed: Boolean
): Boolean = url.isNotBlank() && url == autoSelectedUrl && !engineSwitchEnabled &&
    (!renderedFirstFrame || tunneledFrameAssumed)
