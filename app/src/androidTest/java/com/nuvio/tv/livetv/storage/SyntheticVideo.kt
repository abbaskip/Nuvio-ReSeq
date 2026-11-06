package com.nuvio.tv.livetv.storage

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import java.io.File

/** Six seconds of generated grey frames; no downloaded or real provider media. */
internal object SyntheticVideo {
    fun create(directory: File): File {
        val file = File(directory, "synthetic-video.mp4")
        val software = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", true) } &&
                (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google."))
        }
        val codec = if (software == null) MediaCodec.createEncoderByType("video/avc") else MediaCodec.createByCodecName(software.name)
        val format = MediaFormat.createVideoFormat("video/avc", 160, 96).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 150_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var track = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var frame = 0
            var ended = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!ended && System.nanoTime() < deadline) {
                if (frame <= 60) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (frame == 60) codec.queueInputBuffer(index, 0, 0, frame * 100_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            val bytes = ByteArray(160 * 96 * 3 / 2) { if (it < 160 * 96) (30 + frame * 3).toByte() else 128.toByte() }
                            codec.getInputBuffer(index)!!.apply { clear(); put(bytes) }
                            codec.queueInputBuffer(index, 0, bytes.size, frame * 100_000L, 0)
                        }
                        frame++
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                } else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = codec.getOutputBuffer(output)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
            check(ended && started) { "Synthetic media generation timed out" }
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
        return file
    }

    suspend fun assertPlays(url: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val frameError = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val thread = android.os.HandlerThread("synthetic-video-frames").apply { start() }
        val reader = android.media.ImageReader.newInstance(160, 96, android.graphics.ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ source ->
            try { source.acquireLatestImage()?.use { frames.incrementAndGet() } }
            catch (error: Exception) { frameError.set(error) }
        }, android.os.Handler(thread.looper))
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val active = androidx.media3.exoplayer.ExoPlayer.Builder(instrumentation.targetContext).build()
                player = active
                active.setVideoSurface(reader.surface)
                active.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                active.prepare(); active.playWhenReady = true
            }
            withTimeout(20_000) {
                var progressed = false
                while (!progressed) {
                    instrumentation.runOnMainSync {
                        val active = checkNotNull(player)
                        assertNull("Matched source playback must not fail", active.playerError)
                        assertNull("Decoded frames must be readable", frameError.get())
                        progressed = active.currentPosition >= 1000 && frames.get() > 0
                    }
                    if (!progressed) delay(100)
                }
            }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            reader.close(); thread.quitSafely()
        }
    }
}
