package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.player.LivePlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsPlaybackWorkTest {
    @Test fun `tuning cancels bulk work throughout preview and full screen until playback stops`() = runTest {
        val foreground = MutableStateFlow(true)
        val viewers = MutableStateFlow(1)
        val playback = MutableStateFlow(LivePlaybackState())
        val fullscreen = MutableStateFlow(false)
        val vod = MutableStateFlow(false)
        var starts = 0
        var stops = 0
        val job = launch {
            sportsBackgroundAllowed(foreground, playback, fullscreen, vod).collectLatest { allowed ->
                if (allowed) {
                    starts++
                    try { awaitCancellation() } finally { stops++ }
                }
            }
        }
        runCurrent()
        assertEquals(1, starts)
        playback.value = LivePlaybackState(channelKey = "sport", isBuffering = true)
        runCurrent()
        assertEquals(1, stops)
        fullscreen.value = true
        viewers.value = 0
        playback.value = LivePlaybackState(channelKey = "sport", isPlaying = true)
        runCurrent()
        assertEquals(1, starts)
        fullscreen.value = false // guide is visible, Sports remains off screen
        runCurrent()
        assertEquals(1, starts)
        viewers.value = 1
        runCurrent()
        assertEquals(1, starts)
        assertTrue(sportsBrowsingAllowed(foreground, viewers, playback, fullscreen, vod).first())
        playback.value = LivePlaybackState()
        runCurrent()
        assertEquals(2, starts)
        playback.value = playback.value.copy(videoWidth = 1920, videoHeight = 1080)
        runCurrent()
        assertEquals(2, starts)
        vod.value = true
        runCurrent()
        assertEquals(2, stops)
        vod.value = false
        runCurrent()
        assertEquals(3, starts)
        foreground.value = false
        runCurrent()
        assertEquals(3, stops)
        job.cancelAndJoin()
    }

    @Test fun `score polling yields throughout startup rebuffering reconnects and VOD`() = runTest {
        val foreground = MutableStateFlow(true)
        val playback = MutableStateFlow(LivePlaybackState(channelKey = "sport", isBuffering = true))
        val vod = MutableStateFlow(false)
        val allowed = sportsScoresAllowed(foreground, playback, vod)
        assertFalse(allowed.first())
        playback.value = playback.value.copy(isPlaying = true, isBuffering = false)
        assertTrue(allowed.first())
        playback.value = playback.value.copy(isPlaying = false, isBuffering = true)
        assertFalse(allowed.first())
        playback.value = playback.value.copy(isBuffering = false, reconnectAttempt = 1)
        assertFalse(allowed.first())
        playback.value = playback.value.copy(isPlaying = true)
        vod.value = true
        assertFalse(allowed.first())
        vod.value = false
        foreground.value = false
        assertFalse(allowed.first())
    }

    @Test fun `bounded refinement cancels with playback and leaves no observer after completion`() = runTest {
        val allowed = MutableStateFlow(true)
        var stopped = false
        val job = launch {
            allowed.whileAllowed {
                try { awaitCancellation() } finally { stopped = true }
            }
        }
        runCurrent()
        allowed.value = false
        runCurrent()
        assertTrue(stopped)
        assertTrue(job.isCompleted)
        allowed.value = true
        allowed.whileAllowed { }
        assertEquals(0, allowed.subscriptionCount.value)
    }

    @Test fun `cancellation never enters a cache fallback or retry`() = runTest {
        var fallback = false
        val job = launch {
            sportsCatching { awaitCancellation() }.getOrElse { fallback = true }
        }
        runCurrent()
        job.cancelAndJoin()
        assertFalse(fallback)
    }
}
