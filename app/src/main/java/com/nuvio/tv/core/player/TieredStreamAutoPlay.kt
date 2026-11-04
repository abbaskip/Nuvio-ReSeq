package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

data class StreamAutoPlaySnapshot(val streams: List<Stream>, val complete: Boolean = false)

data class StreamAutoPlayFallbackProgress(val remainingSeconds: Int, val fraction: Float)

/** One decision per search. Only the approved tier uses normal source/regex restrictions. */
class TieredStreamAutoPlay(
    private val settings: PlayerSettings,
    private val installedAddonNames: Set<String>,
    private val preferredBingeGroup: String? = null,
    private val bingeGroupOnly: Boolean = false,
    private val stopAtEmptyFallback: Boolean = false,
) {
    data class Decision(
        val stream: Stream? = null,
        val showPicker: Boolean = false,
        val progress: StreamAutoPlayFallbackProgress? = null,
        val finished: Boolean = false,
        val nextWakeMs: Long = 250L,
    )

    private val unlimited = settings.streamAutoPlayTimeoutSeconds == PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED
    private val instant = settings.streamAutoPlayTimeoutSeconds == 0
    private val approvedDeadlineMs = if (unlimited) Long.MAX_VALUE else settings.streamAutoPlayTimeoutSeconds.coerceIn(0, 30) * 1_000L
    private val fallbackDeadlineMs = if (settings.streamAutoPlayAnySourceFallbackEnabled && !unlimited) {
        PlayerSettings.normalizeAutoPlayFallbackSeconds(settings.streamAutoPlayTimeoutSeconds, settings.streamAutoPlayAnySourceFallbackSeconds) * 1_000L
    } else null
    private val hardDeadlineMs = maxOf(60_000L, (fallbackDeadlineMs ?: 0L) + 60_000L)
    private var previousStreams: List<Stream>? = null
    private var approved: Stream? = null
    private var anySource: Stream? = null
    private var immediateAddon: String? = null
    private var immediateSeenMs = 0L
    private var immediate: Stream? = null
    private var terminal: Decision? = null

    fun evaluate(snapshot: StreamAutoPlaySnapshot, elapsedMs: Long): Decision {
        terminal?.let { return it }
        if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) {
            return Decision(showPicker = true, finished = true).also { terminal = it }
        }
        val elapsed = elapsedMs.coerceAtLeast(0L)
        if (snapshot.streams !== previousStreams) {
            previousStreams = snapshot.streams
            anySource = snapshot.streams.firstOrNull(StreamAutoPlaySelector::isPlayable)
            val immediateStreams = if (settings.streamAutoPlayImmediateEnabled) snapshot.streams.filter {
                it.addonName in installedAddonNames && it.addonName in settings.streamAutoPlayImmediateAddons &&
                    it.iptvPlaylistId == null && it.serverTarget == null && StreamAutoPlaySelector.isPlayable(it)
            } else emptyList()
            val previousAddonChoice = immediateStreams.firstOrNull { it.addonName == immediateAddon }
            immediate = previousAddonChoice ?: immediateStreams.firstOrNull()
            if (immediate?.addonName != immediateAddon) {
                immediateAddon = immediate?.addonName
                immediateSeenMs = elapsed
            }
            if (instant || elapsed <= approvedDeadlineMs) {
                val candidates = if (bingeGroupOnly) snapshot.streams.filter {
                    it.behaviorHints?.bingeGroup == preferredBingeGroup && !preferredBingeGroup.isNullOrBlank()
                } else snapshot.streams
                val ordered = if (preferredBingeGroup.isNullOrBlank()) candidates else candidates.sortedBy {
                    it.behaviorHints?.bingeGroup != preferredBingeGroup
                }
                val approvedRulesConfigured = settings.streamAutoPlayMode != StreamAutoPlayMode.REGEX_MATCH ||
                    StreamAutoPlayPolicy.isRegexSelectionConfigured(settings.streamAutoPlayRegex)
                approved = if (!approvedRulesConfigured) null else StreamAutoPlaySelector.selectAutoPlayStream(
                    streams = ordered, mode = settings.streamAutoPlayMode,
                    regexPattern = settings.streamAutoPlayRegex, source = settings.streamAutoPlaySource,
                    installedAddonNames = installedAddonNames,
                    selectedAddons = settings.streamAutoPlaySelectedAddons,
                    selectedPlugins = settings.streamAutoPlaySelectedPlugins, sourceSettings = settings,
                )
            }
        }

        val immediateReadyAt = if (immediate != null) immediateSeenMs + IMMEDIATE_SETTLE_MS else Long.MAX_VALUE
        val currentApproved = approved?.let { candidate -> snapshot.streams.firstOrNull { current ->
            current.addonName == candidate.addonName && current.getStreamUrl() == candidate.getStreamUrl() &&
                current.infoHash == candidate.infoHash && current.fileIdx == candidate.fileIdx &&
                current.clientResolve == candidate.clientResolve && current.iptvPlaylistId == candidate.iptvPlaylistId &&
                current.ytId == candidate.ytId && current.serverTarget == candidate.serverTarget &&
                StreamAutoPlaySelector.isPlayable(current)
        } }
        val selected = when {
            immediate != null && elapsed >= immediateReadyAt -> immediate
            immediate != null -> null // Give the selected addon's batch its full settling window.
            (unlimited || elapsed >= approvedDeadlineMs || snapshot.complete) && currentApproved != null -> currentApproved
            fallbackDeadlineMs != null && elapsed >= fallbackDeadlineMs -> anySource
            else -> null
        }
        if (selected != null) return Decision(stream = selected, finished = true).also { terminal = it }

        val approvedExpired = !unlimited && elapsed >= approvedDeadlineMs
        val fallbackExpired = fallbackDeadlineMs != null && elapsed >= fallbackDeadlineMs
        val finished = (stopAtEmptyFallback && fallbackExpired && anySource == null) ||
            elapsed >= hardDeadlineMs || (snapshot.complete && immediate == null &&
            (fallbackDeadlineMs == null || fallbackExpired))
        val progress = if (approvedExpired && fallbackDeadlineMs != null && !finished) {
            StreamAutoPlayFallbackProgress(
                remainingSeconds = ceil((fallbackDeadlineMs - elapsed).coerceAtLeast(0L) / 1_000.0).toInt(),
                fraction = ((elapsed - approvedDeadlineMs).toFloat() / (fallbackDeadlineMs - approvedDeadlineMs)).coerceIn(0f, 1f),
            )
        } else null
        val nextDeadline = listOf(approvedDeadlineMs, fallbackDeadlineMs ?: Long.MAX_VALUE, immediateReadyAt, hardDeadlineMs)
            .filter { it > elapsed }.minOrNull() ?: (elapsed + 250L)
        return Decision(showPicker = approvedExpired || finished, progress = progress,
            finished = finished, nextWakeMs = (nextDeadline - elapsed).coerceIn(1L, 250L))
    }

    companion object {
        const val IMMEDIATE_SETTLE_MS = 500L

        fun isEnabled(settings: PlayerSettings): Boolean = settings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL &&
            (settings.streamAutoPlayImmediateEnabled ||
                (settings.streamAutoPlayAnySourceFallbackEnabled && settings.streamAutoPlayTimeoutSeconds != PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED))

        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun awaitSelection(
            settings: PlayerSettings,
            snapshots: Flow<StreamAutoPlaySnapshot>,
            installedAddonNames: Set<String>,
            preferredBingeGroup: String? = null,
            bingeGroupOnly: Boolean = false,
            stopAtEmptyFallback: Boolean = false,
            nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
            canSelect: () -> Boolean = { true },
            onProgress: (Decision) -> Unit = {},
        ): Stream? = coroutineScope {
            val startedAt = nowMs()
            val session = TieredStreamAutoPlay(settings, installedAddonNames, preferredBingeGroup, bingeGroupOnly, stopAtEmptyFallback)
            val updates = snapshots.conflate().produceIn(this)
            var snapshot = StreamAutoPlaySnapshot(emptyList())
            var closed = false
            try {
                while (true) {
                    ensureActive()
                    // Always consume a completed batch before a timer can choose an
                    // earlier partial list, including after returning to the app.
                    while (!closed) {
                        val queued = updates.tryReceive()
                        if (queued.isSuccess) {
                            snapshot = queued.getOrThrow()
                        } else {
                            if (queued.isClosed) {
                                queued.exceptionOrNull()?.let { throw it }
                                closed = true
                                snapshot = snapshot.copy(complete = true)
                            }
                            break
                        }
                    }
                    if (!canSelect()) {
                        delay(100L)
                        continue
                    }
                    val decision = session.evaluate(snapshot, nowMs() - startedAt)
                    onProgress(decision)
                    if (decision.finished) return@coroutineScope decision.stream
                    if (closed) {
                        delay(decision.nextWakeMs)
                    } else {
                        val update = withTimeoutOrNull(decision.nextWakeMs) { updates.receiveCatching() }
                        if (update != null) {
                            update.getOrNull()?.let { snapshot = it }
                            if (update.isClosed) {
                                update.exceptionOrNull()?.let { throw it }
                                closed = true
                                snapshot = snapshot.copy(complete = true)
                            }
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            } finally {
                updates.cancel()
            }
        }
    }
}
