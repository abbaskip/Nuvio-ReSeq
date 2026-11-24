package com.nuvio.tv.mobile

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.google.android.gms.cast.MediaError.DetailedErrorCode
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File

internal enum class CastFormat { ORIGINAL, MP4, AAC, H264 }
internal enum class CastFailure { DELIVERY, FORMAT, UNKNOWN }

internal fun classifyCastFailure(code: Int?, deliveryFailed: Boolean): CastFailure = when {
    deliveryFailed || code == DetailedErrorCode.MEDIA_NETWORK || code in 300..399 -> CastFailure.DELIVERY
    code == DetailedErrorCode.MEDIA_SRC_NOT_SUPPORTED || code == DetailedErrorCode.MEDIA_DECODE ||
        code == DetailedErrorCode.SOURCE_BUFFER_FAILURE -> CastFailure.FORMAT
    else -> CastFailure.UNKNOWN
}

internal fun nextCastFormat(current: CastFormat, audio: String?, alreadyMp4: Boolean): CastFormat? = when (current) {
    CastFormat.ORIGINAL -> when {
        audio != null && audio != MimeTypes.AUDIO_AAC -> CastFormat.AAC
        alreadyMp4 -> CastFormat.H264
        else -> CastFormat.MP4
    }
    CastFormat.MP4 -> if (audio == MimeTypes.AUDIO_AAC || audio == null) CastFormat.H264 else CastFormat.AAC
    CastFormat.AAC -> CastFormat.H264
    CastFormat.H264 -> null
}

/** Owns the source across local-player handoff, receiver retries and conversion. */
internal class PhoneCastMedia(val source: CastSource, private val lease: Closeable?) : Closeable {
    var format = CastFormat.ORIGINAL
        private set
    var deliveryRetried = false
    private var output: File? = null
    val file get() = output ?: source.download
    private var audio: String? = null

    suspend fun advance(): Boolean {
        if (format == CastFormat.ORIGINAL) audio = withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                val local = source.download
                if (local == null) extractor.setDataSource(source.url, emptyMap()) else extractor.setDataSource(local.path)
                (0 until extractor.trackCount).mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                    .firstOrNull { it.startsWith("audio/") }
            } catch (_: Exception) { null } finally { extractor.release() }
        }
        val next = nextCastFormat(format, audio, (source.mime == "video/mp4" || source.url.substringBefore('?').endsWith(".mp4", true)) && audio != null) ?: return false
        output?.delete()
        output = null
        format = next
        return true
    }

    suspend fun prepare(context: Context, progress: (String) -> Unit) {
        if (format == CastFormat.ORIGINAL || output != null) return
        val target = File.createTempFile("cast-", ".mp4", castCache(context))
        try {
            exportCastMedia(context, source.download?.let(Uri::fromFile) ?: Uri.parse(source.url), format, target, progress)
            output = target
        } finally {
            if (output != target) target.delete()
        }
    }

    override fun close() {
        output?.delete()
        output = null
        lease?.close()
    }

    companion object {
        private var directory: File? = null
        @Synchronized private fun castCache(context: Context): File = directory ?: File(context.cacheDir, "cast-exports").also {
            it.mkdirs()
            // A killed process cannot own a receiver relay or a running export.
            it.listFiles()?.filter { file -> file.name.startsWith("cast-") && file.extension == "mp4" }?.forEach(File::delete)
            directory = it
        }
    }
}

/** Export a seekable MP4. Media3 copies compatible tracks and uses the phone's codecs for conversion. */
internal suspend fun exportCastMedia(context: Context, uri: Uri, format: CastFormat, target: File,
    progress: (String) -> Unit) = withContext(Dispatchers.Main.immediate) {
    val completion = CompletableDeferred<Unit>()
    val builder = Transformer.Builder(context).addListener(object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) { completion.complete(Unit) }
        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            completion.completeExceptionally(exportException)
        }
    })
    val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
    val label = when (format) {
        CastFormat.ORIGINAL -> error("Direct playback does not require an export")
        CastFormat.MP4 -> "Repackaging video"
        CastFormat.AAC -> "Converting audio"
        CastFormat.H264 -> "Converting video"
    }
    if (format == CastFormat.AAC || format == CastFormat.H264) builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
    if (format == CastFormat.H264) {
        val encoder = DefaultEncoderFactory.Builder(context).build()
        builder.setVideoMimeType(MimeTypes.VIDEO_H264).setEncoderFactory(object : Codec.EncoderFactory by encoder {
            override fun videoNeedsEncoding() = true
        })
        item.setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(720))))
    }
    val transformer = builder.build()
    val holder = ProgressHolder()
    progress(label)
    try {
        transformer.start(item.build(), target.path)
        while (!completion.isCompleted) {
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress("$label · ${holder.progress}%")
            withTimeoutOrNull(750) { completion.await() }
        }
        completion.await()
    } finally {
        if (!completion.isCompleted) transformer.cancel()
    }
}
