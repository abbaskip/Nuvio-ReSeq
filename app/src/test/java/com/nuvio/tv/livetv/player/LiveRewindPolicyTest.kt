package com.nuvio.tv.livetv.player

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class LiveRewindPolicyTest {
    @Test fun `a startup window spans several local playlist refreshes`() {
        assertTrue(REWIND_STARTUP_MS >= REWIND_TARGET_SECONDS * 2_000L)
        assertEquals(6_000L, rewindPlaylistDurationMs("#EXTM3U\n#EXTINF:2.001,\na.ts\n#EXTINF:1.999,\nb.ts\n#EXTINF:2.000,\nc.ts\n"))
        assertEquals(0L, rewindPlaylistDurationMs("#EXTM3U\n#EXTINF:NaN,\nx.ts\n#EXTINF:-2,\ny.ts\n"))
    }

    @Test fun `loading paused suppressed and audio-only playback cannot trigger a surface restart`() {
        assertFalse(shouldRecoverLiveSurface(true, Player.STATE_BUFFERING, false, true, 0, true))
        assertFalse(shouldRecoverLiveSurface(true, Player.STATE_READY, false, false, 0, true))
        assertFalse(shouldRecoverLiveSurface(true, Player.STATE_READY, false, true, 1, true))
        assertFalse(shouldRecoverLiveSurface(true, Player.STATE_READY, true, true, 0, false))
        assertFalse(shouldRecoverLiveSurface(false, Player.STATE_READY, true, true, 0, true))
        assertTrue(shouldRecoverLiveSurface(true, Player.STATE_READY, true, true, 0, true))
    }
}
