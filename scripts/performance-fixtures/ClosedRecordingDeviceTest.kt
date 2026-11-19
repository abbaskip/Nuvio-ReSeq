package com.nuvio.tv.livetv.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.IptvStorageTestEntryPoint
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.record.LiveTvRecorder
import com.nuvio.tv.livetv.record.LiveTvRecording
import com.nuvio.tv.livetv.record.RecordingSchedulerEntryPoint
import com.nuvio.tv.livetv.record.RecordingStatus
import com.nuvio.tv.livetv.record.RecordingStorage
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    private fun provider(media: ByteArray, segmentRequest: () -> Unit = {}) =
        object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/archive.m3u8" -> newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10.0,\nsource.ts\n#EXT-X-ENDLIST\n")
                "/source.ts" -> {
                    segmentRequest()
                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", media.inputStream(), media.size.toLong())
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        }

    @Test fun savedArchivePlaysWithItsOriginalProviderOffline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val storage = StorageTestContext(app)
        val profileManager = EntryPointAccessors.fromApplication(app.applicationContext, IptvStorageTestEntryPoint::class.java).profileManager()
        val recorder = LiveTvRecorder(storage, LiveTvPreferences(storage, profileManager))
        val media = instrumentation.context.assets.open("closed-recording.ts").use { it.readBytes() }
        val provider = provider(media)
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

    @Test fun androidAlarmRecordsTheSavedChannelAtTheScheduledTime() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val entry = EntryPointAccessors.fromApplication(app, IptvStorageTestEntryPoint::class.java)
        entry.profileManager().activeProfileReady.first { it }
        val prefs = entry.livePreferences()
        val previous = prefs.currentSettings()
        val scheduler = EntryPointAccessors.fromApplication(app, RecordingSchedulerEntryPoint::class.java).scheduler()
        val recordingDir = RecordingStorage.resolve(app, previous.recordingStorage).dir
        val media = instrumentation.context.assets.open("closed-recording.ts").use { it.readBytes() }
        val firstRequest = CompletableDeferred<Long>()
        val provider = provider(media) { firstRequest.complete(System.currentTimeMillis()) }
        provider.start()
        val key = "alarm-fixture-" + System.nanoTime()
        val channel = LiveChannel(key = key, sourceId = "alarm-fixture", sourceName = "Fixture",
            name = "Scheduled channel", tvgId = null, tvgName = null, logo = null, groupId = "fixture",
            group = "Fixture", number = 2, url = "http://127.0.0.1:${provider.listeningPort}/archive.m3u8")
        val title = "Scheduled fixture programme"
        var start = 0L
        try {
            scheduler.start()
            prefs.updateSettings { it.copy(recordPreMinutes = 0, recordPostMinutes = 0) }
            withTimeout(15_000) { while (scheduler.padding() != (0L to 0L)) delay(100) }
            start = System.currentTimeMillis() + 20_000
            val stop = start + 45_000
            assertTrue(scheduler.addManual(channel, title, start, stop).startsWith("Set to record"))
            val saved = withTimeout(10_000) { scheduler.schedules.first { rows -> rows.any { it.channel.key == key } } }
                .single { it.channel.key == key }
            assertEquals(start, scheduler.startsAt(saved))
            assertEquals(channel.url, saved.channel.url)
            val requested = withTimeout(75_000) { firstRequest.await() }
            assertTrue("Media fetched outside its recording window", requested in start-1_000..stop)
            val finished = withTimeout(75_000) {
                var found: LiveTvRecording? = null
                while (found == null) {
                    found = recordingDir.listFiles().orEmpty().firstNotNullOfOrNull { dir ->
                        runCatching { LiveTvRecording.fromJson(JSONObject(File(dir, "info.json").readText()), dir.path) }
                            .getOrNull()?.takeIf { it.channelKey == key && it.status == RecordingStatus.DONE }
                    }
                    if (found == null) delay(300)
                }
                requireNotNull(found)
            }
            assertEquals(title, finished.title)
            assertEquals(start, finished.programStartMs)
            assertEquals(stop, finished.programStopMs)
            assertTrue(finished.sizeBytes >= media.size && finished.durationSec >= 10.0)
            assertTrue(scheduler.schedules.value.none { it.id == saved.id })
            provider.stop()
            val playlist = File(finished.dir, "index.m3u8").readLines().filter(String::isNotBlank)
            assertEquals("#EXT-X-ENDLIST", playlist.last())
            assertArrayEquals(media, File(finished.dir, playlist.first { !it.startsWith("#") }).readBytes())
        } finally {
            if (start != 0L) scheduler.cancel(key, start)
            provider.stop()
            prefs.updateSettings { it.copy(recordPreMinutes = previous.recordPreMinutes, recordPostMinutes = previous.recordPostMinutes) }
        }
    }
}
