package com.nuvio.tv.livetv.ui

import com.nuvio.tv.core.player.PlayerSessionTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvRootPlaybackEffectsTest {
    @Test fun `movie episode On Demand stream lists and player routes release Live TV`() {
        val released = mutableListOf<String>()
        listOf("stream/movie", "stream/series", "stream/ondemand", "player/movie", "player/series",
            "home", "sports", "livetv", "settings").forEach { route ->
            releaseLiveTvForRoute(route) { released += route }
        }
        assertEquals(listOf("stream/movie", "stream/series", "stream/ondemand", "player/movie", "player/series"), released)
    }

    @Test fun `trailer session releases connection even when READY and paused without altering reminders`() = runTest {
        var releases = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            releaseLiveTvWhenPlaybackStarts(PlayerSessionTracker.playbackActive) { releases++ }
        }
        val trailer = PlayerSessionTracker.openTrailer { false }
        try {
            assertEquals(1, releases)
            // Paused READY has no loading pressure, but owns playback resources.
            releaseLiveTvForRoute("sports") { releases++ }
            assertEquals(1, releases)
        } finally { trailer.close() }
        val player = PlayerSessionTracker.open { false }
        try { assertEquals(2, releases) } finally { player.close() }
        assertEquals(2, releases)
    }
}
