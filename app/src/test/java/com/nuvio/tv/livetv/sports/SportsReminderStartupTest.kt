package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsReminderStartupTest {
    @Test fun firstDeadlineIncludesTimeSpentWaitingForHome() {
        assertEquals(15_000L, firstSportsReminderWait(10_000L, 10_000L))
        assertEquals(13_900L, firstSportsReminderWait(10_000L, 11_100L))
        assertEquals(0L, firstSportsReminderWait(10_000L, 29_000L))
    }
}
