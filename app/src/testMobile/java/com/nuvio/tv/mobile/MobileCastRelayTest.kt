package com.nuvio.tv.mobile

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
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
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class MobileCastRelayTest {
    @Test fun adaptiveManifestRetainsTheRepresentationAndSegmentNumber() {
        val manifest = "https://provider.example/title/manifest.mpd".toHttpUrl()
        val number = "\$Number%05d\$"
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><BaseURL>video/</BaseURL><AdaptationSet>
            <SegmentTemplate media="track-${'$'}RepresentationID${'$'}-$number.m4s" initialization="init.mp4" timescale="1000"/>
            <Representation id="1080" bandwidth="1000000"><BaseURL>high/</BaseURL></Representation></AdaptationSet></Period></MPD>"""
        val rewritten = rewriteCastDash(xml, manifest, { "/fixed" }) { base, path ->
            assertEquals("https://provider.example/title/video/high/", base.toString())
            when (path) {
                "init.mp4" -> "/init"
                "track-1080-$number.m4s" -> "/segments/$number"
                else -> error("Unexpected resource $path")
            }
        }
        assertTrue(rewritten.contains("media=\"/segments/$number\""))
        assertTrue(rewritten.contains("initialization=\"/init\""))
        assertTrue(rewritten.contains("timescale=\"1000\""))
    }

    @Test fun hlsMapsSegmentAndCaptionAddressesToReceiverLocalPaths() = runBlocking {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody("#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,URI=\"https://media.example/subs.m3u8\"\nhttps://media.example/video.m3u8?ticket=fixture\n"))
            val address = InetAddress.getByName("127.0.0.1")
            upstream.start(address, 0)
            val source = CastSource(upstream.url("/master.m3u8").toString(), "Fixture")
            val endpoint = CastRelay.prepare(RuntimeEnvironment.getApplication(), source, address, Closeable {})
            requireNotNull(endpoint.relay).use {
                OkHttpClient().newCall(Request.Builder().url(endpoint.url).build()).execute().use { response ->
                    assertEquals(200, response.code)
                    val lines = response.body!!.string().lines()
                    assertTrue(lines[1].contains("URI=\"/"))
                    assertTrue(lines[2].startsWith("/"))
                    assertFalse(lines[2].contains("ticket"))
                    assertFalse(lines.joinToString().contains("media.example"))
                }
            }
        }
    }

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
