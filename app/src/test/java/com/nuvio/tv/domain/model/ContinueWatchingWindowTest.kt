package com.nuvio.tv.domain.model

import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingWindowTest {
    @Test fun finiteWindowsIncludeActivityOnTheBoundaryAndAllHistoryHasNoCutoff() {
        val now = 365 * 86_400_000L
        for (days in listOf(90, 180, 365)) {
            val cutoff = now - days * 86_400_000L
            assertEquals(cutoff, continueWatchingCutoff(days, now))
            val watched = listOf("trakt", "simkl", "mdblist", "local").map { provider ->
                WatchedItem(provider, "series", provider, 1, 2, cutoff, trackingProviderId = provider)
            }
            val outside = watched.first().copy(contentId = "older", watchedAt = cutoff - 1)
            val seed = WatchProgress("trakt", "series", "show", null, null, null, "finale",
                3, 10, null, 100, 100, cutoff - 1)
            assertEquals(watched.map { it.contentId }.toSet(),
                latestShowActivity(emptyList(), listOf(seed), watched + outside).filterValues { it >= cutoff }.keys)
        }
        assertNull(continueWatchingCutoff(-1, now))
    }
}
