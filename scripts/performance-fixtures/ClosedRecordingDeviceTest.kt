package com.nuvio.tv.livetv.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.IptvStorageTestEntryPoint
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.record.LiveTvRecorder
import com.nuvio.tv.livetv.record.LiveTvRecording
import com.nuvio.tv.livetv.record.RecordingStatus
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClosedRecordingDeviceTest {
    @Test fun savedArchivePlaysWithItsOriginalProviderOffline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val storage = StorageTestContext(app)
        val profileManager = EntryPointAccessors.fromApplication(app.applicationContext, IptvStorageTestEntryPoint::class.java).profileManager()
        val recorder = LiveTvRecorder(storage, LiveTvPreferences(storage, profileManager))
        val media = instrumentation.context.assets.open("closed-recording.ts").use { it.readBytes() }
        val provider = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/archive.m3u8" -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10.0,\nsource.ts\n#EXT-X-ENDLIST\n")
                "/source.ts" -> newFixedLengthResponse(Response.Status.OK, "video/mp2t", media.inputStream(), media.size.toLong())
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        }
        provider.start()
        val url = "http://127.0.0.1:${provider.listeningPort}/archive.m3u8"
        val channel = LiveChannel(key = "recording-test-channel", sourceId = "recording-test", sourceName = "Fixture",
            name = "Archive channel", tvgId = null, tvgName = null, logo = null, groupId = "fixture", group = "Fixture", number = 1, url = url)
        val completed = CompletableDeferred<LiveTvRecording>()
        val now = System.currentTimeMillis()
        val recording = recorder.start(channel, url, "Archive fixture", null, null, now - 10_000, now, now + 60_000,
            finite = true, onFinished = { completed.complete(it) })
        try {
            val finished = withTimeout(30_000) { completed.await() }
            assertEquals(RecordingStatus.DONE, finished.status)
            assertEquals(10.0, finished.durationSec, 0.001)
            assertTrue(finished.sizeBytes >= media.size)
            val folder = File(finished.dir)
            val persisted = requireNotNull(LiveTvRecording.fromJson(JSONObject(File(folder, "info.json").readText()), folder.path))
            assertEquals(finished, persisted)
            val index = recorder.playlistFile(persisted).readLines().filter(String::isNotBlank)
            assertEquals("#EXT-X-ENDLIST", index.last())
            val segment = index.single { !it.startsWith("#") }
            assertTrue(segment.matches(Regex("seg[0-9]+\\.ts")))
            assertArrayEquals(media, File(folder, segment).readBytes())
            provider.stop()
            SyntheticVideo.assertPlays(recorder.playUrl(persisted))
        } finally {
            provider.stop()
            recorder.delete(recording.id)
        }
    }
}
