package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.core.player.PlayerSessionTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Mounted once at the Activity root, independent of the status indicator and current screen.
 */
@Composable
internal fun LiveTvRootPlaybackEffects(playback: LiveTvPlaybackController, route: String) {
    PauseLiveTvInBackground(playback)
    LaunchedEffect(route) {
        releaseLiveTvForRoute(route, playback::releaseNow)
    }
    LaunchedEffect(playback) {
        releaseLiveTvWhenPlaybackStarts(PlayerSessionTracker.playbackActive, playback::releaseNow)
    }
}

internal fun releaseLiveTvForRoute(route: String, release: () -> Unit) {
    if (route.startsWith("player/") || route.startsWith("stream/")) release()
}

internal suspend fun releaseLiveTvWhenPlaybackStarts(active: Flow<Boolean>, release: () -> Unit) {
    active.distinctUntilChanged().collect { if (it) release() }
}
