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
    @Test fun extensionlessLiveUrlStaysDirectWhenTheProviderRequiresGetToIdentifyIt() = runBlocking {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setResponseCode(405))
            upstream.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl"))
            val source = CastSource(upstream.url("/channels/501?ticket=fixture").toString(), "Test channel",
                live = true, iptv = true, fallbackUserAgent = "Live player")
            val endpoint = CastRelay.prepare(RuntimeEnvironment.getApplication(), source, null)
            assertEquals(source.url, endpoint.url)
            assertNull(endpoint.relay)
            val head = requireNotNull(upstream.takeRequest(3, TimeUnit.SECONDS))
            val get = requireNotNull(upstream.takeRequest(3, TimeUnit.SECONDS))
            assertEquals("HEAD", head.method)
            assertEquals("GET", get.method)
            assertEquals("bytes=0-2048", get.getHeader("Range"))
            assertEquals("Live player", get.getHeader("User-Agent"))
        }
    }

    @Test fun extensionlessLiveTransportStreamReachesTheReceiverAsPlayableHls() = runBlocking {
        val address = InetAddress.getByName("127.0.0.1")
        val bytes = requireNotNull(javaClass.getResourceAsStream("/cast/channel.ts")).use { it.readBytes() }
        MockWebServer().use { upstream ->
            upstream.start(address, 0)
            upstream.enqueue(MockResponse().setHeader("Content-Type", "video/mp2t"))
            upstream.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)).setHeader("Content-Type", "video/mp2t"))
            val source = CastSource(upstream.url("/channels/501").toString(), "Test channel", live = true, iptv = true,
                fallbackUserAgent = "Live player")
            val endpoint = CastRelay.prepare(RuntimeEnvironment.getApplication(), source, address, forceRelay = true)
            requireNotNull(endpoint.relay).use {
                assertTrue(endpoint.transportStreamSegments)
                val client = OkHttpClient()
                val manifest = client.newCall(Request.Builder().url(endpoint.url).build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("no-store", response.header("Cache-Control"))
                    response.body!!.string()
                }
                assertTrue(com.nuvio.tv.livetv.player.rewindPlaylistDurationMs(manifest) >= 6000L)
                val segment = manifest.lineSequence().first { it.endsWith(".ts") }
                val url = requireNotNull(endpoint.url.toHttpUrl().resolve(segment))
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    assertEquals("video/mp2t", response.header("Content-Type"))
                    assertEquals(0x47, response.body!!.byteStream().read())
                }
                for (method in listOf("HEAD", "GET")) {
                    val request = requireNotNull(upstream.takeRequest(3, TimeUnit.SECONDS))
                    assertEquals(method, request.method)
                    assertEquals("Live player", request.getHeader("User-Agent"))
                }
            }
        }
    }

    @Test fun servingADownloadSupportsHeadRangesAndRejectsOtherPaths() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = java.io.File.createTempFile("cast-download", ".mp4", context.cacheDir)
        try {
            file.writeText("abcdefgh")
            val source = CastSource(android.net.Uri.fromFile(file).toString(), "Saved", download = file)
            val endpoint = CastRelay.prepare(context, source, InetAddress.getByName("127.0.0.1"), selectedFile = file)
            requireNotNull(endpoint.relay).use {
                val client = OkHttpClient()
                fun request(path: String = endpoint.url) = Request.Builder().url(path)
                client.newCall(request().head().build()).execute().use { head ->
                    assertEquals("8", head.header("Content-Length"))
                    assertEquals("bytes", head.header("Accept-Ranges"))
                    assertEquals(0, head.body!!.bytes().size)
                }
                client.newCall(request().header("Range", "bytes=2-4").build()).execute().use { range ->
                    assertEquals(206, range.code)
                    assertEquals("bytes 2-4/8", range.header("Content-Range"))
                    assertEquals("cde", range.body!!.string())
                }
                client.newCall(request().header("Range", "bytes=8-").build()).execute().use { range ->
                    assertEquals(416, range.code)
                }
                client.newCall(request(endpoint.url + "other").build()).execute().use { assertEquals(404, it.code) }
            }
        } finally { file.delete() }
    }

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
            val source = CastSource(upstream.url("/media/episode.mp4").toString(), "Fixture",
                mapOf("Authorization" to "Bearer fixture", "user-agent" to "Provider agent"), fallbackUserAgent = "Default agent")
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
                assertEquals(listOf("Provider agent"), request.headers.values("User-Agent"))
                assertEquals("bytes=2-4", request.getHeader("Range"))
                client.newCall(Request.Builder().url(endpoint.url + "-wrong").build()).execute().use { assertEquals(404, it.code) }
                assertEquals(1, upstream.requestCount)
            }
            assertTrue(released)
        }
    }
}
