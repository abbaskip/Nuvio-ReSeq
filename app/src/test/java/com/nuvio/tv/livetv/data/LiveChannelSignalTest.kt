package com.nuvio.tv.livetv.data

import org.junit.Assert.*
import org.junit.Test

class LiveChannelSignalTest {
    @Test fun `late single detach after handoff keeps guide and Sports in playback mode`() {
        val values = mutableListOf<Boolean>()
        val signal = LiveChannelSignal(values::add)
        signal.single(true)
        signal.single(false) // releaseNow before pane creation
        signal.multiview(true)
        signal.single(false) // guide detach's delayed release
        assertTrue(values.last())
        signal.multiview(false)
        assertFalse(values.last())
        signal.single(true) // guide preview after exit
        signal.multiview(false) // repeated close cannot clear the preview
        assertTrue(values.last())
        signal.single(false)
        assertFalse(values.last())
    }
}
