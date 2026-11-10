package com.nuvio.tv.core.usenet

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class UsenetPerformanceTest {
    @Test fun nzbMemoryCacheExpiresAndSeparatesHeadersAndEvictsOldest() {
        var now = 0L
        val cache = NzbMemoryCache { now }
        val document = NzbDocument(emptyList(), "private")
        cache.put("url", mapOf("Authorization" to "a"), document, 100)
        assertSame(document, cache.get("url", mapOf("Authorization" to "a")))
        assertNull(cache.get("url", mapOf("Authorization" to "b")))
        now = TimeUnit.MINUTES.toNanos(30)
        assertNull(cache.get("url", mapOf("Authorization" to "a")))
        repeat(5) { cache.put("$it", emptyMap(), document, 100) }
        assertNull(cache.get("0", emptyMap()))
        assertSame(document, cache.get("4", emptyMap()))
    }

    @Test fun diagnosticsContainOnlyStagesAndCountersAndReportOnce() {
        val logs = mutableListOf<String>()
        val trace = UsenetStartupTrace(logs::add)
        trace.mark("selection_started")
        trace.add("demand", 3)
        trace.finish(true)
        trace.finish(false)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("demand=3"))
        assertTrue(logs.single().matches(Regex("[a-z_0-9= truefalsms]+")))
    }
}
