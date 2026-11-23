package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
import com.nuvio.tv.livetv.thumbs.SeekThumbs
import com.nuvio.tv.livetv.thumbs.SeekrSpriteTrack
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.SeekrPreviews
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.TvHiltTestActivity
import com.nuvio.tv.livetv.thumbs.NativeThumbs
import com.nuvio.tv.livetv.thumbs.SeekThumbHttpFile
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SeekThumbnailDeviceTest {
    @Test fun localReaderYieldsToBufferingAndSeekrOnlyNeverOpensIt() = runBlocking {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val old = SeekThumbSettings.load(context)
        val owner = SeekThumbs.Owner.MAIN
        val folder = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "preview-test").toFile()
        val media = java.io.File(folder, "clip.mp4")
        instrumentation.context.assets.open("thumbnail-test.mp4").use { input ->
            media.outputStream().use { input.copyTo(it) }
        }
        val playlist = java.io.File(folder, "index.m3u8")
        playlist.writeText("#EXTM3U\n#EXTINF:4.0,\nclip.mp4\n#EXT-X-ENDLIST\n")
        val source = SeekThumbs.Source.Recording(playlist, folder.name)
        val buffering = java.util.concurrent.atomic.AtomicBoolean(true)
        val playback = SeekThumbs.Playback({ true }, buffering::get, { 0L }, { 0L })
        try {
            SeekThumbSettings.update(context) { it.copy(enabled = true, seekrOnly = true, whenToMake = SeekThumbSettings.When.SEEKING) }
            SeekThumbs.open(context, owner, source, 60_000L, 64, false, playback)
            assertFalse(SeekThumbs.active(owner))
            SeekThumbSettings.update(context) { it.copy(seekrOnly = false) }
            SeekThumbs.open(context, owner, source, 60_000L, 64, false, playback)
            SeekThumbs.want(owner, 0)
            kotlinx.coroutines.delay(300)
            assertNull(SeekThumbs.frame(owner, 0))
            SeekThumbs.seekCommitted(owner)
            buffering.set(false)
            kotlinx.coroutines.delay(1_200)
            assertNull(SeekThumbs.frame(owner, 0))
            SeekThumbs.want(owner, 0)
            val frame = kotlinx.coroutines.withTimeout(8_000L) {
                while (SeekThumbs.frame(owner, 0) == null) kotlinx.coroutines.delay(50)
                requireNotNull(SeekThumbs.frame(owner, 0))
            }
            assertTrue(frame.width in 1..240)
        } finally {
            SeekThumbs.close(owner)
            SeekThumbSettings.update(context) { old }
            folder.deleteRecursively()
        }
    }

    @Test fun exoSeeksWhileASeparateReaderDecodesTheSameFile() = checkEngineSeek(useMpv = false)

    @Test fun libmpvSeeksWhileASeparateReaderDecodesTheSameFile() = checkEngineSeek(useMpv = true)

    private fun checkEngineSeek(useMpv: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clip = java.io.File.createTempFile("seek-playback-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("thumbnail-test.mp4").use { input -> clip.outputStream().use(input::copyTo) }
            ActivityScenario.launch(TvHiltTestActivity::class.java).use { scenario ->
                var exo: androidx.media3.exoplayer.ExoPlayer? = null
                var mpv: NuvioMpvSurfaceView? = null
                fun awaitPlayer(label: String, condition: () -> Boolean) {
                    val until = android.os.SystemClock.elapsedRealtime() + 15_000
                    while (android.os.SystemClock.elapsedRealtime() < until) {
                        var ready = false
                        scenario.onActivity { ready = condition() }
                        if (ready) return
                        Thread.sleep(30)
                    }
                    fail(label)
                }
                try {
                    scenario.onActivity { activity ->
                        if (useMpv) {
                            mpv = NuvioMpvSurfaceView(activity).also {
                                activity.setContentView(it)
                                it.setMedia(clip.path, emptyMap())
                                it.setPaused(true)
                            }
                        } else {
                            val surface = android.view.SurfaceView(activity)
                            activity.setContentView(surface)
                            exo = androidx.media3.exoplayer.ExoPlayer.Builder(activity).build().apply {
                                setVideoSurfaceView(surface)
                                setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(clip)))
                                prepare()
                            }
                        }
                    }
                    awaitPlayer("Engine did not load the fixture") { (mpv?.durationMs() ?: exo?.duration ?: 0) > 3_000 }
                    val reader = requireNotNull(NativeThumbs.Reader.open(clip.path))
                    try {
                        scenario.onActivity {
                            if (useMpv) { mpv!!.setPaused(true); mpv!!.seekToMsExact(2_000) }
                            else exo!!.seekTo(2_000)
                        }
                        val image = requireNotNull(reader.frameAt(1_000, 160, 90))
                        assertTrue(image.width in 1..160)
                        image.recycle()
                        awaitPlayer("Engine did not reach the requested position") {
                            (mpv?.currentPositionMs() ?: exo?.currentPosition ?: 0) in 1_750L..2_500L
                        }
                        scenario.onActivity { if (useMpv) mpv!!.setPaused(false) else exo!!.play() }
                        awaitPlayer("Engine did not resume after seeking") {
                            (mpv?.currentPositionMs() ?: exo?.currentPosition ?: 0) > 2_600
                        }
                    } finally { reader.close() }
                } finally {
                    scenario.onActivity { mpv?.releasePlayer(); exo?.release() }
                }
            }
        } finally { clip.delete() }
    }

    @Test fun authenticatedRangeReaderCachesNearbyBytesAndScopesHeadersOnRedirect() {
        val bytes = ByteArray(512) { it.toByte() }
        val seenAtOrigin = java.util.concurrent.atomic.AtomicReference<String>()
        val seenAtDestination = java.util.concurrent.atomic.AtomicReference<String>()
        val downloads = AtomicInteger()
        val destination = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                seenAtDestination.set(session.headers["x-fixture-token"])
                downloads.incrementAndGet()
                return newFixedLengthResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, "application/octet-stream",
                    bytes.inputStream(), bytes.size.toLong()).apply { addHeader("Content-Range", "bytes 0-511/512") }
            }
        }
        val origin = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                seenAtOrigin.set(session.headers["x-fixture-token"])
                return newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT, "text/plain", "").apply {
                    addHeader("Location", "http://127.0.0.1:${destination.listeningPort}/video")
                }
            }
        }
        try {
            destination.start()
            origin.start()
            SeekThumbHttpFile("http://127.0.0.1:${origin.listeningPort}/video", mapOf("X-Fixture-Token" to "fixture")).use {
                val data = ByteArray(8)
                assertEquals(512L, it.size)
                assertEquals(8, it.readAt(150, data, 0, data.size))
                assertArrayEquals(bytes.copyOfRange(150, 158), data)
                assertEquals(-1, it.readAt(512, data, 0, data.size))
                assertEquals("fixture", seenAtOrigin.get())
                assertNull(seenAtDestination.get())
                assertEquals(1, downloads.get())
            }
        } finally { origin.stop(); destination.stop() }
    }

    @Test fun resolvesSeekrTilesFromOneSheetAndFallsBackOnAMissingSheet() = runBlocking {
        val spriteHost = "https://sprites.seekr.tv"
        val pixels = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        pixels.eraseColor(Color.RED)
        for (x in 2..3) for (y in 0..1) pixels.setPixel(x, y, Color.GREEN)
        val png = ByteArrayOutputStream().also { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val downloads = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var code = 200
            val body = when (request.url.encodedPath) {
                "/sprites" -> {
                    assertEquals("fixture", request.header("X-API-Key"))
                    """{"vtt_url":"$spriteHost/title.vtt?sig=test","source_duration_ms":60000,"scale":1.0}""".toResponseBody()
                }
                "/title.vtt" -> {
                    assertNull(request.header("X-API-Key"))
                    """WEBVTT

00:00:00.000 --> 00:00:10.000
$spriteHost/image.png#xywh=0,0,2,2

00:00:10.000 --> 00:00:20.000
$spriteHost/image.png#xywh=2,0,2,2

00:00:20.000 --> 00:00:30.000
$spriteHost/missing.png#xywh=0,0,2,2
""".toResponseBody()
                }
                "/image.png" -> { assertNull(request.header("X-API-Key")); downloads.incrementAndGet(); png.toResponseBody() }
                else -> { code = 404; "".toResponseBody() }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()
        }.build()
        val track = requireNotNull(SeekrPreviews.create("fixture", httpClient = http).loadTrack(SeekrContent.Movie(tmdbId = 1), 60000L))
        val sprites = SeekrSpriteTrack(track, http)
        val state = SeekrPreviewState()
        state.resolved(sprites, false)
        assertFalse(state.allowGeneration)
        val first = requireNotNull(sprites.bitmap(requireNotNull(sprites.cueAt(1)).tile))
        val second = requireNotNull(sprites.bitmap(requireNotNull(sprites.cueAt(11000)).tile))
        assertEquals(Color.RED, first.getPixel(0, 0))
        assertEquals(Color.GREEN, second.getPixel(0, 0))
        assertEquals(1, downloads.get())
        assertNull(sprites.bitmap(requireNotNull(sprites.cueAt(21000)).tile))
        state.tileUnavailable(sprites)
        assertTrue(state.allowGeneration)
        state.resolved(sprites, true)
        state.tileUnavailable(sprites)
        assertFalse(state.allowGeneration)
    }
}
