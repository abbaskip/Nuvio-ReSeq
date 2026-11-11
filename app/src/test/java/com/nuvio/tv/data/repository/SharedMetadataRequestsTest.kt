package com.nuvio.tv.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMetadataRequestsTest {
    @Test fun `last caller leaving cancels network work and frees request for retry`() = runTest {
        val requests = SharedMetadataRequests<String>(backgroundScope)
        var cancelled = false
        val first = async { requests.await("show") { try { awaitCancellation() } finally { cancelled = true } } }
        runCurrent()
        first.cancel()
        runCurrent()
        assertTrue(cancelled)
        val retry = async { requests.await("show") { "fresh" } }
        runCurrent()
        assertEquals("fresh", retry.await())
    }
    @Test fun `leaving Home does not cancel metadata still needed by details`() = runTest {
        val requests = SharedMetadataRequests<String>(backgroundScope)
        val response = CompletableDeferred<String>()
        var calls = 0
        val home = async { requests.await("show") { calls++; response.await() } }
        val details = async { requests.await("show") { calls++; response.await() } }
        runCurrent()
        assertEquals(1, calls)
        home.cancel()
        runCurrent()
        response.complete("catalogue")
        runCurrent()
        assertEquals("catalogue", details.await())
    }
    @Test fun `clearing metadata cancels in-flight requests`() = runTest {
        val requests = SharedMetadataRequests<String>(backgroundScope)
        val waiting = async { requests.await("show") { awaitCancellation() } }
        runCurrent()
        requests.clear()
        runCurrent()
        assertTrue(waiting.isCancelled)
    }
}
