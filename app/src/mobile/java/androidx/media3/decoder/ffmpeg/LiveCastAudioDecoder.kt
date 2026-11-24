package androidx.media3.decoder.ffmpeg

import android.media.MediaCodec
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.transformer.Codec
import java.nio.ByteBuffer

/** Transformer adapter for the same software Dolby decoder the local IPTV player already ships. */
internal class LiveCastAudioDecoder(private val inputFormat: Format) : Codec {
    private val decoder = FfmpegAudioDecoder(inputFormat, 4, 4,
        inputFormat.maxInputSize.takeIf { it > 0 } ?: 32_768, 2, "stereo", C.ENCODING_PCM_16BIT)
    private var input: DecoderInputBuffer? = null
    private var output: SimpleDecoderOutputBuffer? = null
    private var ended = false

    override fun getConfigurationFormat() = inputFormat
    override fun getInputFormat() = inputFormat
    override fun getName(): String = decoder.name
    override fun maybeDequeueInputBuffer(inputBuffer: DecoderInputBuffer): Boolean {
        val owned = input ?: decoder.dequeueInputBuffer()?.also { it.clear(); input = it } ?: return false
        inputBuffer.data = owned.data
        return true
    }
    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        val owned = requireNotNull(input)
        owned.timeUs = inputBuffer.timeUs
        if (inputBuffer.isEndOfStream) owned.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        else if (owned.data !== inputBuffer.data) {
            val data = requireNotNull(inputBuffer.data)
            owned.ensureSpaceForWrite(data.remaining())
            requireNotNull(owned.data).put(data)
            owned.flip()
        }
        decoder.queueInputBuffer(owned)
        input = null
        inputBuffer.data = null
    }
    private fun poll(): SimpleDecoderOutputBuffer? {
        if (!ended && output == null) {
            val next = decoder.dequeueOutputBuffer() ?: return null
            if (next.isEndOfStream) { next.release(); ended = true } else output = next
        }
        return output
    }
    override fun getOutputFormat(): Format? {
        if (poll() == null) return null
        return Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(decoder.channelCount).setSampleRate(decoder.sampleRate).build()
    }
    override fun getOutputBuffer(): ByteBuffer? = poll()?.data
    override fun getOutputBufferInfo(): MediaCodec.BufferInfo? = poll()?.let { decoded ->
        MediaCodec.BufferInfo().apply { set(0, requireNotNull(decoded.data).remaining(), decoded.timeUs, 0) }
    }
    override fun releaseOutputBuffer(render: Boolean) { output?.release(); output = null }
    override fun isEnded(): Boolean { poll(); return ended }
    override fun release() { releaseOutputBuffer(false); decoder.release() }
    override fun getInputSurface(): Surface = error("Audio decoder")
    override fun signalEndOfInputStream(): Unit = error("Audio decoder")
    override fun releaseOutputBuffer(renderPresentationTimeUs: Long): Unit = error("Audio decoder")
}
