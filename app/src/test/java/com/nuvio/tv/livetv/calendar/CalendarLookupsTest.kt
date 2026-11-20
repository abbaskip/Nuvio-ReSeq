package com.nuvio.tv.livetv.calendar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarLookupsTest {
    private val titles = (1..7).map { CalendarTitle("tt" + it, "Show " + it, false, null, null, it.toLong()) }
    private val page = CalendarPage(region = "AU", zone = "Australia/Sydney")

    @Test fun readyTitlesReachTheScreenWhileGuideAndFirstProviderAreStillBusy() = runTest {
        val guide = CompletableDeferred<CalendarPage>()
        val slow = CompletableDeferred<Unit>()
        val results = mutableListOf<CalendarLookup>()
        var active = 0
        var peak = 0
        val job = launch {
            calendarLookups({ emptyList() }, { guide.await() }, { titles }) { title ->
                active++
                peak = maxOf(peak, active)
                try { if (title == titles.first()) slow.await(); emptyList() } finally { active-- }
            }.collect(results::add)
        }
        runCurrent()
        assertEquals(titles.drop(1), results.filterIsInstance<CalendarLookup.Release>().map { it.title })
        assertEquals(2, peak)
        guide.complete(page)
        runCurrent()
        assertEquals(CalendarLookup.Guide(page), results.last())
        slow.complete(Unit)
        job.join()
        assertEquals(titles.first(), (results.last() as CalendarLookup.Release).title)
    }

    @Test fun leavingCancelsBothTitleRequestsAndTheGuideWithoutStartingTheRest() = runTest {
        var started = 0
        var stopped = 0
        suspend fun block(): Nothing {
            started++
            try { awaitCancellation() } finally { stopped++ }
        }
        val job = launch { calendarLookups({ block() }, { error("Guide needs scheduled shows") }, { titles }, { block() }).collect() }
        runCurrent()
        assertEquals(3, started)
        job.cancelAndJoin()
        assertEquals(3, stopped)
        assertEquals(3, started)
    }
}
