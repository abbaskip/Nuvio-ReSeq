package com.nuvio.tv.mobile

import android.content.Context
import android.media.metrics.LogSessionId
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.Clock
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.ffmpeg.AudioSyncFfmpegDecoder
import androidx.media3.decoder.ffmpeg.LiveCastAudioDecoder
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

internal enum class LiveCastFormat { COPY_VIDEO, H264 }

internal class LiveCastMedia(
    context: Context, private val source: CastSource, private val format: LiveCastFormat,
    private val client: OkHttpClient
) : Closeable {
    private val app = context.applicationContext
    private val dir = File(cache(app), UUID.randomUUID().toString()).apply { check(mkdir()) }
    val window = LiveCastWindow(dir)
    @Volatile var errorCode: Int? = null
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        try {
            val done = CompletableDeferred<Unit>()
            val factory = object : Muxer.Factory {
                override fun create(path: String): Muxer {
                    val fragments = LiveCastFragments(window)
                    val muxer = FragmentedMp4Muxer.Builder(fragments).setFragmentDurationMs(1500).build()
                    return object : Muxer by muxer {
                        private var bytes = 0L
                        private var lastSegment = 0
                        private var waitingForVideoKeyframe = -1
                        override fun addTrack(format: Format): Int = muxer.addTrack(format).also {
                            if (MimeTypes.isVideo(format.sampleMimeType)) waitingForVideoKeyframe = it
                        }
                        override fun writeSampleData(trackId: Int, buffer: ByteBuffer, info: BufferInfo) {
                            if (lastSegment != window.sequence) { lastSegment = window.sequence; bytes = 0L }
                            bytes += info.size
                            check(bytes <= 32L * 1024 * 1024) { "Too much live media without a keyframe" }
                            muxer.writeSampleData(trackId, buffer, info)
                            if (info.flags and C.BUFFER_FLAG_KEY_FRAME != 0 && trackId == waitingForVideoKeyframe)
                                waitingForVideoKeyframe = -1
                            if (trackId != waitingForVideoKeyframe && info.size > 0)
                                fragments.sampleWritten(trackId + 1, info.presentationTimeUs)
                        }
                    }
                }
                override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
                    C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
                    C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_AAC)
                    else -> ImmutableList.of()
                }
            }
            val mediaSources = DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))
            val platform = DefaultDecoderFactory.Builder(app).setEnableDecoderFallback(true).build()
            val decoder = object : Codec.DecoderFactory by platform {
                override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec = try {
                    platform.createForAudioDecoding(format, logSessionId)
                } catch (e: ExportException) {
                    if (AudioSyncFfmpegDecoder.supports(format.sampleMimeType.orEmpty())) LiveCastAudioDecoder(format) else throw e
                }
            }
            val builder = Transformer.Builder(app).setMuxerFactory(factory)
                .setAssetLoaderFactory(ExoPlayerAssetLoader.Factory(app, decoder, Clock.DEFAULT, mediaSources, null, null))
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        errorCode = exportException.errorCode
                        done.completeExceptionally(exportException)
                    }
                })
            val media = MediaItem.Builder().setUri(source.url).setMimeType(source.mime).build()
            val item = EditedMediaItem.Builder(media)
            val audioEffects = if (source.audioMime != null && source.audioMime != MimeTypes.AUDIO_AAC)
                listOf(ChannelMixingAudioProcessor().apply {
                    for (channels in 1..6) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, 2))
                }) else emptyList()
            val videoEffects = if (format == LiveCastFormat.H264) {
                builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                val encoder = DefaultEncoderFactory.Builder(app).build()
                builder.setEncoderFactory(object : Codec.EncoderFactory by encoder { override fun videoNeedsEncoding() = true })
                if (source.videoHeight > 720) listOf(Presentation.createForHeight(720)) else emptyList()
            } else emptyList()
            item.setEffects(Effects(audioEffects, videoEffects))
            val transformer = builder.build()
            try {
                transformer.start(item.build(), File(dir, "live").path)
                done.await()
                window.finish()
                if (!window.ready.isCompleted) error("Channel ended before the live window was ready")
            } finally {
                if (!done.isCompleted) transformer.cancel()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { window.ready.completeExceptionally(e) }
    }

    /** Only start the provider connection and codecs after the TV reaches this phone. */
    fun start() { worker.start() }
    override fun close() {
        window.ready.cancel()
        scope.cancel()
        worker.invokeOnCompletion { window.close() }
    }

    companion object {
        private var directory: File? = null
        @Synchronized private fun cache(context: Context): File = directory ?: File(context.cacheDir, "cast-live").also {
            it.mkdirs()
            it.listFiles()?.filter(File::isDirectory)?.forEach(File::deleteRecursively)
            directory = it
        }
    }
}
