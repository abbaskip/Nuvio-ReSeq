package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.SurfaceHolder
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.SubtitleStyleSettings
import `is`.xyz.mpv.Utils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

internal const val MPV_TRACK_LIST_PROPERTY = "track-list"
internal const val MPV_SID_PROPERTY = "sid"
internal const val MPV_SUB_TEXT_PROPERTY = "sub-text"
private val MPV_STYLED_SUBTITLE_CODECS = setOf(
    "subrip", "srt", "webvtt", "text", "mov_text", "microdvd", "mpl2", "subviewer", "subviewer1", "sami", "realtext"
)

class NuvioMpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NuvioBaseMpvView(context, attrs) {

    private var initialized = false
    private var hasQueuedInitialMedia = false
    private var lastMediaRequestKey: String? = null
    private var pendingInitialMediaUrl: String? = null
    private var pendingInitialStartOption: String? = null
    private var deferredLoadUntilSurface: Boolean = false
    private var requestedMediaUrl: String? = null
    private var pathAtMediaRequest: String? = null
    private var hardwareDecodeMode: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE
    private var hi10pGnextSoftwareFallbackActive = false
    private var appliedHi10pGnextSoftwareFallback: Boolean? = null
    private val reportPosted = AtomicBoolean(false)
    private val reportUpdate = Runnable {
        reportPosted.set(false)
        captureReport()
    }
    private val properties get() = mpv.properties

    internal fun isIdleActive() = initialized && properties.flag("idle-active") == true
    private var currentAspectMode: AspectMode = AspectMode.ORIGINAL
    private var pendingAspectRetryCount = 0
    private var styledSubtitlesEnabled = false
    private val aspectReapplyRunnable = Runnable {
        applyAspectModeInternal(currentAspectMode, allowRetry = true)
    }

    fun ensureInitialized() {
        if (initialized) return
        Utils.copyAssets(context)
        initialize(
            configDir = context.filesDir.path,
            cacheDir = context.cacheDir.path
        )
        initialized = true
    }

    fun setMedia(url: String, headers: Map<String, String>, startPositionMs: Long = 0L) {
        ensureInitialized()
        val requestKey = buildMediaRequestKey(url = url, headers = headers) +
            "#start=${startPositionMs.coerceAtLeast(0L)}"
        if (hasQueuedInitialMedia && requestKey == lastMediaRequestKey) {
            return
        }
        applyHeaders(headers)
        markMediaRequested(url)
        val startOption = startPositionMs
            .takeIf { it > 0L }
            ?.let {
                buildList {
                    add(String.format(Locale.US, "start=+%.3f", it / 1000.0))
                    // Avoid decoding forward from a distant keyframe before showing resumed Hi10P video.
                    if (hi10pGnextSoftwareFallbackActive) add("hr-seek=no")
                }.joinToString(",")
            }
        if (startOption != null && holder.surface?.isValid == true) {
            ensureSurfaceAttachedIfAlreadyAvailable()
            loadFileWithOptions(url, startOption)
            hasQueuedInitialMedia = true
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
            deferredLoadUntilSurface = false
        } else if (startOption != null) {
            pendingInitialMediaUrl = url
            pendingInitialStartOption = startOption
            deferredLoadUntilSurface = true
            hasQueuedInitialMedia = true
        } else if (hasQueuedInitialMedia) {
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
            if (holder.surface?.isValid == true) {
                ensureSurfaceAttachedIfAlreadyAvailable()
                mpv.command("loadfile", url, "replace")
                deferredLoadUntilSurface = false
            } else {
                playFile(url)
                deferredLoadUntilSurface = true
            }
        } else {
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
            playFile(url)
            ensureSurfaceAttachedIfAlreadyAvailable()
            deferredLoadUntilSurface = holder.surface?.isValid != true
            hasQueuedInitialMedia = true
        }
        lastMediaRequestKey = requestKey
        applyDefaultTrackSelectionForNewLoad()
        scheduleAspectModeRefresh(resetRetryCount = true)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        deferredLoadUntilSurface = false
        val url = pendingInitialMediaUrl ?: return
        val startOption = pendingInitialStartOption
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
        if (startOption != null) {
            loadFileWithOptions(url, startOption)
        } else {
            mpv.command("loadfile", url, "replace")
        }
    }

    /**
     * mpv's `loadfile` signature is `<url> [<flags> [<index> [<options>]]]`, so the per-file option
     * list belongs in the fifth argument. Passing it where `<index>` is expected makes mpv reject
     * the whole command and stay idle, i.e. resuming at a position would never load the file.
     */
    private fun loadFileWithOptions(url: String, options: String) {
        mpv.command("loadfile", url, "replace", LOADFILE_DEFAULT_INDEX, options)
    }

    fun setMediaUsingLoadfile(url: String, headers: Map<String, String>) {
        ensureInitialized()
        val requestKey = buildMediaRequestKey(url = url, headers = headers)
        applyHeaders(headers)
        markMediaRequested(url)
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
        if (holder.surface?.isValid == true) {
            ensureSurfaceAttachedIfAlreadyAvailable()
            mpv.command("loadfile", url, "replace")
            deferredLoadUntilSurface = false
        } else {
            playFile(url)
            deferredLoadUntilSurface = true
        }
        hasQueuedInitialMedia = true
        lastMediaRequestKey = requestKey
        applyDefaultTrackSelectionForNewLoad()
        scheduleAspectModeRefresh(resetRetryCount = true)
    }

    private fun ensureSurfaceAttachedIfAlreadyAvailable() {
        if (!initialized) return
        val currentHolder = holder
        val currentSurface = currentHolder.surface ?: return
        if (!currentSurface.isValid) return
        runCatching {
            // Some fallback transitions initialize mpv after the Surface is already alive.
            // In that path, SurfaceHolder callback may not fire again, so force attach.
            surfaceCreated(currentHolder)
        }.onFailure {
            Log.w(TAG, "Failed to force MPV surface attach: ${it.message}")
        }
    }

    private fun applyDefaultTrackSelectionForNewLoad() {
        runCatching {
            // Let mpv choose the default streams for every new media load.
            mpv.setPropertyString("aid", "auto")
            mpv.setPropertyString("sid", "auto")
            mpv.setPropertyBoolean("sub-visibility", !styledSubtitlesEnabled)
        }.onFailure {
            Log.w(TAG, "Failed to reset default A/V track selection: ${it.message}")
        }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        mpv.setPropertyBoolean("pause", paused)
    }

    fun isPlayingNow(): Boolean {
        if (!initialized) return false
        return properties.flag("pause") == false
    }

    fun isPausedForCacheNow(): Boolean {
        if (!initialized) return false
        return properties.flag("paused-for-cache") == true
    }

    fun demuxerCacheDurationSec(): Double {
        if (!initialized) return 0.0
        return properties.double("demuxer-cache-duration") ?: 0.0
    }

    fun isCoreIdleNow(): Boolean {
        if (!initialized) return false
        return properties.flag("core-idle") == true
    }

    fun markMediaRequested(url: String) {
        com.nuvio.tv.core.report.PlaybackReports.begin(this, "MPV", "Nuvio player")
        com.nuvio.tv.core.report.PlaybackReports.update(this, mapOf("provider host" to com.nuvio.tv.core.report.ReportRedactor.host(url)))
        val path = if (initialized) properties.string("path") else null
        pathAtMediaRequest = mpvPathBaselineForRequest(url, requestedMediaUrl, pathAtMediaRequest, path)
        requestedMediaUrl = url
    }

    val hasPendingInitialMedia: Boolean
        get() = pendingInitialMediaUrl != null || deferredLoadUntilSurface

    /** False while mpv still reports the path recorded at the last media request. */
    fun isPositionFromRequestedMedia(): Boolean {
        if (!initialized) return false
        return isMpvPositionFromMediaRequest(
            requestedUrl = requestedMediaUrl,
            pathAtRequest = pathAtMediaRequest,
            currentPath = properties.string("path")
        )
    }

    fun isEofReached(): Boolean {
        if (!initialized) return false
        return properties.flag("eof-reached") == true
    }

    fun seekToMs(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        mpv.setPropertyDouble("time-pos", seconds)
    }

    /** Exact even when the file was loaded with `hr-seek=no`, where [seekToMs] lands on a keyframe. */
    fun seekToMsExact(positionMs: Long) {
        if (!initialized) return
        val seconds = String.format(Locale.US, "%.3f", positionMs.coerceAtLeast(0L) / 1000.0)
        runCatching { mpv.command("seek", seconds, "absolute+exact") }
            .onFailure { Log.w(TAG, "Exact seek failed: ${it.message}") }
    }

    fun currentSubtitleText(): String? {
        if (!initialized) return null
        return properties.string(MPV_SUB_TEXT_PROPERTY)
    }

    fun syncStyledSubtitles(enabled: Boolean): String? {
        styledSubtitlesEnabled = enabled
        if (!initialized) return null
        return runCatching {
            val codec = properties.string("current-tracks/sub/codec")?.lowercase(Locale.US)
            val styled = enabled && codec != null && codec in MPV_STYLED_SUBTITLE_CODECS
            val sid = properties.string(MPV_SID_PROPERTY)
            if (sid != null && sid != "no" && sid != "false") {
                mpv.setPropertyBoolean("sub-visibility", !styled)
            }
            if (styled) properties.string("sub-text/ass") ?: properties.string(MPV_SUB_TEXT_PROPERTY) else null
        }.getOrNull()
    }

    fun currentPositionMs(): Long {
        if (!initialized) return 0L
        val timePos = properties.double("time-pos/full")
            ?: properties.double("time-pos")
            ?: 0.0
        val seconds = mpvPlaybackPositionSec(
            timePosSec = timePos,
            audioPtsSec = properties.double("audio-pts"),
            seeking = properties.flag("seeking") == true,
            audioDelaySec = properties.double("audio-delay") ?: 0.0
        )
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    fun durationMs(): Long {
        if (!initialized) return 0L
        val seconds = properties.double("duration/full") ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    /** Live HLS/DASH in mpv is typically reported as not seekable. VOD HLS is seekable. */
    fun isLiveStreamNow(): Boolean {
        if (!initialized) return false
        return properties.flag("seekable") == false
    }

    fun hasVideoTrackSelectedNow(): Boolean {
        if (!initialized) return false
        val vid = properties.string("vid")?.trim()
        return !vid.isNullOrBlank() && !vid.equals("no", ignoreCase = true)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!initialized) return
        mpv.setPropertyDouble("speed", speed.toDouble())
    }

    fun applyAudioAmplificationDb(db: Int) {
        if (!initialized) return
        val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        val linearScale = 10.0.pow(clampedDb / 20.0)
        val targetVolumePercent = (100.0 * linearScale).coerceIn(0.0, MPV_MAX_VOLUME_PERCENT)
        runCatching {
            mpv.setPropertyDouble("volume", targetVolumePercent)
        }.onFailure {
            Log.w(TAG, "Failed to apply audio amplification on mpv (db=$clampedDb): ${it.message}")
        }
    }

    fun applyAudioLanguagePreferences(languages: List<String>) {
        if (!initialized) return
        val normalized = languages
            .mapNotNull { language ->
                language.trim().takeIf { it.isNotBlank() }
            }
            .distinct()
        runCatching {
            // Empty value resets language preference back to default behavior.
            mpv.setPropertyString("alang", normalized.joinToString(","))
            // Re-run automatic audio selection with the latest preferences.
            mpv.setPropertyString("aid", "auto")
        }.onFailure {
            Log.w(TAG, "Failed to set audio language preference: ${it.message}")
        }
    }

    fun applyHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
        hardwareDecodeMode = mode
        if (!initialized || hi10pGnextSoftwareFallbackActive) return
        runCatching {
            mpv.setPropertyString("hwdec", mode.toMpvHwdecValue())
        }.onFailure {
            Log.w(TAG, "Failed to apply mpv hardware decode mode ($mode): ${it.message}")
        }
    }

    fun applyHi10pGnextSoftwareFallback(active: Boolean) {
        hi10pGnextSoftwareFallbackActive = active
        if (!initialized || appliedHi10pGnextSoftwareFallback == active) return
        runCatching {
            val videoOutput = if (active) MPV_VIDEO_OUTPUT_GPU_NEXT else MPV_VIDEO_OUTPUT_GPU
            val hardwareDecoder = if (active) MPV_HWDEC_DISABLED else hardwareDecodeMode.toMpvHwdecValue()
            setVo(videoOutput)
            mpv.setPropertyString("vo", videoOutput)
            mpv.setPropertyString("hwdec", hardwareDecoder)
            appliedHi10pGnextSoftwareFallback = active
        }.onFailure {
            Log.w(TAG, "Failed to apply mpv Hi10P G-NEXT SW fallback (active=$active): ${it.message}")
        }
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        if (!initialized) return
        runCatching {
            mpv.setPropertyDouble("sub-delay", delayMs / 1000.0)
        }.onFailure {
            Log.w(TAG, "Failed to set subtitle delay on mpv: ${it.message}")
        }
    }

    fun setAudioDelayMs(delayMs: Int) {
        if (!initialized) return
        runCatching {
            mpv.setPropertyDouble("audio-delay", audioDelayMsToSeconds(delayMs))
        }.onFailure {
            Log.w(TAG, "Failed to set audio delay on mpv (delayMs=$delayMs): ${it.message}")
        }
    }

    /**
     * Bluetooth A2DP/LE cannot carry encoded passthrough. Force a stereo PCM mix.
     * Mid-session route changes pass [reloadOutput] so AudioTrack follows the new device
     * without restarting video.
     */
    fun applyBluetoothAudioRoute(isBluetooth: Boolean, reloadOutput: Boolean = false) {
        if (!initialized) return
        val wasPaused = !isPlayingNow()
        runCatching {
            mpv.setPropertyString("audio-channels", MpvBluetoothAudioPolicy.audioChannels(isBluetooth))
            if (MpvBluetoothAudioPolicy.shouldClearAudioSpdif(isBluetooth)) {
                mpv.setPropertyString("audio-spdif", "")
            }
            if (reloadOutput) {
                reloadAudioOutput()
                if (wasPaused) {
                    mpv.setPropertyBoolean("pause", true)
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed to apply bluetooth audio route on mpv (bt=$isBluetooth): ${it.message}")
        }
    }

    private fun reloadAudioOutput() {
        mpv.submit {
            if (command("ao-reload") < 0) {
                val aid = getPropertyString("aid")
                if (!aid.isNullOrBlank() && !aid.equals("no", ignoreCase = true)) setPropertyString("aid", aid)
            }
        }
    }

    fun applyAspectMode(mode: AspectMode) {
        currentAspectMode = mode
        pendingAspectRetryCount = 0
        removeCallbacks(aspectReapplyRunnable)
        applyAspectModeInternal(mode, allowRetry = true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        captureReport()
        pendingAspectRetryCount = 0
        removeCallbacks(aspectReapplyRunnable)
        post {
            applyAspectModeInternal(currentAspectMode, allowRetry = true)
        }
    }

    private fun applyAspectModeInternal(mode: AspectMode, allowRetry: Boolean) {
        val viewAspect = readViewAspectRatio(width, height)
        val videoAspect = readVideoAspectRatio()
        val scale = resolveAspectScale(
            mode = mode,
            viewAspect = viewAspect,
            videoAspect = videoAspect
        )
        scaleX = scale.scaleX
        scaleY = scale.scaleY
        if (
            allowRetry &&
            aspectModeNeedsVideoAspect(mode) &&
            (viewAspect <= 0f || videoAspect == null || videoAspect <= 0f)
        ) {
            scheduleAspectModeRefresh(resetRetryCount = false)
        }
    }

    private fun scheduleAspectModeRefresh(resetRetryCount: Boolean) {
        if (resetRetryCount) {
            pendingAspectRetryCount = 0
        }
        removeCallbacks(aspectReapplyRunnable)
        if (pendingAspectRetryCount >= MAX_ASPECT_RETRY_COUNT) {
            return
        }
        val delayMs = if (pendingAspectRetryCount == 0) 0L else ASPECT_RETRY_DELAY_MS
        pendingAspectRetryCount += 1
        postDelayed(aspectReapplyRunnable, delayMs)
    }

    var subtitleLiftFraction: Double = 0.0

    fun applySubtitleStyle(style: SubtitleStyleSettings) {
        if (!initialized) return
        runCatching {
            val scale = subtitleScale(style)
            val clampedOffset = style.verticalOffset.coerceIn(
                SUBTITLE_VERTICAL_OFFSET_MIN,
                SUBTITLE_VERTICAL_OFFSET_MAX
            )
            val normalizedOffset = (clampedOffset - SUBTITLE_VERTICAL_OFFSET_MIN).toDouble() /
                (SUBTITLE_VERTICAL_OFFSET_MAX - SUBTITLE_VERTICAL_OFFSET_MIN).toDouble()
            val subPos = MPV_SUB_POS_AT_BOTTOM -
                (normalizedOffset * (MPV_SUB_POS_AT_BOTTOM - MPV_SUB_POS_AT_TOP))
            val subMarginY = (MPV_SUB_MARGIN_Y_MIN +
                (normalizedOffset * (MPV_SUB_MARGIN_Y_MAX - MPV_SUB_MARGIN_Y_MIN))).toInt()
            val outlineSize = when {
                !style.outlineEnabled -> 0.0
                isAssOrSsaSubtitleSelectedNow() -> style.outlineWidth.coerceIn(1, 6).toDouble()
                else -> 1.0
            }
            val backgroundAlpha = (style.backgroundColor ushr 24) and 0xFF
            val borderStyle = if (backgroundAlpha > 0) "background-box" else "outline-and-shadow"
            // In background-box mode, sub-shadow-offset controls the box padding/margin
            val shadowOffset = if (backgroundAlpha > 0) 5.0 else 0.0

            mpv.setPropertyDouble("sub-scale", scale)
            mpv.setPropertyBoolean("sub-bold", style.bold)
            mpv.setPropertyDouble("sub-outline-size", outlineSize)
            val liftedSubPos = if (subtitleLiftFraction > 0.0) {
                minOf(subPos, 100.0 * (1.0 - subtitleLiftFraction))
            } else {
                subPos
            }
            mpv.setPropertyDouble("sub-pos", liftedSubPos)
            mpv.setPropertyInt("sub-margin-y", subMarginY)
            mpv.setPropertyDouble("sub-shadow-offset", shadowOffset)
            mpv.setPropertyString("sub-border-style", borderStyle)
            mpv.setPropertyString("sub-color", toMpvColor(style.textColor))
            mpv.setPropertyString("sub-back-color", toMpvColor(style.backgroundColor))
            mpv.setPropertyString("sub-outline-color", toMpvColor(style.outlineColor))
            mpv.setPropertyBoolean("sub-filter-sdh", style.stripSdh)
            mpv.setPropertyBoolean("sub-filter-sdh-harder", style.stripSdh)
        }.onFailure {
            Log.w(TAG, "Failed to apply subtitle style on mpv: ${it.message}")
        }
    }

    fun readPlayerMediaInfo(): PlayerMediaInfo? {
        if (!initialized) return null
        return mpvMediaInfo { name ->
            properties.string(name)?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    /** True for PQ/HLG video, false for any other transfer, null until mpv knows the video params. */
    fun readVideoIsHdr(): Boolean? {
        if (!initialized) return null
        val gamma = properties.string("video-params/gamma")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return gamma == "pq" || gamma == "hlg"
    }

    internal fun subtitleTextMetrics(style: SubtitleStyleSettings): SubtitleTextMetrics? {
        if (!initialized || width <= 0 || height <= 0 || isAssOrSsaSubtitleSelectedNow()) return null
        val fontSize = properties.double("sub-font-size") ?: return null
        val unitPx = height / MPV_SUBTITLE_REFERENCE_HEIGHT
        val textSizePx = if (style.usesStyledRenderer) {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                SUBTITLE_BASE_TEXT_SP * (style.size / 100f),
                resources.displayMetrics
            )
        } else {
            (fontSize * subtitleScale(style)).toFloat() * unitPx
        }
        val marginXPx = (properties.int("sub-margin-x") ?: MPV_SUBTITLE_DEFAULT_MARGIN_X) * unitPx
        val location = IntArray(2)
        getLocationInWindow(location)
        return SubtitleTextMetrics(
            textSizePx = textSizePx,
            bold = style.bold,
            scaleX = 1f,
            innerPaddingPx = textSizePx * MPV_SUBTITLE_INNER_PADDING_RATIO,
            wrapWidthPx = (width - marginXPx * 2f).coerceAtLeast(0f),
            centerXPx = location[0] + width / 2f
        )
    }

    private fun subtitleScale(style: SubtitleStyleSettings): Double = (style.size / 100.0).coerceIn(0.5, 3.0)

    private fun isAssOrSsaSubtitleSelectedNow(): Boolean {
        val codec = properties.string("current-tracks/sub/codec")?.lowercase(Locale.ROOT) ?: return false
        return codec.contains("ass") || codec.contains("ssa")
    }

    fun selectAudioTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyInt("aid", trackId)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to select audio track id=$trackId: ${it.message}")
            false
        }
    }

    fun selectSubtitleTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyBoolean("sub-visibility", !styledSubtitlesEnabled)
            mpv.setPropertyInt("sid", trackId)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to select subtitle track id=$trackId: ${it.message}")
            false
        }
    }

    fun disableSubtitles(): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.setPropertyString("sid", "no")
            mpv.setPropertyBoolean("sub-visibility", false)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to disable subtitles: ${it.message}")
            false
        }
    }

    fun addAndSelectExternalSubtitle(
        url: String,
        title: String? = null,
        language: String? = null
    ): Boolean {
        if (!initialized) return false
        if (url.isBlank()) return false
        return runCatching {
            // "cached" avoids duplicate re-loads for the same external subtitle.
            val safeTitle = title?.takeIf { it.isNotBlank() }
            val safeLanguage = language?.takeIf { it.isNotBlank() }
            when {
                safeTitle != null && safeLanguage != null ->
                    mpv.command("sub-add", url, "cached", safeTitle, safeLanguage)
                safeTitle != null ->
                    mpv.command("sub-add", url, "cached", safeTitle)
                else ->
                    mpv.command("sub-add", url, "cached")
            }
            mpv.setPropertyBoolean("sub-visibility", !styledSubtitlesEnabled)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to add external subtitle: ${it.message}")
            false
        }
    }

    /**
     * Adds [path] (a retimed copy of a subtitle) as a new external track and selects it, then
     * removes [replaceTrackId], an earlier copy, so repeated retiming doesn't pile up tracks.
     * Returns the new track's id, or null when it could not be added.
     */
    suspend fun replaceExternalSubtitle(
        path: String,
        title: String?,
        language: String?,
        replaceTrackId: Int?,
    ): Int? {
        if (!initialized || path.isBlank()) return null
        val visible = !styledSubtitlesEnabled
        return mpv.result {
            val args = mutableListOf("sub-add", path, "select")
            title?.takeIf { it.isNotBlank() }?.let {
                args += it
                language?.takeIf { lang -> lang.isNotBlank() }?.let(args::add)
            }
            if (command(*args.toTypedArray()) < 0) return@result null
            setPropertyBoolean("sub-visibility", visible)
            val added = getPropertyString("sid")?.toIntOrNull() ?: return@result null
            if (replaceTrackId != null && replaceTrackId != added) command("sub-remove", replaceTrackId.toString())
            added
        }
    }

    /** Removes an external subtitle track added with [replaceExternalSubtitle]. */
    fun removeSubtitleTrack(trackId: Int): Boolean {
        if (!initialized) return false
        return runCatching {
            mpv.command("sub-remove", trackId.toString())
            true
        }.getOrElse {
            Log.w(TAG, "Failed to remove subtitle track id=$trackId: ${it.message}")
            false
        }
    }

    /** The selected subtitle track id, or null when none is selected. */
    fun selectedSubtitleTrackId(): Int? {
        if (!initialized) return null
        return properties.string("sid")?.toIntOrNull()
    }

    fun applySubtitleLanguagePreferences(preferred: String, secondary: String?) {
        if (!initialized) return
        val languages = listOfNotNull(
            preferred.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) },
            secondary?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        )
        if (languages.isEmpty()) {
            disableSubtitles()
            return
        }
        runCatching {
            mpv.setPropertyString("slang", languages.joinToString(","))
        }.onFailure {
            Log.w(TAG, "Failed to set subtitle language preference: ${it.message}")
        }
    }

    fun readTrackSnapshot(): MpvTrackSnapshot {
        if (!initialized) return MpvTrackSnapshot(emptyList(), emptyList())
        val audio = mutableListOf<MpvTrack>()
        val subtitles = mutableListOf<MpvTrack>()
        val audioId = properties.string("aid")?.toIntOrNull() ?: properties.int("current-tracks/audio/id")
        val subId = properties.string("sid")?.toIntOrNull() ?: properties.int("current-tracks/sub/id")
        for (node in properties.list(MPV_TRACK_LIST_PROPERTY)) {
            val track = node.asMap() ?: continue
            val type = track["type"]?.asString() ?: continue
            if (type != "audio" && type != "sub") continue
            val id = track["id"]?.asInt()?.toInt() ?: continue
            val title = track["title"]?.asString()?.trim()?.takeIf(String::isNotBlank)
            val language = track["lang"]?.asString()?.trim()?.takeIf(String::isNotBlank)
            val isAudio = type == "audio"
            val fallback = if (isAudio) com.nuvio.tv.R.string.player_track_audio_fallback
                else com.nuvio.tv.R.string.player_track_subtitle_fallback
            val item = MpvTrack(
                id = id, type = type, name = title ?: language ?: context.getString(fallback, id),
                language = language, codec = track["codec"]?.asString(),
                channelCount = if (isAudio) (track["demux-channel-count"] ?: track["audio-channels"]
                    ?: track["channels"])?.asInt()?.toInt() else null,
                isSelected = (if (isAudio) audioId else subId)?.let { it == id } ?: (track["selected"]?.asBoolean() == true),
                isForced = !isAudio && (track["forced"]?.asBoolean() == true ||
                    listOfNotNull(title, language).any { it.contains("forced", ignoreCase = true) }),
                isExternal = track["external"]?.asBoolean() == true
            )
            if (isAudio) audio += item else subtitles += item
        }
        return MpvTrackSnapshot(audio, subtitles)
    }

    fun readChapters(): List<PlayerChapter> {
        if (!initialized) return emptyList()
        return properties.list("chapter-list").mapNotNull { node ->
            val chapter = node.asMap() ?: return@mapNotNull null
            val seconds = chapter["time"]?.asDouble() ?: return@mapNotNull null
            PlayerChapter((seconds * 1000.0).roundToLong(), chapter["title"]?.asString()?.trim())
        }
    }

    fun releasePlayer() {
        if (mpvInstance == null) return
        removeCallbacks(aspectReapplyRunnable)
        runCatching { destroy() }
            .onFailure { Log.w(TAG, "Failed to destroy libmpv view cleanly: ${it.message}") }
        initialized = false
        hasQueuedInitialMedia = false
        lastMediaRequestKey = null
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
        deferredLoadUntilSurface = false
        requestedMediaUrl = null
        pathAtMediaRequest = null
        appliedHi10pGnextSoftwareFallback = null
        removeCallbacks(reportUpdate)
        reportPosted.set(false)
    }

    override fun initOptions() {
        mpv.command("apply-profile", "fast")
        mpv.setOptionString("msg-level", "ffmpeg=warn,vd=warn,ad=warn,vo=warn,ao=warn,demux=warn,lavf=warn,cplayer=warn,autoconvert=warn,hwupload=warn")
        setVo(if (hi10pGnextSoftwareFallbackActive) MPV_VIDEO_OUTPUT_GPU_NEXT else MPV_VIDEO_OUTPUT_GPU)
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("user-agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        // Preserve native ASS/SSA styling behavior on MPV.
        mpv.setOptionString("sub-ass-override", "no")
        mpv.setOptionString("sub-codepage", "auto:utf-8")
        mpv.setOptionString("sub-font", "Roboto")
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-force-margins", "yes")
        mpv.setOptionString(
            "hwdec",
            if (hi10pGnextSoftwareFallbackActive) MPV_HWDEC_DISABLED else hardwareDecodeMode.toMpvHwdecValue()
        )
        appliedHi10pGnextSoftwareFallback = hi10pGnextSoftwareFallbackActive
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        mpv.setOptionString("input-default-bindings", "yes")
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("softvol", "yes")
        mpv.setOptionString("volume-max", MPV_MAX_VOLUME_PERCENT.toInt().toString())
    }

    override fun postInitOptions() {
        mpv.setOptionString("save-position-on-quit", "no")
    }

    override fun onMpvFormatChanged() {
        if (reportPosted.compareAndSet(false, true)) post(reportUpdate)
    }

    private fun applyHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) {
            mpv.setPropertyString("http-header-fields", "")
            return
        }
        val raw = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = ",") { (key, value) ->
                val escapedHeader = "$key: $value"
                    .replace("\\", "\\\\")
                    .replace(",", "\\,")
                escapedHeader
            }
        mpv.setPropertyString("http-header-fields", raw)
    }

    private fun buildMediaRequestKey(url: String, headers: Map<String, String>): String {
        val normalizedHeaders = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = "|") { "${it.key.trim()}:${it.value.trim()}" }
        return "$url#$normalizedHeaders"
    }

    private fun MpvHardwareDecodeMode.toMpvHwdecValue(): String {
        return when (this) {
            MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> "mediacodec,mediacodec-copy"
            MpvHardwareDecodeMode.AUTO_SAFE -> "auto-safe"
            MpvHardwareDecodeMode.HARDWARE_COPY -> "mediacodec-copy"
            MpvHardwareDecodeMode.HARDWARE_DIRECT -> "mediacodec"
            MpvHardwareDecodeMode.DISABLED -> "no"
        }
    }

    private fun toMpvColor(color: Int): String {
        return String.format(Locale.US, "#%08X", color)
    }

    private fun applyCoverAspectScale() {
        val viewAspect = if (width > 0 && height > 0) {
            width.toFloat() / height.toFloat()
        } else {
            0f
        }
        val videoAspect = readVideoAspectRatio()

        if (videoAspect != null && videoAspect > 0f && viewAspect > 0f) {
            if (videoAspect > viewAspect) {
                scaleX = 1.0f
                scaleY = videoAspect / viewAspect
            } else {
                scaleX = viewAspect / videoAspect
                scaleY = 1.0f
            }
            return
        }

        // Fallback to a visible zoom when video metadata/aspect is unavailable.
        scaleX = MPV_COVER_FALLBACK_SCALE
        scaleY = MPV_COVER_FALLBACK_SCALE
    }

    private fun readVideoAspectRatio(): Float? {
        if (!initialized) return null

        val directAspect = runCatching {
            properties.double("video-out-params/aspect")
                ?: properties.double("video-params/aspect")
        }.getOrNull()
        if (directAspect != null && directAspect > 0.0) {
            return directAspect.toFloat()
        }

        val width = runCatching {
            properties.int("video-out-params/dw")
                ?: properties.int("video-params/w")
        }.getOrNull() ?: return null
        val height = runCatching {
            properties.int("video-out-params/dh")
                ?: properties.int("video-params/h")
        }.getOrNull() ?: return null
        if (width <= 0 || height <= 0) return null

        return width.toFloat() / height.toFloat()
    }

    /** Both automatic capture and the Report action use the last delivered values. */
    fun captureReport() {
        if (!initialized) return
        val media = properties.takeIf { isPositionFromRequestedMedia() }
        fun value(key: String) = media?.string(key) ?: "not reported"
        com.nuvio.tv.core.report.PlaybackReports.update(this, mapOf(
            "stream type" to value("file-format"), "container" to value("file-format"),
            "video codec" to value("video-codec"), "codec profile/level" to "not exposed by MPV",
            "coded size" to "${value("video-params/w")}x${value("video-params/h")}",
            "display size" to "${value("video-out-params/dw")}x${value("video-out-params/dh")}",
            "crop window" to "x=${value("video-params/crop-x")},y=${value("video-params/crop-y")},w=${value("video-params/crop-w")},h=${value("video-params/crop-h")}",
            "pixel aspect ratio" to value("video-params/par"), "rotation" to value("video-params/rotate"),
            "frame rate" to value("container-fps"), "decoder" to value("video-codec"), "decoder mode" to value("hwdec-current"),
            "surface size" to "${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}", "view size" to "${width}x$height",
            "aspect/zoom" to currentAspectMode.name, "letterbox mask" to "MPV video output",
            "audio codec" to value("audio-codec-name"), "audio channels" to value("audio-params/channel-count"),
            "audio passthrough" to value("audio-out-params/format")))
    }

    companion object {
        private const val TAG = "NuvioMpvSurfaceView"
        private const val MPV_SUBTITLE_REFERENCE_HEIGHT = 720f
        private const val MPV_SUBTITLE_DEFAULT_MARGIN_X = 25
        private const val MPV_SUBTITLE_INNER_PADDING_RATIO = 0.125f
        private const val MPV_VIDEO_OUTPUT_GPU = "gpu"
        private const val MPV_VIDEO_OUTPUT_GPU_NEXT = "gpu-next"
        private const val MPV_HWDEC_DISABLED = "no"
        /** `loadfile` insertion index; only meaningful for insert-at flags, -1 is mpv's default. */
        private const val LOADFILE_DEFAULT_INDEX = "-1"
        private const val MPV_COVER_FALLBACK_SCALE = 1.15f
        private const val MPV_MAX_VOLUME_PERCENT = 400.0
        private const val ASPECT_RETRY_DELAY_MS = 120L
        private const val MAX_ASPECT_RETRY_COUNT = 10
        private const val SUBTITLE_VERTICAL_OFFSET_MIN = -20
        private const val SUBTITLE_VERTICAL_OFFSET_MAX = 50
        private const val MPV_SUB_POS_AT_BOTTOM = 103.4
        private const val MPV_SUB_POS_AT_TOP = 72.4
        private const val MPV_SUB_MARGIN_Y_MIN = 0
        private const val MPV_SUB_MARGIN_Y_MAX = 60
    }
}

data class MpvTrackSnapshot(
    val audioTracks: List<MpvTrack>,
    val subtitleTracks: List<MpvTrack>
)

data class MpvTrack(
    val id: Int,
    val type: String,
    val name: String,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val isSelected: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean
)

/**
 * The path that position readings for [url] must move off. Null means none: nothing was loaded,
 * or [url] repeats a request whose media has already loaded. A repeat of a pending request keeps
 * its baseline.
 */
internal fun mpvPathBaselineForRequest(
    url: String,
    requestedUrl: String?,
    pathAtRequest: String?,
    currentPath: String?
): String? {
    if (url != requestedUrl) return currentPath?.takeIf { it.isNotEmpty() }
    return if (isMpvPositionFromMediaRequest(url, pathAtRequest, currentPath)) null else pathAtRequest
}

/**
 * mpv can keep reporting the previous file's properties until a `loadfile replace` starts the new one.
 * Readings count once `path` equals the requested URL, or differs from the path seen at request
 * time, since a URL that mpv loads as a playlist reports its entry as `path`.
 */
internal fun isMpvPositionFromMediaRequest(
    requestedUrl: String?,
    pathAtRequest: String?,
    currentPath: String?
): Boolean {
    if (requestedUrl == null) return true
    if (currentPath.isNullOrEmpty()) return false
    return currentPath == requestedUrl || currentPath != pathAtRequest
}

/** How far video may trail the audio clock outside a seek before its position is distrusted. */
internal const val MPV_VIDEO_PTS_GLITCH_MIN_SEC = 10.0

/**
 * h264_mediacodec stamps a packet that has no PTS with 0 instead of leaving it unset. On streams
 * with such packets (seen on some HLS hosts) mpv shows that frame as `time-pos` 0 while audio keeps
 * playing at the real position, so the position snaps to the start for a moment: the Skip Intro
 * button flashes mid-episode and a seek made then is counted from 0. When the video position is far
 * behind a running audio clock outside a seek, the audio clock holds the real position.
 */
internal fun mpvPlaybackPositionSec(
    timePosSec: Double,
    audioPtsSec: Double?,
    seeking: Boolean,
    audioDelaySec: Double
): Double {
    if (audioPtsSec == null || seeking) return timePosSec
    val glitchMinSec = MPV_VIDEO_PTS_GLITCH_MIN_SEC + abs(audioDelaySec)
    return if (audioPtsSec - timePosSec > glitchMinSec) audioPtsSec else timePosSec
}
