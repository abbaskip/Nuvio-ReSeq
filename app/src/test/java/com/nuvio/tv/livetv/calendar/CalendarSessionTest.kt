package com.nuvio.tv.livetv.calendar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSessionTest {
    private val now = 1_800_000_000_000L
    private val item = CalendarItem("show/1", "show", "Fixture Show", 20_000, CalendarKind.EPISODE)
    private val cached = CalendarPage(region = "AU", zone = "Australia/Sydney", checkedAt = now, items = listOf(item))

    @Test fun unopenedDestinationDoesNothingAndFreshVisitsOnlyRestore() = runTest {
        val playback = MutableStateFlow(false)
        val requests = MutableSharedFlow<Boolean>()
        var reads = 0
        var builds = 0
        val source = calendarSession(playback, requests, { now + CalendarPolicy.FRESH_MS - 1 },
            { reads++; cached }, { _, _, _ -> builds++; false })
        assertEquals(0, reads)
        repeat(2) {
            val states = mutableListOf<CalendarDisplay>()
            val session = launch { source.collect(states::add) }
            runCurrent()
            assertEquals(listOf(item), states.first().items)
            assertEquals(CalendarStatus.READY, states.last().status)
            session.cancelAndJoin()
        }
        assertEquals(2, reads)
        assertEquals(0, builds)
    }

    @Test fun stalePageIsVisibleDuringPlaybackAndLeavingCancelsAllPendingWork() = runTest {
        val playing = MutableStateFlow(true)
        val requests = MutableSharedFlow<Boolean>()
        var started = 0
        var stopped = 0
        val states = mutableListOf<CalendarDisplay>()
        val source = calendarSession(playing, requests, { now + CalendarPolicy.FRESH_MS },
            { cached }, { _, _, _ ->
                started++
                try { awaitCancellation() } finally { stopped++ }
            })
        val session = launch { source.collect(states::add) }
        runCurrent()
        assertEquals(0, started)
        assertEquals(listOf(item), states.last().items)
        assertEquals(CalendarStatus.PLAYBACK, states.last().status)
        playing.value = false
        runCurrent()
        assertEquals(1, started)
        playing.value = true
        runCurrent()
        assertEquals(1, stopped)
        playing.value = false
        runCurrent()
        session.cancelAndJoin()
        assertEquals(2, stopped)
        playing.value = true
        playing.value = false
        runCurrent()
        assertEquals(2, started)
    }

    @Test fun emptySuccessfulSnapshotAlsoSkipsRefreshAndManualRetryKeepsLastGoodRows() = runTest {
        val requests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        val states = mutableListOf<CalendarDisplay>()
        var calls = 0
        val session = launch {
            calendarSession(MutableStateFlow(false), requests, { now }, { cached.copy(items = emptyList()) },
                { _, _, publish ->
                    calls++
                    publish(cached)
                    throw java.io.IOException("offline")
                }).collect(states::add)
        }
        runCurrent()
        assertEquals(0, calls)
        requests.emit(true)
        runCurrent()
        assertEquals(listOf(item), states.last().items)
        assertEquals(CalendarStatus.RETRY, states.last().status)
        session.cancelAndJoin()
    }
}
