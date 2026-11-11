package com.nuvio.tv.livetv.sports

import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SportsMatchTraceTest {
    private val tag = "SportsMatchTrigger"
    @After fun reset() { ShadowLog.reset() }

    @Test fun `INFO disables diagnostics even in a debug build`() {
        ShadowLog.setLoggable(tag, Log.INFO)
        val trace = SportsMatchTrace()
        trace.observe("loading", true)
        trace.fixtures(listOf("match@100:1:2"))
        trace.begin()
        trace.end(true)
        assertTrue(ShadowLog.getLogsForTag(tag).isEmpty())
    }

    @Test fun `DEBUG enables trigger details and INFO stops further logging`() {
        ShadowLog.setLoggable(tag, Log.DEBUG)
        val trace = SportsMatchTrace()
        trace.observe("loading", true)
        trace.fixtures(listOf("match@100:1:2"))
        trace.begin()
        trace.end(true)
        val logs = ShadowLog.getLogsForTag(tag)
        assertEquals(2, logs.size)
        assertTrue(logs[0].msg.contains("loading=true"))
        assertTrue(logs[0].msg.contains("match:added"))
        assertTrue(logs[1].msg.contains("completed=true"))
        ShadowLog.setLoggable(tag, Log.INFO)
        trace.observe("loading", false)
        trace.begin()
        trace.end(false)
        assertEquals(2, ShadowLog.getLogsForTag(tag).size)
    }
}
