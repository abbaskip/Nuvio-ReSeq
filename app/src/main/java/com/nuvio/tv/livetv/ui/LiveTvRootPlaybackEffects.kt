package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.tv.livetv.player.LiveTvPlaybackController

/**
 * Deliberately NOT mounted. These previously lived in the unused loading overlay.
 * Enabling app-wide playback policy needs a separate decision from displaying update status.
 */
@Suppress("unused")
@Composable
internal fun LiveTvRootPlaybackEffects(playback: LiveTvPlaybackController, route: String) {
    PauseLiveTvInBackground(playback)
    LaunchedEffect(route) {
        if (route.startsWith("player/") || route.startsWith("stream/")) playback.releaseNow()
    }
}
