package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSwitchResumeTest {
    @Test fun playingPositionWinsOverAnOlderSavedResume() {
        assertEquals(71_000L, sourceSwitchResumePosition(false, true, 71_000L, 70_000L, null, 30_000L))
    }
    @Test fun aQueuedResumeSurvivesAStreamThatFailsBeforeTheFirstFrame() {
        assertEquals(41_000L, sourceSwitchResumePosition(false, false, 0L, 0L, 41_000L, 20_000L))
    }
    @Test fun restartingAtZeroDoesNotRestoreAnOlderPositionWhenSwitching() {
        assertEquals(0L, sourceSwitchResumePosition(false, true, 0L, 0L, null, 60_000L))
    }
    @Test fun savedPositionIsAvailableWithoutAStartedPlayer() {
        assertEquals(32_000L, sourceSwitchResumePosition(false, false, null, 0L, null, 32_000L))
    }
    @Test fun liveSourcesDoNotInheritAVodSeek() {
        assertNull(sourceSwitchResumePosition(true, true, 50_000L, 50_000L, null, 50_000L))
    }
}
