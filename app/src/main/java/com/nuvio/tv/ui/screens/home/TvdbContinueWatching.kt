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
            episodeDescription = video.overview, episodeThumbnail = video.thumbnail)
        is ContinueWatchingItem.NextUp -> item.copy(info = item.info.copy(videoId = video.id,
            season = video.season!!, episode = video.episode!!, tvdbEpisodeId = identity.trackingEpisodeId,
            episodeTitle = video.title, displaySeason = video.displaySeason, displayEpisode = video.displayEpisode,
            episodeDescription = video.overview, thumbnail = video.thumbnail))
    }
}

internal fun ContinueWatchingItem.hasTvdbPlaybackIdentity(): Boolean = when (this) {
    is ContinueWatchingItem.InProgress -> progress.tvdbContext != null
    is ContinueWatchingItem.NextUp -> info.tvdbEpisodeId != null
}

/** Also covers cards restored from snapshots before their metadata has been refreshed. */
internal fun HomeViewModel.verifyTvdbPlayback(item: ContinueWatchingItem, play: (ContinueWatchingItem) -> Unit) {
    viewModelScope.launch {
        val profileId = profileManager.activeProfileId.value
        val id = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentId; is ContinueWatchingItem.NextUp -> item.info.contentId }
        val type = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentType; is ContinueWatchingItem.NextUp -> item.info.contentType }
        if (type !in listOf("series", "tv") || tvdbOrderStore?.selected(profileId, listOf(id)) == null) {
            if (item.hasTvdbPlaybackIdentity()) {
                Toast.makeText(appContext, "Episode order changed. Open the series to choose an episode.", Toast.LENGTH_LONG).show()
            } else if (profileManager.activeProfileId.value == profileId) play(item)
            return@launch
        }
        val projected = try {
            val meta = withTimeoutOrNull(22_000L) {
                (metaRepository.getMetaFromAllAddons(type, id).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
            }
            meta?.takeIf { it.episodeOrder?.effectiveType != null }?.let { projectTvdbContinueWatching(item, it.videos) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        if (profileManager.activeProfileId.value != profileId) return@launch
        if (projected != null) play(projected)
        else Toast.makeText(appContext, "This episode could not be safely matched. Open the series to choose an episode.", Toast.LENGTH_LONG).show()
    }
}
