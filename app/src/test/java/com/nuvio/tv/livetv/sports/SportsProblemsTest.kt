package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SportsProblemsTest {
    @Test fun successfulLiveScoresCannotHideTheFailedCatalogueOrItsRetryAction() = runTest {
        val problems = SportsProblems()
        problems.catalogue.value = "Sports schedule unavailable. Try refreshing."
        problems.scores.value = "Live scores unavailable"
        problems.scores.value = null
        assertEquals("Sports schedule unavailable. Try refreshing.", problems.message.first())
        problems.catalogue.value = null
        assertNull(problems.message.first())
        problems.scores.value = "Live scores unavailable"
        assertEquals("Live scores unavailable", problems.message.first())
    }
}
