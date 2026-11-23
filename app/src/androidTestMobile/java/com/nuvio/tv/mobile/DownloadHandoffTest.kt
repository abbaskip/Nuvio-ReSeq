package com.nuvio.tv.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.player.ExternalPlayerInput
import com.nuvio.tv.core.player.ExternalPlayerResultContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadHandoffTest {
    @Test fun externalActivityCanReadTheSelectedSavedFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val media = MobileOfflineMedia(context)
        val video = MobileVideo(987645, "movie", "test-handoff", "test-handoff", "A title + 100%")
        val directory = media.folder(video)
        directory.mkdirs()
        val header = byteArrayOf(0, 0, 0, 24) + "ftyp".toByteArray()
        try {
            val file = File(directory, "test.part").apply { writeBytes(header) }
            media.publish(video, file, "mp4")
            val uri = requireNotNull(media.shareUri(video))
            assertEquals("content", uri.scheme)
            val intent = ExternalPlayerResultContract().createIntent(context, ExternalPlayerInput(url = uri.toString()))
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val received = CountDownLatch(1)
            var bytes: ByteArray? = null
            val reply = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == Activity.RESULT_OK) bytes = resultData?.getByteArray("header")
                    received.countDown()
                }
            }
            context.startActivity(intent.setClassName(instrumentation.context.packageName, DownloadReaderActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("reply", reply))
            assertTrue(received.await(15, TimeUnit.SECONDS))
            assertArrayEquals(header, bytes)
        } finally {
            media.saved(video.profile).filter { it.video == video }.forEach(media::remove)
        }
    }
}
