package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.EpgProgram
import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class GuideCatchupGapsTest {
    @Test fun gapsFollowTheClockAndRealProgrammeEdgesInsideTheArchiveWindow() {
        val hour = 3_600_000L
        val noon = Calendar.getInstance().apply { clear(); set(2026, Calendar.JANUARY, 15, 12, 0) }.timeInMillis
        val now = noon + hour / 2
        val show = EpgProgram(noon - hour - hour / 4, noon - hour / 2, "News")
        val result = withCatchupGaps(listOf(show), 1, now, 3)
        val gaps = result.filter { it.title.isEmpty() }
        assertEquals(listOf(
            (now - 3 * hour) to (noon - 2 * hour), (noon - 2 * hour) to show.startMs,
            show.stopMs to noon, noon to (noon + hour)
        ), gaps.map { it.startMs to it.stopMs })
        assertTrue(show in result)
        val archive = CatchupInfo("default", "https://example.test/rewind?start={utc}&duration={duration}", 1)
        val shortGap = gaps[2]
        assertTrue(CatchupUrlBuilder.isAvailable(archive, shortGap.startMs, now))
        assertEquals("https://example.test/rewind?start=${shortGap.startMs / 1000}&duration=1800",
            CatchupUrlBuilder.build("https://example.test/live", archive, shortGap.startMs, shortGap.stopMs, now))
        assertTrue(withCatchupGaps(emptyList(), null, now, 3).isEmpty())
        assertTrue(withCatchupGaps(emptyList(), 1, now, 0).isEmpty())
        assertEquals(now - 24 * hour, withCatchupGaps(emptyList(), 1, now, 72).first().startMs)
    }
}
