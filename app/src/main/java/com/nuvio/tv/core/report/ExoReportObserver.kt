@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.core.report

import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Only existing player callbacks; no sampling loop, stream requests or extra player. */
object ExoReportObserver {
    fun attach(player: ExoPlayer, context: String, sourceId: () -> String? = { null }) {
        PlaybackReports.begin(player, "ExoPlayer", context)
        var rebuffers = 0
        var ready = false
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
                PlaybackReports.begin(player, "ExoPlayer", context, sourceId())
                ready = false; rebuffers = 0
                val item = mediaItem?.localConfiguration
                PlaybackReports.update(player, mapOf("provider host" to ReportRedactor.host(item?.uri.toString()),
                    "stream type" to (item?.mimeType ?: "auto-detected")))
            }
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                val profile = runCatching { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(format) }.getOrNull()
                PlaybackReports.update(player, mapOf("video codec" to "${format.sampleMimeType} (${format.codecs})",
                    "container" to (format.containerMimeType ?: "not reported"), "coded size" to "${format.width}x${format.height}",
                    "codec profile/level" to (profile?.let { "${it.first}/${it.second}" } ?: "not reported"),
                    "pixel aspect ratio" to format.pixelWidthHeightRatio.toString(), "rotation" to format.rotationDegrees.toString(),
                    "frame rate" to format.frameRate.toString()))
            }
            override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
                PlaybackReports.update(player, mapOf("display size" to "${videoSize.width}x${videoSize.height}",
                    "pixel aspect ratio" to videoSize.pixelWidthHeightRatio.toString(), "rotation" to videoSize.unappliedRotationDegrees.toString()))
                PlaybackReports.event(player, "video size changed: ${videoSize.width}x${videoSize.height}")
            }
            override fun onSurfaceSizeChanged(eventTime: AnalyticsListener.EventTime, width: Int, height: Int) {
                PlaybackReports.update(player, mapOf("surface size" to "${width}x$height"))
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                val info = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == decoderName } }.getOrNull()
                val mode = if (Build.VERSION.SDK_INT >= 29 && info != null) {
                    if (info.isHardwareAccelerated) "hardware" else if (info.isSoftwareOnly) "software" else "vendor"
                } else "not reported by Android"
                PlaybackReports.update(player, mapOf("decoder" to decoderName, "decoder mode" to mode))
            }
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                PlaybackReports.update(player, mapOf("audio codec" to "${format.sampleMimeType} (${format.codecs})", "audio channels" to format.channelCount.toString()))
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                PlaybackReports.update(player, mapOf("audio decoder" to decoderName))
            }
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
                val pcm = androidx.media3.common.util.Util.isEncodingLinearPcm(audioTrackConfig.encoding)
                PlaybackReports.update(player, mapOf("audio passthrough" to
                    "${if (pcm) "decoded PCM" else "encoded output"}; encoding=${audioTrackConfig.encoding}; offload=${audioTrackConfig.offload}"))
            }
            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (state == Player.STATE_BUFFERING && ready) rebuffers++
                if (state == Player.STATE_READY) ready = true
                PlaybackReports.update(player, mapOf("state" to when (state) { Player.STATE_READY -> "ready"; Player.STATE_BUFFERING -> "buffering"; Player.STATE_ENDED -> "ended"; else -> "idle" }, "rebuffers" to rebuffers.toString()))
            }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                PlaybackReports.update(player, mapOf("last error" to "${error.errorCodeName}; ${error.cause?.javaClass?.name ?: error.javaClass.name}"))
            }
        })
    }

    private val frameFormats = java.util.WeakHashMap<Any, android.media.MediaFormat>()
    @Synchronized fun frame(owner: Any, format: android.media.MediaFormat?) {
        if (format == null) return
        if (frameFormats[owner] === format) return
        frameFormats[owner] = format
        fun value(key: String) = runCatching { if (format.containsKey(key)) format.getInteger(key).toString() else "?" }.getOrDefault("?")
        PlaybackReports.update(owner, mapOf("crop window" to "left=${value("crop-left")},top=${value("crop-top")},right=${value("crop-right")},bottom=${value("crop-bottom")}",
            "coded size" to "${value("width")}x${value("height")}"))
    }
}
