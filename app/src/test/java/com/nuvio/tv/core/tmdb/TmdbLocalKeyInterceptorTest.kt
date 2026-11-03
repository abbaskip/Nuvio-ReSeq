package com.nuvio.tv.core.tmdb

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbLocalKeyInterceptorTest {
    @Test
    fun `shared TMDB lookups replace a bundled key with the current local key`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = tmdbPrivateClient(TmdbLocalKeyInterceptor({ "synthetic-local-key" }, "localhost"))
            client.newCall(Request.Builder().url(server.url("/3/find/tt1?api_key=bundled-key")).build()).execute().close()
            assertEquals("synthetic-local-key", server.takeRequest().requestUrl!!.queryParameter("api_key"))
            assertNull(client.cache)
            assertEquals(1, client.interceptors.size)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
        }
    }

    @Test
    fun `personal keys are never attached to other hosts`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = tmdbPrivateClient(TmdbLocalKeyInterceptor({ "synthetic-local-key" }))
            client.newCall(Request.Builder().url(server.url("/3/find/tt1")).build()).execute().close()
            assertNull(server.takeRequest().requestUrl!!.queryParameter("api_key"))
        }
    }
}
