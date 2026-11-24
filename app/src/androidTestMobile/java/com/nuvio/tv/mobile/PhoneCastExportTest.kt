package com.nuvio.tv.mobile

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneCastExportTest {
    @Test fun convertsPcmToAac() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File.createTempFile("cast-audio", ".wav", context.cacheDir)
        val output = File.createTempFile("cast-audio", ".mp4", context.cacheDir)
        try {
            val dataSize = 44_100 * 2
            val wav = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16)
            wav.putShort(1).putShort(1).putInt(44_100).putInt(dataSize).putShort(2).putShort(16)
            wav.put("data".toByteArray()).putInt(dataSize)
            input.writeBytes(wav.array())
            withTimeout(60_000) { exportCastMedia(context, Uri.fromFile(input), CastFormat.AAC, output) {} }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                assertEquals("audio/mp4a-latm", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            } finally { extractor.release() }
        } finally { input.delete(); output.delete() }
    }

    @Test fun remuxAndVideoFallbackProduceSeekableMp4() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("source", ".mp4", context.cacheDir)
        val output = File.createTempFile("output", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("thumbnail-test.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            for (format in listOf(CastFormat.MP4, CastFormat.H264)) {
                withTimeout(120_000) { exportCastMedia(context, Uri.fromFile(source), format, output) {} }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.path)
                    val video = (0 until extractor.trackCount).first {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }
                    val track = extractor.getTrackFormat(video)
                    if (format == CastFormat.H264) assertEquals("video/avc", track.getString(MediaFormat.KEY_MIME))
                    assertTrue(track.getLong(MediaFormat.KEY_DURATION) > 0)
                    extractor.selectTrack(video)
                    extractor.seekTo(track.getLong(MediaFormat.KEY_DURATION) / 2, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    assertTrue(extractor.sampleTime >= 0)
                } finally { extractor.release() }
            }
        } finally { source.delete(); output.delete() }
    }
}
