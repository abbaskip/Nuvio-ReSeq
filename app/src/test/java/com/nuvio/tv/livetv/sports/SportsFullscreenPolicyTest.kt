package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.player.LivePlaybackState
import org.junit.Assert.*
import org.junit.Test

class SportsFullscreenPolicyTest {
    @Test fun `selecting the current channel expands during playback buffering and an intentional pause`() {
        for (state in listOf(
            LivePlaybackState(channelKey = "channel", isPlaying = true),
            LivePlaybackState(channelKey = "channel", isBuffering = true),
            LivePlaybackState(channelKey = "channel", userPaused = true)
        )) assertTrue(canExpandSportsPreview(state, "channel"))
    }

    @Test fun `another channel replay or failed stream still needs its playback action`() {
        assertFalse(canExpandSportsPreview(LivePlaybackState(), "channel"))
        assertFalse(canExpandSportsPreview(LivePlaybackState(channelKey = "other", isPlaying = true), "channel"))
        assertFalse(canExpandSportsPreview(LivePlaybackState(channelKey = "channel", catchupTitle = "Replay"), "channel"))
        assertFalse(canExpandSportsPreview(LivePlaybackState(channelKey = "channel", error = "Unavailable"), "channel"))
    }
}
