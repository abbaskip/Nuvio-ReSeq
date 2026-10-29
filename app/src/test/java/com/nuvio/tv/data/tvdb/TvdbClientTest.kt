package com.nuvio.tv.data.tvdb

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call

class TvdbClientTest {
    private val store = mockk<TvdbCredentialStore> {
        every { read(any()) } returns "fixture-key"
        every { revision(any()) } returns 0L
    }
    private val paths = mutableListOf<String>()
    private fun client(vararg replies: Pair<Int, String>): TvdbClient {
        val queue = ArrayDeque(replies.toList())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/login")) {
                val buffer = okio.Buffer()
                chain.request().body!!.writeTo(buffer)
                val body = buffer.readUtf8()
                assertTrue(body.contains("\"apikey\""))
                assertFalse(body.contains("\"pin\""))
            }
            paths += chain.request().url.encodedPath + (chain.request().url.encodedQuery?.let { "?$it" } ?: "")
            val (code, body) = queue.removeFirst()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return TvdbClient(store, http)
    }
    private val login = 200 to """{"data":{"token":"fixture-token"}}"""

    @Test fun `timeout cancels the HTTP call and releases the shared client lock`() = runBlocking {
        val release = CountDownLatch(1)
        val pending = AtomicReference<Call>()
        val started = CompletableDeferred<Unit>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            pending.set(chain.call())
            started.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(login.second.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val job = launch { TvdbClient(store, http).testConnection(1) }
            withTimeout(5_000L) { started.await() }
            withTimeout(1_000L) { job.cancelAndJoin() }
            assertTrue("HTTP call survived coroutine timeout", pending.get()?.isCanceled() == true)
        } finally { release.countDown(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun `empty credentials make no HTTP calls`() = runTest {
        every { store.read(any()) } returns null
        try { client().testConnection(1); fail("Accepted missing credentials") }
        catch (e: TvdbException) { assertEquals(TvdbException.Reason.NO_KEY, e.reason) }
        assertTrue(paths.isEmpty())
    }

    @Test fun `connection test authenticates directly without PIN`() = runTest {
        client(login).testConnection(1)
        assertEquals(listOf("/v4/login"), paths)
    }

    @Test fun `401 refreshes once and retries request`() = runTest {
        val api = client(login, 401 to "{}", login,
            200 to """{"data":{"seasons":[{"type":{"type":"dvd","name":"DVD"}}]}}""")
        assertEquals(listOf(TvdbOrder("dvd", "DVD")), api.orders(1, 78874))
        assertEquals(2, paths.count { it == "/v4/login" })
    }

    @Test fun `failed second page does not return a partial order`() = runTest {
        val api = client(login, 200 to """{"data":{"episodes":[{"id":297989,"seasonNumber":1,"number":2}]},"links":{"next":"next-page"}}""", 503 to "{}")
        try { api.episodes(1, 78874, "dvd"); fail("Partial order accepted") }
        catch (e: TvdbException) { assertEquals(TvdbException.Reason.NETWORK, e.reason) }
        assertTrue(paths.last().endsWith("page=1"))
    }

    @Test fun `repeated pages stop immediately instead of looping until timeout`() = runTest {
        val page = 200 to """{"data":{"episodes":[{"id":297989,"seasonNumber":1,"number":2}]},"links":{"next":"same-page"}}"""
        try { client(login, page, page).episodes(1, 78874, "dvd"); fail("Repeated page accepted") }
        catch (e: TvdbException) { assertEquals(TvdbException.Reason.INVALID_RESPONSE, e.reason) }
        assertEquals(3, paths.size)
    }

    @Test fun `different TVDB identities cannot occupy the same displayed slot`() = runTest {
        val page = 200 to """{"data":{"episodes":[{"id":297989,"seasonNumber":1,"number":2},{"id":297994,"seasonNumber":1,"number":2}]},"links":{"next":null}}"""
        try { client(login, page).episodes(1, 78874, "dvd"); fail("Ambiguous order accepted") }
        catch (e: TvdbException) { assertEquals(TvdbException.Reason.INVALID_RESPONSE, e.reason) }
    }

    @Test fun `complete pagination keeps TVDB identity`() = runTest {
        val api = client(login, 200 to """{"data":{"episodes":[{"id":297989,"seasonNumber":1,"number":2}]},"links":{"next":"next-page"}}""",
            200 to """{"data":{"episodes":[{"id":297994,"seasonNumber":1,"number":4}]},"links":{"next":null}}""")
        assertEquals(listOf(297989L, 297994L), api.episodes(1, 78874, "dvd").map { it.id })
    }

    @Test fun `authentication errors disclose neither key nor server body`() = runTest {
        try { client(401 to "fixture-key must not appear in errors").testConnection(1); fail("Accepted rejected key") }
        catch (e: TvdbException) {
            assertEquals(TvdbException.Reason.AUTHENTICATION, e.reason)
            assertFalse(e.toString().contains("fixture-key"))
        }
    }

    @Test fun `credential replacement invalidates an existing token`() = runTest {
        var revision = 0L
        every { store.revision(any()) } answers { revision }
        val api = client(login, 200 to """{"data":{"seasons":[]}}""", login, 200 to """{"data":{"seasons":[]}}""")
        api.orders(1, 78874)
        revision++
        api.orders(1, 78874)
        assertEquals(2, paths.count { it == "/v4/login" })
    }
}
