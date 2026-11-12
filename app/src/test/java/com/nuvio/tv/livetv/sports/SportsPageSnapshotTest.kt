package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class SportsPageSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-11-12T00:30:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val future = SportsEvent("nba", "basketball/nba", now + 4 * hour, true, GameState.PRE,
        "Heat at Lakers", null, "Scheduled", null, null)
    private val channel = LiveChannel("fox", "p", "Fixture", "Fox Sports", null, null, null, "sports", "Sports", 1, "https://fixture.invalid")
    private fun save(file: File, list: List<SportsEvent>, at: Long = now, matches: Map<String, List<ChannelMatch>> = emptyMap()) = runBlocking {
        SportsPageSnapshot.write(file, list, matches, at, now - 7 * day, now + 8 * day) { true }
    }

    @Test fun `future fixtures survive old snapshot ages and local midnight with saved channels`() = runBlocking {
        val file = temporary.newFile()
        val match = ChannelMatch(channel, 95, "Channel name", null, "HD", false)
        save(file, listOf(future), now - 6 * day, mapOf(future.id to listOf(match)))
        val page = requireNotNull(SportsPageSnapshot.read(file, now))
        assertEquals(future.startMs, page.events.single().startMs)
        assertFalse(page.events.single().awaitingUpdate)
        assertEquals(channel, page.matches(mapOf(channel.key to channel))[future.id]!!.single().channel)
    }

    @Test fun `scheduled fixture crossing kickoff since save becomes Updating even when saved recently`() {
        val ev = future.copy(startMs = now - 60_000)
        val shown = requireNotNull(SportsPageSnapshot.display(ev, now - 120_000, now))
        assertTrue(shown.awaitingUpdate)
        assertEquals("Updating…", cornerStatus(shown, false, false))
        assertFalse(shown.hasScore)
    }

    @Test fun `live and stumps scores expire per item after fifteen minutes without freshening from the snapshot`() = runBlocking {
        val side = Side(TeamInfo("ca/test", "pak", "Pakistan", "Pakistan", "PAK", null, null, null, null), "100/2", null, false, true)
        for (state in listOf(GameState.LIVE, GameState.OFF)) {
            val ev = future.copy(leagueId = "ca/test", startMs = now - day, endMs = now + day,
                state = state, status = if (state == GameState.OFF) "Stumps" else "Day 2", home = side, away = side,
                updatedAt = now - 16 * 60_000)
            val file = File(temporary.root, state.name)
            save(file, listOf(ev))
            val shown = requireNotNull(SportsPageSnapshot.read(file, now)).events.single()
            assertTrue(shown.awaitingUpdate)
            assertEquals(GameState.PRE, shown.state)
            assertNull(shown.home!!.score)
            assertTrue(shown.home!!.periods.isEmpty())
            assertFalse(shown.hasScore)
            assertFalse(requireNotNull(SportsPageSnapshot.display(ev, now - 15 * 60_000, now)).awaitingUpdate)
        }
    }

    @Test fun `finished fixtures use current results window and expired or corrupt snapshots are ignored`() = runBlocking {
        assertNull(SportsPageSnapshot.display(future.copy(state = GameState.POST, startMs = now - 8 * day), now - 8 * day, now))
        assertEquals(GameState.POST, SportsPageSnapshot.display(future.copy(state = GameState.POST, startMs = now - day), now - day, now)!!.state)
        val file = temporary.newFile()
        save(file, listOf(future), now - 8 * day)
        assertNull(SportsPageSnapshot.read(file, now))
        file.writeText("{broken")
        assertNull(SportsPageSnapshot.read(file, now))
        file.writeText("x".repeat(SportsPageSnapshot.MAX_BYTES.toInt() + 1))
        assertNull(SportsPageSnapshot.read(file, now))
    }

    @Test fun `large fixture is bounded to 512 events eight matches each and two MiB`() = runBlocking {
        val file = temporary.newFile()
        val events = (1..2001).map { future.copy(id = "event-$it") }
        val candidates = (1..20).map { ChannelMatch(channel.copy(key = "fox-$it"), 95, "Channel name", null, null, false) }
        save(file, events, matches = events.associate { it.id to candidates })
        val page = requireNotNull(SportsPageSnapshot.read(file, now))
        assertEquals(SportsPageSnapshot.MAX_EVENTS, page.items.size)
        assertTrue(page.items.all { it.matches.size == SportsPageSnapshot.MAX_MATCHES })
        assertTrue(file.length() <= SportsPageSnapshot.MAX_BYTES)
        // A second write must replace the prior complete file on Windows as well as Android.
        save(file, listOf(future))
        assertEquals(listOf(future.id), SportsPageSnapshot.read(file, now)!!.events.map { it.id })
    }
}
