package com.nuvio.tv.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchedDatePickerTest {
    @Test fun dateOnlyAndDateTimeUseTheViewingTimeZone() {
        val zone = ZoneId.of("Australia/Sydney")
        val day = LocalDate.of(2025, 10, 5)
        assertEquals(Instant.parse("2025-10-04T14:00:00Z").toEpochMilli(), watchedInstant(day, null, zone))
        assertEquals(Instant.parse("2025-10-04T16:15:00Z").toEpochMilli(), watchedInstant(day, LocalTime.of(3, 15), zone))
        assertNull(watchedInstant(day, LocalTime.of(2, 15), zone))
    }
}
