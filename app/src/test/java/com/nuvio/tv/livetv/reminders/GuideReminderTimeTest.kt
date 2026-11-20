package com.nuvio.tv.livetv.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideReminderTimeTest {
    @Test fun persistedReminderIsDueFromOneMinuteBeforeUntilTenMinutesAfterStart() {
        val start = 2_000_000L
        assertEquals(30_000L, reminderDelayMs(start, start - 90_000))
        assertEquals(0L, reminderDelayMs(start, start - 60_000))
        assertEquals(0L, reminderDelayMs(start, start + 50_000))
        assertEquals(0L, reminderDelayMs(start, start + 599_999))
        assertNull(reminderDelayMs(start, start + 600_000))
    }
}
