package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class LiveTvTimeshiftSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedInitDownloadRecoversWhileRetainingMediaSegments() = runBlocking {
        val context = mockk<Context>()
        every { context.cacheDir } returns temporary.root
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } returns mockk<Uri>()
        val recorder = LiveTvTimeshift(context)
        val retry = java.util.concurrent.CountDownLatch(1)
        try {
            MockWebServer().use { server ->
                val attempts = java.util.concurrent.atomic.AtomicInteger()
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                        when (request.path) {
                            "/live.m3u8" -> MockResponse().setBody(
                                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:5\n" +
                                    "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\nsegment.m4s\n")
                            "/segment.m4s" -> MockResponse().setBody("media")
                            "/init.mp4" -> if (attempts.incrementAndGet() == 1) MockResponse().setResponseCode(503)
                                else { retry.await(8, TimeUnit.SECONDS); MockResponse().setBody("init") }
                            else -> MockResponse().setResponseCode(404)
                        }
                }
                val session = recorder.start(server.url("/live.m3u8").toString(), emptyMap(), 1)!!
                val index = File(session.dir, "index.m3u8")
                try {
                    withTimeout(5_000) { while (!File(session.dir, "seg5.ts").exists()) delay(20) }
                    assertFalse(index.exists())
                    assertEquals(0L, recorder.availableDurationMs)
                    retry.countDown()
                    withTimeout(7_000) { while (!index.exists()) delay(20) }
                    assertEquals("init", File(session.dir, "init.mp4").readText())
                    assertEquals("media", File(session.dir, "seg5.ts").readText())
                    assertTrue(index.readText().contains("#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1.000,\nseg5.ts"))
                    assertEquals(1000L, recorder.availableDurationMs)
                } finally {
                    retry.countDown()
                    recorder.stop()
                }
            }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    @Test fun `switching a blocked recording cannot let its old completion stop the replacement`() = runBlocking {
        val context = mockk<Context>()
        every { context.cacheDir } returns temporary.root
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } answers {
            val location = firstArg<File>().path
            mockk<Uri> { every { path } returns location }
        }
        val recorder = LiveTvTimeshift(context)
        try {
            MockWebServer().use { server ->
                repeat(2) { server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 1, TimeUnit.SECONDS)) }
                val first = recorder.start(server.url("/first.ts").toString(), emptyMap(), 1, connectionReleaseDelayMs = 0)!!
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                withTimeout(5_000) { while (recorder.bytesReceived == 0L) delay(20) }
                val second = recorder.start(server.url("/second.ts").toString(), emptyMap(), 1, connectionReleaseDelayMs = 0)!!
                assertNotEquals(first.dir, second.dir)
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                withTimeout(5_000) { while (recorder.bytesReceived == 0L) delay(20) }
                delay(500) // let the cancelled reader's completion run after the replacement began
                assertSame(second, recorder.session)
                assertTrue(recorder.isRecording)
                recorder.stop()
                assertFalse(recorder.isRecording)
            }
        } finally {
            recorder.stop()
            unmockkStatic(Uri::class)
        }
    }
}
