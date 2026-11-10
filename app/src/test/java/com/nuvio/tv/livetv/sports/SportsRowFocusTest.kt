package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test

class SportsRowFocusTest {
    private fun row(vararg ids: String, start: Int = 0) = SportsRow("up", "Upcoming", SportsRow.Kind.UPCOMING,
        events = ids.map { SportsEvent(it, "cricket/19430", 0, true, GameState.PRE, it, null, null, null, null) },
        startIndex = start)

    @Test fun refreshFromEightCardsToOneIgnoresTheOldLayoutIndex() {
        val old = row(*(0..7).map { "event-$it" }.toTypedArray())
        assertEquals("up|event-7", old.restoreFocusKey(listOf(7), "event-7"))
        val refreshed = row("event-0")
        assertNull(refreshed.restoreFocusKey(listOf(7), "event-7"))
        assertNull(refreshed.focusKey(7))
        assertEquals("up|event-0", refreshed.restoreFocusKey(listOf(7, 0), "event-7"))
    }

    @Test fun reorderedRowsRestoreByEventIdentityThenUseTheVisibleStart() {
        val refreshed = row("third", "second", "first", start = 2)
        assertEquals("up|second", refreshed.restoreFocusKey(listOf(0, 1, 2), "second"))
        assertEquals("up|first", refreshed.restoreFocusKey(listOf(0, 2), "removed"))
        assertEquals("up|third", refreshed.restoreFocusKey(listOf(0, 8), "removed"))
    }

    @Test fun emptyAndStaleLayoutsLeaveFocusToCompose() {
        assertNull(row().restoreFocusKey(listOf(0, 7), "removed"))
        assertNull(row("new").restoreFocusKey(emptyList(), "new"))
        assertNull(row("new").focusKey(-1))
    }
}
