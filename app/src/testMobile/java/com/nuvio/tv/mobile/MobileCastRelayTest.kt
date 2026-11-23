package com.nuvio.tv.mobile

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class MobileCastRelayTest {
    @Test fun receiverGetsTheSelectedRangeAndProviderHeadersStayOnThePhone() = runBlocking {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/8").setBody("cde"))
            upstream.start(InetAddress.getByName("127.0.0.1"), 0)
            val source = CastSource(upstream.url("/media/episode.mp4").toString(), "Fixture", mapOf("Authorization" to "Bearer fixture"))
            var released = false
            val endpoint = CastRelay.prepare(RuntimeEnvironment.getApplication(), source, InetAddress.getByName("127.0.0.1"), Closeable { released = true })
            requireNotNull(endpoint.relay).use {
                assertFalse(endpoint.url.contains("fixture"))
                assertFalse(endpoint.url.contains("episode.mp4"))
                val client = OkHttpClient()
                client.newCall(Request.Builder().url(endpoint.url).header("Range", "bytes=2-4").build()).execute().use { response ->
                    assertEquals(206, response.code)
                    assertEquals("bytes 2-4/8", response.header("Content-Range"))
                    assertEquals("*", response.header("Access-Control-Allow-Origin"))
                    assertEquals("cde", response.body!!.string())
                }
                val request = requireNotNull(upstream.takeRequest(3, TimeUnit.SECONDS))
                assertEquals("Bearer fixture", request.getHeader("Authorization"))
                assertEquals("bytes=2-4", request.getHeader("Range"))
                client.newCall(Request.Builder().url(endpoint.url + "-wrong").build()).execute().use { assertEquals(404, it.code) }
                assertEquals(1, upstream.requestCount)
            }
            assertTrue(released)
        }
    }
}
