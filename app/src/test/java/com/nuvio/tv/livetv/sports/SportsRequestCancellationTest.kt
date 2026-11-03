package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SportsRequestCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `cancelling a request closes an active response body promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(10_000)).throttleBody(1, 1, TimeUnit.SECONDS))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/scores")).build())
            val reading = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val request = async {
                call.consumeSportsResponse { response ->
                    reading.countDown()
                    try { response.body!!.string() } finally { finished.countDown() }
                }
            }
            withContext(Dispatchers.IO) {
                assertTrue(reading.await(5, TimeUnit.SECONDS))
            }
            withTimeout(5_000) { request.cancelAndJoin() }
            assertTrue(call.isCanceled())
            withContext(Dispatchers.IO) {
                assertTrue("The body reader must release its worker", finished.await(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `cancelled catalogue download can resume without a failure cooldown`() = runBlocking {
        MockWebServer().use { server ->
            val now = 1_793_318_400_000L
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val feed = SportsBaseFeed(temporary.newFolder(), server.url("/base").toString(),
                clock = { now }, allowLocalhost = true)
            val download = async { feed.refresh() }
            withContext(Dispatchers.IO) {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            }
            withTimeout(5_000) { download.cancelAndJoin() }
            server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"generatedAt":$now,"entries":[{"provider":"espn","leagueId":"football/nfl","fetchedAt":$now,"payload":{"events":[]}}]}"""))
            withTimeout(5_000) { assertNotNull(feed.refresh()) }
            assertEquals(2, server.requestCount)
        }
    }
}
