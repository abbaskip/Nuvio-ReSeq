package com.nuvio.tv.livetv.multiview

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MultiviewLoadControlTest {
    @Test fun `a full byte target can load missing audio until enough media is playable`() {
        val target = 256 * 1024
        val control = multiviewLoadControl(target)
        val id = PlayerId("pane")
        fun buffering(durationUs: Long) = LoadControl.Parameters(id, Timeline.EMPTY,
            MediaSource.MediaPeriodId("fixture"), 0, durationUs, 1f, true, true, C.TIME_UNSET, C.TIME_UNSET)
        control.onPrepared(id)
        control.onTracksSelected(buffering(0), TrackGroupArray.EMPTY, emptyArray())
        val allocator = control.allocator
        val allocations = List(target / allocator.individualAllocationLength) { allocator.allocate() }
        try {
            assertTrue(allocator.totalBytesAllocated >= target)
            assertTrue(control.shouldContinueLoading(buffering(200_000)))
            assertFalse(control.shouldContinueLoading(buffering(1_600_000)))
        } finally {
            allocations.forEach(allocator::release)
            control.onReleased(id)
        }
    }
}
