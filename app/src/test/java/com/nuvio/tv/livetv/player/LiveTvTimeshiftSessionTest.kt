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
