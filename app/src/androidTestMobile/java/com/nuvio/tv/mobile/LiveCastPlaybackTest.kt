package com.nuvio.tv.mobile

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveCastPlaybackTest {
    @Test fun repackagesRawHevcAndDolby() = play(false, LiveCastFormat.COPY_VIDEO)
    @Test fun repackagesHlsWithTransportStreamSegments() = play(true, LiveCastFormat.COPY_VIDEO)
    @Test fun convertsLiveVideoToH264() = play(false, LiveCastFormat.H264)

    private fun play(hls: Boolean, format: LiveCastFormat) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val sourceFile = File.createTempFile("live-test", ".ts", context.cacheDir)
        assets.open("live-hevc-dolby.ts").use { input -> sourceFile.outputStream().use { input.copyTo(it) } }
        val sourcePlaylist = assets.open("live-hevc-dolby.m3u8").bufferedReader().use { it.readText() }
        val firstRequest = java.util.concurrent.atomic.AtomicLong()
        val releaseStream = java.util.concurrent.CountDownLatch(1)
        // The provider lives on this emulator too. The production relay rejects loopback provider URLs.
        val providerHost = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .first { it is java.net.Inet4Address && it.isSiteLocalAddress }.hostAddress!!
        val server = object : NanoHTTPD(providerHost, 0) {
            override fun serve(session: IHTTPSession): Response = when {
                session.headers["x-test-channel"] != "sports" -> newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "")
                session.uri == "/channel.ts" -> if (hls) serveCastFile(sourceFile, "video/mp2t", session) else {
                    val stream = object : java.io.FilterInputStream(sourceFile.inputStream()) {
                        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(bytes, off, minOf(len, 8192))
                            if (n < 0) releaseStream.await(75, java.util.concurrent.TimeUnit.SECONDS)
                            return n
                        }
                    }
                    newChunkedResponse(Response.Status.OK, "video/mp2t", stream)
                }
                session.uri == "/channel.m3u8" -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    firstRequest.compareAndSet(0L, now)
                    val available = 3 + ((now - firstRequest.get()) / 2000).toInt()
                    val entries = sourcePlaylist.substringBefore("#EXT-X-ENDLIST").split("#EXTINF:")
                    newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL",
                        entries.take(available + 1).joinToString("#EXTINF:"))
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        }
        server.start()
        val base = "http://$providerHost:${server.listeningPort}"
        val source = CastSource(if (hls) "$base/channel.m3u8" else "$base/channel.ts", "Synthetic test channel",
            headers = mapOf("X-Test-Channel" to "sports"),
            live = true, iptv = true, audioMime = MimeTypes.AUDIO_E_AC3, videoHeight = 90,
            mime = if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
        var relay: CastRelay? = null
        try {
            withContext(Dispatchers.Main) {
                val endpoint = CastRelay.prepare(context, source, java.net.InetAddress.getByName("127.0.0.1"), liveFormat = format)
                relay = requireNotNull(endpoint.relay)
                assertTrue(endpoint.fragmentedMp4)
                assertFalse(requireNotNull(relay).receiverRequestedMedia)
                val player = ExoPlayer.Builder(context).build()
                val error = CompletableDeferred<PlaybackException>()
                val images = android.media.ImageReader.newInstance(160, 90, android.graphics.ImageFormat.PRIVATE, 3)
                var frames = 0
                try {
                    images.setOnImageAvailableListener({ reader ->
                        reader.acquireLatestImage()?.use { frames++ }
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                    player.setVideoSurface(images.surface)
                    player.volume = 0f
                    player.addListener(object : Player.Listener {
                        override fun onPlayerError(playbackError: PlaybackException) { error.complete(playbackError) }
                    })
                    player.setMediaItem(MediaItem.fromUri(endpoint.url))
                    player.prepare()
                    player.play()
                    withTimeout(30_000) {
                        while (!player.isPlaying || player.currentPosition < 1000 || player.videoFormat == null) {
                            if (error.isCompleted) throw error.await()
                            delay(50)
                        }
                    }
                    assertEquals(if (format == LiveCastFormat.H264) MimeTypes.VIDEO_H264 else MimeTypes.VIDEO_H265,
                        player.videoFormat?.sampleMimeType)
                    assertEquals(MimeTypes.AUDIO_AAC, player.audioFormat?.sampleMimeType)
                    val started = player.currentPosition
                    withTimeout(8_000) {
                        while (player.currentPosition < started + 2200 || frames < 2) {
                            if (error.isCompleted) throw error.await()
                            delay(50)
                        }
                    }
                } finally { player.release(); images.close() }
            }
        } finally {
            releaseStream.countDown()
            withContext(Dispatchers.Main) { relay?.close() }
            server.stop()
            sourceFile.delete()
        }
    }
}
