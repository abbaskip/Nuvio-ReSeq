package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SOURCE_FALLBACK_POLL_COUNT = 30
private const val SOURCE_FALLBACK_POLL_INTERVAL_MS = 500L

/**
 * Treats a source that shows no first frame within the configured start-up time as failed, so it
 * falls back to the next matching source. It has an effect only where that fallback applies. It
 * runs for both engines; the mpv startup watchdog keeps its own limits, so whichever fires first
 * starts the fallback.
 */
internal fun PlayerRuntimeController.scheduleSourceStartupTimeout() {
    sourceStartupTimeoutJob?.cancel()
    val url = currentStreamUrl
    sourceStartupTimeoutJob = scope.launch {
        val timeoutSeconds = streamAutoPlayFallbackStartupTimeoutSeconds
        delay(timeoutSeconds * 1_000L)
        if (hasRenderedFirstFrame || currentStreamUrl != url) return@launch
        if (_uiState.value.error != null || isInBackground) return@launch
        // The last source allowed keeps loading, as it would without the fallback.
        if (sourceFallbackAttempts >= streamAutoPlayFallbackMaxSources) return@launch
        val message = context.getString(R.string.player_error_source_startup_timeout, timeoutSeconds)
        if (tryFallbackToNextSourceAfterError(message)) {
            Log.w(PlayerRuntimeController.TAG, "Source showed no first frame in ${timeoutSeconds}s, falling back")
        }
    }
}

/**
 * Starts the fallback over for a stream that replaced the playing one in place, such as the next
 * episode. Only a stream auto source selection picked falls back, [autoSelection] then holds the
 * sources tried before it, and the count belongs to the episode the sources were tried for.
 */
internal fun PlayerRuntimeController.restartSourceFallback(
    playbackUrl: String,
    autoSelection: SourceFallbackHandoff.TriedSources?
) {
    sourceFallbackJob?.cancel()
    linkRefreshAttempts = 0
    linkRefreshAttemptedUrl = null
    autoSelectedSourceUrl = playbackUrl.takeIf { url ->
        autoSelection != null && url.isNotBlank() && !url.startsWith("torrent://")
    }
    sourceFallbackAttempts = autoSelection?.attempts ?: 0
    sourceFallbackPendingStream = null
    sourceFallbackTriedKeys.clear()
    autoSelection?.let { tried -> sourceFallbackTriedKeys += tried.keys }
}

/** Shows [errorMessage] as the playback error unless the next matching source is tried instead. */
internal fun PlayerRuntimeController.showErrorUnlessSourceFallback(errorMessage: String) {
    if (tryFallbackToNextSourceAfterError(errorMessage)) return
    _uiState.update {
        it.copy(
            error = errorMessage,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false
        )
    }
}

/**
 * When the auto-selected source fails before playback starts, quietly plays the next source that
 * auto source selection would pick, up to the configured number of further sources. It never
 * applies to a source the user picked, and it excludes the engine switch on error.
 *
 * Returns `true` when a fallback was started and the error must not be shown yet.
 */
internal fun PlayerRuntimeController.tryFallbackToNextSourceAfterError(
    errorMessage: String,
    onExhausted: ((String) -> Unit)? = null
): Boolean {
    if (sourceFallbackJob?.isActive == true) return true
    if (!streamAutoPlayFallbackActive || isMixPlayback || isInBackground || isPartyControlLocked ||
        _playbackTimeline.value.isLive || userPausedManually) return false
    val failedUrl = currentStreamUrl
    if (failedUrl.isBlank()) return false
    if (sourceFallbackAttempts >= streamAutoPlayFallbackMaxSources) {
        // The last source tried was saved for reuse when it started playing, but it failed.
        streamCacheKey?.let { key -> scope.launch { streamLinkCacheDataStore.remove(key, profileId) } }
        return false
    }
    val expectedVideoId = currentVideoId
    val videoId = currentVideoId ?: contentId ?: return false

    val state = _uiState.value
    val failedSource = sourceFallbackPendingStream
    val failedAddonName = failedSource?.addonName ?: currentAddonName
    val failedInfoHash = failedSource?.getEffectiveInfoHash() ?: state.currentStreamInfoHash
    val failedKey = failedSource?.sourceFallbackKey() ?: "url:$failedUrl"
    val resumePosition = sourceSwitchResumePosition(false, hasRenderedFirstFrame, currentPlaybackPositionMs(),
        _playbackTimeline.value.currentPosition, state.pendingSeekPosition, pendingResumeProgress?.position)
    sourceFallbackTriedKeys += "url:$failedUrl"
    val season = currentSeason
    val episode = currentEpisode
    fun stillOwnsPlayback() = currentStreamUrl == failedUrl && currentVideoId == expectedVideoId && currentSeason == season && currentEpisode == episode

    sourceFallbackJob = scope.launch {
        showRecoveryOverlay()
        // Joins the shared source search, so results the stream screen already found are reused.
        loadSourceStreams(forceRefresh = false)
        val settings = playerSettingsDataStore.playerSettings.first()
        val installedAddonNames = streamAddonsFor(videoId).map { it.displayName }.toSet()

        var polls = 0
        while (sourceFallbackAttempts < streamAutoPlayFallbackMaxSources) {
            if (!stillOwnsPlayback()) return@launch
            val sources = _uiState.value
            val next = nextSourceFallbackCandidate(
                streams = sources.sourceAllStreams,
                settings = settings,
                installedAddonNames = installedAddonNames,
                triedKeys = sourceFallbackTriedKeys,
                failedAddonName = failedAddonName,
                failedInfoHash = failedInfoHash,
                failedKey = failedKey
            )
            if (next == null) {
                val searchDone = !sources.isLoadingSourceStreams &&
                    (sources.sourceAllStreams.isNotEmpty() || sources.sourceStreamsError != null)
                if (searchDone || polls >= SOURCE_FALLBACK_POLL_COUNT) break
                delay(SOURCE_FALLBACK_POLL_INTERVAL_MS)
                polls++
                continue
            }
            sourceFallbackTriedKeys += next.sourceFallbackKey()
            sourceFallbackAttempts++

            val playable = (directDebridResolver.resolveToPlayableStream(next, season, episode)
                as? DirectDebridPlayableResult.Success)?.stream
            val url = playable?.getStreamUrl()
            if (!stillOwnsPlayback()) return@launch
            if (playable == null || !StreamAutoPlaySelector.isPlayable(playable) || url == failedUrl) {
                Log.d(PlayerRuntimeController.TAG, "Fallback source from ${next.addonName} has no playable link, trying the next one")
                continue
            }
            Log.i(
                PlayerRuntimeController.TAG,
                "Source failed to start, falling back to ${playable.name ?: playable.addonName} from ${playable.addonName}"
            )
            sourceFallbackPendingStream = next
            linkRefreshAttempts = 0
            linkRefreshAttemptedUrl = null
            sourceFallbackJob = null
            switchToSourceStream(playable, resumePosition, fromFailureFallback = true)
            autoSelectedSourceUrl = currentStreamUrl
            sourceFallbackTriedKeys += "url:$currentStreamUrl"
            return@launch
        }

        // The last source that failed may have been saved for reuse.
        if (!stillOwnsPlayback()) return@launch
        streamCacheKey?.let { streamLinkCacheDataStore.remove(it, profileId) }
        sourceFallbackJob = null
        if (onExhausted != null) {
            onExhausted(errorMessage)
            return@launch
        }
        _uiState.update {
            it.copy(
                error = errorMessage,
                isBuffering = false,
                showLoadingOverlay = false,
                showPauseOverlay = false
            )
        }
    }
    return true
}

/**
 * The best stream auto source selection would pick from [streams], leaving out the sources
 * already tried and other copies of the torrent that failed.
 */
internal fun nextSourceFallbackCandidate(
    streams: List<Stream>,
    settings: PlayerSettings,
    installedAddonNames: Set<String>,
    triedKeys: Set<String>,
    failedAddonName: String?,
    failedInfoHash: String?,
    failedKey: String? = null
): Stream? {
    val exactAnchor = streams.indexOfFirst { stream ->
        (failedKey != null && stream.sourceFallbackKey() == failedKey) ||
            (failedInfoHash != null && stream.addonName == failedAddonName &&
                stream.getEffectiveInfoHash().equals(failedInfoHash, ignoreCase = true))
    }
    val anchor = if (exactAnchor >= 0) exactAnchor else streams.indexOfLast {
        it.addonName == failedAddonName && it.sourceFallbackKey() in triedKeys
    }
    val failed = streams.getOrNull(anchor) ?: streams.firstOrNull { it.addonName == failedAddonName }
    fun addonTier(stream: Stream) = stream.iptvPlaylistId != null || stream.serverTarget != null ||
        stream.addonName in installedAddonNames || stream.isDirectDebrid()
    val isAddonTier = failed?.let(::addonTier) ?: (failedAddonName in installedAddonNames)
    val immediateTier = settings.streamAutoPlayImmediateEnabled &&
        failedAddonName in settings.streamAutoPlayImmediateAddons && failedAddonName in installedAddonNames
    return streams.drop(anchor + 1).firstOrNull { stream ->
        val sameTier = if (immediateTier) stream.addonName in settings.streamAutoPlayImmediateAddons &&
            stream.addonName in installedAddonNames else addonTier(stream) == isAddonTier
        sameTier && StreamAutoPlaySelector.isPlayable(stream) && stream.sourceFallbackKey() !in triedKeys &&
            !(failedInfoHash != null && stream.addonName == failedAddonName &&
                stream.getEffectiveInfoHash().equals(failedInfoHash, ignoreCase = true))
    }
}

/** Identifies a source across fresh, prepared and resolved copies of the same stream. */
internal fun Stream.sourceFallbackKey(): String =
    usenet?.identity?.let { "usenet:$addonName:$it" }
        ?: serverTarget?.let { "server:$it" }
        ?: ytId?.let { "youtube:$addonName:$it" }
        ?: getEffectiveInfoHash()?.lowercase()?.let { hash -> "torrent:$addonName:$hash:${getEffectiveFileIdx() ?: -1}" }
        ?: getStreamUrl()?.let { url -> "url:$url" }
        ?: "meta:$addonName:${name.orEmpty()}:${description.orEmpty()}"
