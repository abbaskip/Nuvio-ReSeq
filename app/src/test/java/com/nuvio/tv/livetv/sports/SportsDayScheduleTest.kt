package com.nuvio.tv.livetv.sports

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SportsDayScheduleTest {
    private fun dates(day: String, tvZone: String, providerZone: String): List<String> {
        val date = LocalDate.parse(day)
        val zone = ZoneId.of(tvZone)
        return SportsDaySchedule.dates(date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), ZoneId.of(providerZone), "yyyyMMdd")
    }

    @Test fun `Australian date requests both Eastern calendar days`() {
        assertEquals(listOf("20261109", "20261110"), dates("2026-11-10", "Australia/Sydney", "America/New_York"))
    }

    @Test fun `UTC cricket dates cover the selected local day`() {
        assertEquals(listOf("20261109", "20261110"), dates("2026-11-10", "Australia/Sydney", "UTC"))
    }

    @Test fun `midnight end is exclusive and daylight saving days use calendar boundaries`() {
        assertEquals(listOf("20261110"), dates("2026-11-10", "UTC", "UTC"))
        for (date in listOf("2026-03-08", "2026-11-01")) {
            assertEquals(listOf(date.replace("-", "")), dates(date, "America/New_York", "America/New_York"))
        }
        assertEquals(listOf("20261003", "20261004"), dates("2026-10-04", "Australia/Sydney", "UTC"))
    }

    @Test fun `selected date result replaces stale schedule without losing other events`() {
        val old = SportsEvent("game", "basketball/nba", 1L, true, GameState.PRE, "Game", null, null, null, null)
        val result = old.copy(state = GameState.POST, updatedAt = 200L)
        val other = old.copy(id = "other")
        val merged = SportsDaySchedule.freshest(listOf(old, other, result, old.copy(updatedAt = 100L)))
        assertEquals(2, merged.size)
        assertEquals(result, merged.first { it.id == "game" })
        assertTrue(merged.contains(other))
    }
}
