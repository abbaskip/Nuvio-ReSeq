package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.player.StreamAutoPlaySnapshot
import com.nuvio.tv.core.player.TieredStreamAutoPlay
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.displayEpisode
import com.nuvio.tv.domain.model.displaySeason
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

internal fun PlayerRuntimeController.cancelSourceAutoPlay() {
    val wasPending = nextEpisodeAutoPlayJob?.isActive == true || _uiState.value.autoPlayFallback != null
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    _uiState.update { state ->
        state.copy(autoPlayFallback = null,
            isLoadingEpisodeStreams = if (wasPending && state.showEpisodeStreams) false else state.isLoadingEpisodeStreams,
            postPlayDismissedForCurrentEpisode = wasPending || state.postPlayDismissedForCurrentEpisode,
            postPlayMode = state.postPlayMode.takeUnless { it is PostPlayMode.AutoPlay && it.searching })
    }
}

internal suspend fun PlayerRuntimeController.playNextEpisodeWithTiers(
    nextVideo: Video,
    settings: PlayerSettings,
    installedAddonOrder: List<String>,
    preferredServerNames: Set<String>,
    bingeGroupOnly: Boolean,
    userInitiated: Boolean,
    pickerInitiallyVisible: Boolean = false,
    forceRefresh: Boolean = false,
) = coroutineScope {
    val snapshots = MutableStateFlow(StreamAutoPlaySnapshot(emptyList()))
    var pickerVisible = pickerInitiallyVisible
    var lastError: String? = null
    var availableAddons = emptyList<String>()

    fun updatePicker() {
        if (!pickerVisible || !isActive) return
        val snapshot = snapshots.value
        _uiState.update { state ->
            val filter = state.episodeSelectedAddonFilter
            state.copy(
                showEpisodesPanel = true, showEpisodeStreams = true, showSourcesPanel = false,
                showControls = true, postPlayMode = null,
                isLoadingEpisodeStreams = !snapshot.complete,
                episodeStreamsError = lastError.takeIf { snapshot.streams.isEmpty() },
                episodeAllStreams = snapshot.streams,
                episodeFilteredStreams = if (filter == null) snapshot.streams else snapshot.streams.filter { it.addonName == filter },
                episodeAvailableAddons = availableAddons,
                episodeSourceChips = availableAddons.map { SourceChipItem(name = it, status = SourceChipStatus.SUCCESS) },
                episodeStreamsForVideoId = nextVideo.id,
                episodeStreamsSeason = nextVideo.displaySeason,
                episodeStreamsEpisode = nextVideo.displayEpisode,
                episodeStreamsTitle = nextVideo.title,
                episodesSelectedSeason = nextVideo.displaySeason ?: state.episodesSelectedSeason,
            )
        }
    }

    _uiState.update { it.copy(autoPlayFallback = null, episodeSelectedAddonFilter = null) }
    updatePicker()
    val search = launch {
        try {
            streamRepository.getStreamsFromAllAddons(
                type = contentType ?: return@launch,
                videoId = nextVideo.id, season = nextVideo.season, episode = nextVideo.episode,
                forceRefresh = forceRefresh,
                contentContext = vodContentContext(nextVideo),
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val ordered = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder, preferredServerNames)
                        availableAddons = ordered.map { it.addonName }
                        snapshots.value = StreamAutoPlaySnapshot(ordered.flatMap { it.streams })
                        lastError = null
                        updatePicker()
                    }
                    is NetworkResult.Error -> { lastError = result.message; updatePicker() }
                    NetworkResult.Loading -> Unit
                }
            }
        } finally {
            snapshots.update { it.copy(complete = true) }
            updatePicker()
        }
    }
    try {
        val selected = TieredStreamAutoPlay.awaitSelection(
            settings = settings,
            snapshots = snapshots,
            installedAddonNames = installedAddonOrder.toSet(),
            preferredBingeGroup = currentStreamBingeGroup.takeIf { settings.streamAutoPlayPreferBingeGroupForNextEpisode },
            bingeGroupOnly = bingeGroupOnly,
            canSelect = { !isPartyControlLocked },
            onProgress = { decision ->
                if (decision.showPicker) {
                    pickerVisible = true
                    updatePicker()
                    _uiState.update { it.copy(autoPlayFallback = decision.progress) }
                }
            },
        )
        ensureActive()
        val resolved = selected?.let { resolveDirectDebridStreamIfNeeded(it, nextVideo.season, nextVideo.episode) }
        ensureActive()
        if (resolved != null) {
            _uiState.update { it.copy(autoPlayFallback = null) }
            switchToEpisodeStream(resolved, forcedTargetVideo = nextVideo, isAutoPlay = !userInitiated)
        } else {
            pickerVisible = true
            updatePicker()
            _uiState.update { it.copy(autoPlayFallback = null) }
        }
    } finally {
        search.cancel()
        _uiState.update { it.copy(autoPlayFallback = null) }
    }
}
