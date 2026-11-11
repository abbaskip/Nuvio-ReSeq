package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class SportsPausedCricketMatchingTest {
    private val hour = 3_600_000L
    private val now = 1_794_477_600_000L
    private val channel = LiveChannel("fox505", "p", "Fixture", "Fox Sports 505", null, null, null,
        "sport", "Sports", 505, "https://fixture.invalid/505")
    private fun event(league: String = "ca/shield", state: GameState = GameState.OFF, status: String = "Stumps"): SportsEvent {
        fun side(id: String, name: String, abbr: String, home: Boolean) = Side(
            TeamInfo(league, id, name, name, abbr, null, null, null, null), "100/2", null, false, home)
        return SportsEvent("shield", league, now - 48 * hour, true, state, "Victoria v Western Australia", null,
            status, side("wa", "Western Australia", "WA", false), side("vic", "Victoria", "VIC", true), endMs = now + 48 * hour)
    }
    private fun matches(ev: SportsEvent, programme: EpgProgram, replay: Boolean = false, picked: Boolean = false): List<ChannelMatch> {
        val db = mockk<EpgDatabase>()
        every { db.findByWords(any(), any(), any(), any(), any(), any()) } answers {
            if (programme.startMs in firstArg<Long>() until secondArg<Long>() && programme.stopMs > thirdArg<Long>())
                listOf(channel.key to programme) else emptyList()
        }
        return SportsMatcher(db).match(SportsMatcher.Input(listOf(ev), listOf(channel), emptyMap(), 0,
            SportsSettings(region = "AU", eventChannels = if (picked) mapOf(ev.id to "${channel.key}|${ev.startMs}") else emptyMap()),
            now, replay = replay)).matches[ev.id].orEmpty()
    }

    @Test fun `background and opened game find the next Shield session without changing stumps`() {
        val ev = event()
        val tomorrow = EpgProgram(now + 18 * hour, now + 21 * hour, "Sheffield Shield: VIC v WA Day 2 P1")
        for (replay in listOf(false, true)) {
            assertTrue(matches(ev, tomorrow, replay).any { it.sure && it.program == tomorrow })
        }
        assertEquals(GameState.OFF, ev.state)
        assertEquals("Stumps", ev.status)
        assertTrue(matches(ev, tomorrow, picked = true).any { it.score == 200 })
        assertTrue(matches(ev.copy(endMs = null), tomorrow).any { it.sure && it.program == tomorrow })
        for (status in listOf("Rain Delay", "BadLight", "Suspended")) {
            assertTrue(status, matches(ev.copy(status = status), tomorrow).any { it.sure && it.program == tomorrow })
        }
    }

    @Test fun `cancelled postponed expired and wrong opponent matches stay excluded`() {
        val ev = event()
        val p = EpgProgram(now + 18 * hour, now + 21 * hour, "Sheffield Shield: VIC v WA Day 2 P1")
        for (excluded in listOf(ev.copy(status = "Cancelled"), ev.copy(status = "Postponed"), ev.copy(endMs = now))) {
            assertTrue(matches(excluded, p, picked = true).isEmpty())
        }
        assertTrue(matches(ev, p.copy(startMs = now + 37 * hour, stopMs = now + 40 * hour)).none { it.program != null })
        assertTrue(matches(ev, p.copy(title = "Sheffield Shield: NSW v Queensland Day 2")).none { it.sure && it.program != null })
        assertTrue(matches(ev.copy(endMs = now + 12 * hour), p).none { it.program != null })
    }

    @Test fun `live Shield and Tests retain current session matching and completed games do not gain future sessions`() {
        val p = EpgProgram(now - 5 * hour, now + hour, "Sheffield Shield: VIC v WA Day 3 live")
        assertTrue(matches(event(state = GameState.LIVE, status = "InPlay"), p).any { it.sure && it.program == p })
        val test = event("cricket/19430", GameState.LIVE, "InPlay")
        assertTrue(matches(test, p.copy(title = "Test Cricket: Victoria v Western Australia live")).any { it.program != null })
        val final = event(state = GameState.POST, status = "Final")
        assertTrue(matches(final, p.copy(startMs = now + 18 * hour, stopMs = now + 21 * hour)).isEmpty())
    }
}
