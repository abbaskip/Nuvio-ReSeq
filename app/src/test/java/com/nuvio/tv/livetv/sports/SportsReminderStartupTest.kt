package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsReminderStartupTest {
    private val kickoff = 1_000_000L
    private val fixture = SportsEvent("fixture", "league", kickoff, true, GameState.PRE, "Final", null, null, null, null)
    private val settings = SportsSettings()
    private val followed = SportsFollows(leagues = setOf("league"))

    @Test fun followedFixturesUseTheEarlyPollingWindowAndSkipGuideRepeats() {
        fun pick(event: SportsEvent, now: Long) =
            nextSportsReminder(listOf(event), followed, settings, emptySet(), now, 60_000)
        assertEquals(fixture, pick(fixture, kickoff - 90_000))
        assertNull(pick(fixture, kickoff - 90_001))
        assertNull(pick(fixture.copy(source = EventSource.GUIDE), kickoff))
        assertNull(pick(fixture.copy(hasTime = false), kickoff))
        assertNull(pick(fixture, kickoff + 600_000))
    }

    @Test fun explicitReminderSurvivesFeedRemovalAndIsShownOnce() {
        val reminder = SportsReminder("missing", "Saved fixture", kickoff)
        val follows = followed.copy(reminders = listOf(reminder))
        val disabled = settings.copy(remindMyTeams = false)
        fun pick(now: Long, seen: Set<String> = emptySet()) =
            nextSportsReminder(listOf(fixture), follows, disabled, seen, now, 60_000)
        assertNull(pick(kickoff - 60_001))
        assertEquals("Saved fixture", pick(kickoff - 60_000)?.title)
        assertNull(pick(kickoff, setOf("missing")))
    }

    @Test fun firstDeadlineIncludesTimeSpentWaitingForHome() {
        assertEquals(15_000L, firstSportsReminderWait(10_000L, 10_000L))
        assertEquals(13_900L, firstSportsReminderWait(10_000L, 11_100L))
        assertEquals(0L, firstSportsReminderWait(10_000L, 29_000L))
    }
}
