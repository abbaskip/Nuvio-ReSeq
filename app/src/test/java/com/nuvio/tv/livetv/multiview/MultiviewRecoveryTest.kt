package com.nuvio.tv.livetv.multiview

import org.junit.Assert.*
import org.junit.Test

class MultiviewRecoveryTest {
    @Test fun `a new process consumes only a guide return after an interrupted session`() {
        var persisted = false
        fun process() = MultiviewRecoveryFlag({ persisted }, { persisted = it })
        process().opened()
        val restarted = process()
        assertTrue(restarted.takeGuideReturn())
        assertFalse(restarted.takeGuideReturn())
        assertFalse(process().takeGuideReturn())
    }

    @Test fun `normal exit preserves the chosen startup page`() {
        var persisted = false
        val session = MultiviewRecoveryFlag({ persisted }, { persisted = it })
        session.opened()
        session.left()
        assertFalse(MultiviewRecoveryFlag({ persisted }, { persisted = it }).takeGuideReturn())
    }
}
