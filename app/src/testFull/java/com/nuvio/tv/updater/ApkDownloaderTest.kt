package com.nuvio.tv.updater

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `failed body never publishes a partial APK and preserves an existing complete file`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(200_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.start()
            val file = temporary.newFile("update.apk").apply { writeText("previous complete file") }
            val result = ApkDownloader(OkHttpClient()).download(server.url("/update.apk").toString(), file) { _, _ -> }
            assertTrue(result.isFailure)
            assertEquals("previous complete file", file.readText())
            assertFalse(File(file.parentFile, file.name + ".part").exists())
        }
    }

    @Test fun `completed response publishes once and cancellation is propagated with partial cleanup`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete synthetic APK"))
            server.enqueue(MockResponse().setBody("x".repeat(50_000)))
            server.start()
            val destination = File(temporary.root, "update.apk")
            val downloader = ApkDownloader(OkHttpClient())
            assertTrue(downloader.download(server.url("/ok").toString(), destination) { _, _ -> }.isSuccess)
            assertEquals("complete synthetic APK", destination.readText())
            try {
                downloader.download(server.url("/cancel").toString(), destination) { _, _ -> throw CancellationException("test cancellation") }
                fail("Cancellation must not become a normal failed result")
            } catch (_: CancellationException) { }
            assertEquals("complete synthetic APK", destination.readText())
            assertFalse(File(destination.path + ".part").exists())
        }
    }
}
