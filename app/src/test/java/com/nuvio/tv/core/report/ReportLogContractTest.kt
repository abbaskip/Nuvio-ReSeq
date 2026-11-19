package com.nuvio.tv.core.report

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReportLogContractTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun threadtimeWarningsKeepExactlyTheReleasedReportText() {
        val prefix = " 11-20 23:01:02.003   34 100 "
        val withheld = "[message withheld: may contain account or viewing information]"
        for (level in listOf("W", "E", "F")) {
            val expected = "11-20 23:01:02.003 $level application: "
            assertEquals(expected + withheld,
                ReportRedactor.logLine("$prefix$level account-secret: https://host/live/user/password/1"))
            assertEquals(expected + "java.io.IOException",
                ReportRedactor.logLine("$prefix$level player.path: detail java.io.IOException: secret"))
        }
        assertNull(ReportRedactor.logLine("${prefix}I Tag: detail"))
        assertNull(ReportRedactor.logLine("--------- beginning of crash"))
    }

    @Test fun completedWritesSurviveWithoutClosingTheProcessDescriptor() {
        val dir = temp.newFolder()
        ReportRing(dir, segmentBytes = 12).use { ring ->
            ring.append("first")
            ring.append("second")
            assertEquals("first\nsecond\n", ring.read())
            ring.append("ä".repeat(10))
            assertArrayEquals(("ä".repeat(10) + "\n").toByteArray().copyOf(12),
                File(dir, "recent.txt").readBytes())
            assertEquals("second\n" + "ä".repeat(6), ReportRing(dir, 12).read())
        }
    }
}
