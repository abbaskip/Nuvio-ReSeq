package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.PlaybackShuffleState
import com.nuvio.tv.data.tvdb.reconcileTvdbVideoProgress
import com.nuvio.tv.domain.model.*

internal fun PlayerUiState.withTvdbPlayback(videos: List<Video>, season: Int?, episode: Int?, progress: PlaybackShuffleState?): PlayerUiState {
    val candidates = videos.filter { it.season == season && it.episode == episode }
    val identity = (candidates.singleOrNull { it.title == currentEpisodeTitle }
        ?: candidates.singleEpisodeSource())?.episodeIdentity
    if (videos.none { it.episodeIdentity != null }) return if (tvdbEpisodeIdentity == null) this else copy(tvdbEpisodeIdentity = null)
    val verified = reconcileTvdbVideoProgress(videos, progress?.progress ?: episodeWatchProgressMap)
    return copy(tvdbEpisodeIdentity = identity, episodeWatchProgressMap = verified,
        watchedEpisodeKeys = verified.filterValues { it.isCompleted() }.keys)
}

internal val PlayerUiState.displaySeason: Int? get() = tvdbEpisodeIdentity?.displayed?.season ?: currentSeason
internal val PlayerUiState.displayEpisode: Int? get() = tvdbEpisodeIdentity?.displayed?.episode ?: currentEpisode

internal fun PlayerRuntimeController.currentTvdbContext(): TvdbPlaybackContext? {
    if (navigationArgs.tvdbContextJson == "provider") return null
    val launch = TvdbPlaybackContext.decode(navigationArgs.tvdbContextJson)?.takeIf {
        it.requestVideoId == currentVideoId && it.season == currentSeason && it.episode == currentEpisode
    }
    if (launch != null) return launch
    val identity = _uiState.value.tvdbEpisodeIdentity?.takeIf {
        it.streamVideoId == currentVideoId && it.displayed.season == currentSeason && it.displayed.episode == currentEpisode
    } ?: return null
    return identity.tvdbPlaybackContext(currentVideoId ?: return null)
}
