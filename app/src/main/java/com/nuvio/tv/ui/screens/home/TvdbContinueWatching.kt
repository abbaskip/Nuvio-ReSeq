package com.nuvio.tv.ui.screens.home

import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.tvdb.resolveTvdbProgressVideo
import com.nuvio.tv.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun HomeViewModel.observeTvdbContinueWatchingChanges() = viewModelScope.launch {
    cwEnrichmentCache.contentInvalidated.filterNotNull().collect { change ->
        if (change.profileId == profileManager.activeProfileId.value) invalidateTvdbContinueWatching(change.contentIds)
    }
}

internal fun HomeViewModel.invalidateTvdbContinueWatching(ids: Set<String>) {
    cwMetadataLoads.invalidate(ids)
    for (cache in listOf<MutableMap<String, *>>(cwMetaCache, cwMetaNegativeCacheTimestamps, cwBadgeEpisodeCache, cwBadgeNextSeasonMs)) {
        synchronized(cache) { cache.keys.removeAll { it.substringAfter(':') in ids } }
    }
    synchronized(cwNextUpResolutionCache) { cwNextUpResolutionCache.keys.removeAll { it.substringBefore('|') in ids } }
    cwNextUpNegativeCacheTimestamps.keys.removeAll { it.substringBefore('|') in ids }
    ids.forEach {
        cwEnrichedNextUpOverlay.remove(it)
        cwEnrichedInProgressOverlay.remove(it)
        cwLastProcessedNextUpContentIds.remove(it)
        cwProcessedOlderSeedContentIds.remove(it)
    }
    synchronized(discoveredOlderNextUpItems) { discoveredOlderNextUpItems.removeAll { it.info.contentId in ids } }
    // Keep the current row until the refreshed pipeline can publish a replacement.
    shuffleHomeRefresh.value = shuffleHomeRefresh.value.copy(metadata = shuffleHomeRefresh.value.metadata + 1)
    cwPipelineRefreshTrigger.value++
}

internal fun CwVideoSummary.asTvdbVideo() = Video(id, title.orEmpty(), released, thumbnail,
    season = season, episode = episode, overview = overview, available = available, episodeIdentity = episodeIdentity)

/** Search coordinates follow the selected order; progress follows verified content. */
internal fun projectTvdbContinueWatching(item: ContinueWatchingItem, videos: List<Video>): ContinueWatchingItem? {
    val video = when (item) {
        is ContinueWatchingItem.InProgress -> resolveTvdbProgressVideo(videos, item.progress)
        is ContinueWatchingItem.NextUp -> {
            val info = item.info
            if (info.tvdbEpisodeId != null) videos.filter { it.episodeIdentity?.trackingEpisodeId == info.tvdbEpisodeId }.singleEpisodeSource()
            else videos.filter { it.id == info.videoId && it.season == info.season && it.episode == info.episode &&
                !info.episodeTitle.isNullOrBlank() && (it.title == info.episodeTitle || it.episodeIdentity?.providerTitle == info.episodeTitle) }.singleEpisodeSource()
        }
    }?.takeIf { it.hasResolvedSource && it.season != null && it.episode != null } ?: return null
    val identity = video.episodeIdentity ?: return null
    return when (item) {
        is ContinueWatchingItem.InProgress -> item.copy(progress = item.progress.copy(videoId = video.id,
            season = video.season, episode = video.episode, tvdbEpisodeId = identity.trackingEpisodeId,
            episodeTitle = video.title, tvdbEpisodeIds = identity.trackingEpisodeIds, tvdbContext = video.tvdbPlaybackContext()), displaySeason = video.displaySeason, displayEpisode = video.displayEpisode,
            episodeDescription = video.overview, episodeThumbnail = video.thumbnail, chooseEpisode = false)
        is ContinueWatchingItem.NextUp -> item.copy(info = item.info.copy(videoId = video.id,
            season = video.season!!, episode = video.episode!!, tvdbEpisodeId = identity.trackingEpisodeId,
            episodeTitle = video.title, tvdbContext = video.tvdbPlaybackContext(), displaySeason = video.displaySeason, displayEpisode = video.displayEpisode,
            episodeDescription = video.overview, thumbnail = video.thumbnail))
    }
}

internal fun ContinueWatchingItem.hasTvdbPlaybackIdentity(): Boolean = when (this) {
    is ContinueWatchingItem.InProgress -> progress.tvdbContext != null
    is ContinueWatchingItem.NextUp -> info.tvdbEpisodeId != null
}

/** Also covers cards restored from snapshots before their metadata has been refreshed. */
internal fun HomeViewModel.verifyTvdbPlayback(item: ContinueWatchingItem, play: (ContinueWatchingItem) -> Unit,
    openSeries: (String, String, String) -> Unit, manualSelection: Boolean = false) {
    viewModelScope.launch {
        val profileId = profileManager.activeProfileId.value
        val id = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentId; is ContinueWatchingItem.NextUp -> item.info.contentId }
        val type = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentType; is ContinueWatchingItem.NextUp -> item.info.contentType }
        if (item.chooseEpisode && !manualSelection && !item.hasTvdbPlaybackIdentity()) {
            openSeries(id, type, ""); return@launch
        }
        val target = if (item.chooseEpisode) {
            // The options menu can outlive the placeholder card from which it opened.
            // Resolve the current episode before invoking the manual source picker.
            withTimeoutOrNull(22_000L) {
                fun current() = (uiState.value.continueWatchingItems + uiState.value.upcomingItems)
                    .firstOrNull { it.contentId() == id && !it.chooseEpisode }
                val shuffleProfile = episodeShuffleStore.profiles.first { it.profileId == profileId }
                val sequentialIdentity = item.hasTvdbPlaybackIdentity() && shuffleProfile.profileId == profileId &&
                    shuffleProfile.available && !shuffleProfile.settings(id, type).enabled
                current() ?: if (sequentialIdentity) {
                    // A negative Home metadata cache must not block an already identified
                    // sequential episode. The fresh playback projection below verifies it.
                    when (item) {
                        is ContinueWatchingItem.InProgress -> item.copy(chooseEpisode = false)
                        is ContinueWatchingItem.NextUp -> item.copy(chooseEpisode = false)
                    }
                } else run {
                    val seed = when (item) {
                        is ContinueWatchingItem.InProgress -> item.progress
                        is ContinueWatchingItem.NextUp -> item.info.toProgressSeed()
                    }
                    resolveCwMeta(seed) ?: return@withTimeoutOrNull null
                    val state = uiState.first { state ->
                        (state.continueWatchingItems + state.upcomingItems).any { it.contentId() == id && !it.chooseEpisode }
                    }
                    (state.continueWatchingItems + state.upcomingItems).first { it.contentId() == id && !it.chooseEpisode }
                }
            }
        } else item
        if (profileManager.activeProfileId.value != profileId) return@launch
        if (target == null) {
            Toast.makeText(appContext, "This episode could not be safely matched. Try again once Continue Watching has refreshed.", Toast.LENGTH_LONG).show()
            return@launch
        }
        if (type !in listOf("series", "tv") || tvdbOrderStore?.selected(profileId, listOf(id)) == null) {
            if (target.hasTvdbPlaybackIdentity()) {
                Toast.makeText(appContext, "Episode order changed. Open the series to choose an episode.", Toast.LENGTH_LONG).show()
            } else if (profileManager.activeProfileId.value == profileId) play(target)
            return@launch
        }
        val projected = try {
            val meta = withTimeoutOrNull(22_000L) {
                (metaRepository.getMetaFromAllAddons(type, id).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
            }
            meta?.takeIf { it.episodeOrder?.effectiveType != null }?.let { projectTvdbContinueWatching(target, it.videos) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        if (profileManager.activeProfileId.value != profileId) return@launch
        if (projected != null) play(projected)
        else Toast.makeText(appContext, "This episode could not be safely matched. Open the series to choose an episode.", Toast.LENGTH_LONG).show()
    }
}
