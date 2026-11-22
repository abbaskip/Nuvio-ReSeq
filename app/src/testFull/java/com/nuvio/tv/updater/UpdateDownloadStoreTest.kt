package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.updater.model.AppUpdate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun verifiedBytesSurviveWrongReleaseSizeAndUnavailablePackageInfo() = runBlocking {
        val record = MutableStateFlow<DownloadedUpdate?>(null)
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        val preferences = mockk<UpdatePreferences>()
        every { preferences.downloadedUpdate } returns record
        coEvery { preferences.setDownloadedUpdate(any()) } answers { record.value = firstArg() }
        fun store() = UpdateDownloadStore(context, preferences, ApkDownloader(OkHttpClient()))

        MockWebServer().use { server ->
            val body = "release bytes"
            server.enqueue(MockResponse().setBody(body))
            val update = AppUpdate(
                tag = "v9.0.0", title = "Test", notes = "", releaseUrl = null, assetName = "update.apk",
                assetUrl = server.url("/update.apk").toString(), assetSizeBytes = 9000L,
                assetDigest = "sha256:${body.encodeUtf8().sha256().hex()}"
            )
            val file = store().download(update).getOrThrow()
            assertEquals(body, file.readText())
            assertEquals(file, store().download(update).getOrThrow())
            assertEquals(1, server.requestCount)
            assertEquals(update.tag to file, store().pendingInstall())

            // A replaced asset with the same tag, filename and byte count must be checked again.
            val replaced = update.copy(assetDigest = "sha256:${"other payload".encodeUtf8().sha256().hex()}")
            assertNull(store().downloadedFileFor(replaced))
            file.appendText("damage")
            assertNull(store().downloadedFileFor(update))
            assertNull(store().pendingInstall())

            server.enqueue(MockResponse().setBody(body))
            assertTrue(store().download(replaced).isFailure)
            assertFalse(file.exists())
            assertFalse(File(file.path + ".part").exists())
            assertNull(store().pendingInstall())
        }
    }
}
