package com.nuvio.tv.data.local

import javax.inject.Inject
import javax.inject.Singleton

data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null,
    val ratingVisible: Boolean = true,
    val tvdbEpisodeId: Long? = null,
    val tvdbContext: com.nuvio.tv.domain.model.TvdbPlaybackContext? = null,
    val latestSeriesActivity: Long? = null,
    val chooseEpisode: Boolean = false
)

data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null,
    val ratingVisible: Boolean = true,
    val tvdbContext: com.nuvio.tv.domain.model.TvdbPlaybackContext? = null,
    val chooseEpisode: Boolean = false
)

data class CwContentInvalidation(val profileId: Int, val contentIds: Set<String>, val version: Long)

/** Compatibility boundary for metadata invalidation; membership lives in the repository. */
@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    val repository: com.nuvio.tv.data.cw.ContinueWatchingRepository
) {
    private val _cacheCleared = kotlinx.coroutines.flow.MutableStateFlow(0)
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = _cacheCleared
    private val _contentInvalidated = kotlinx.coroutines.flow.MutableStateFlow<CwContentInvalidation?>(null)
    val contentInvalidated: kotlinx.coroutines.flow.StateFlow<CwContentInvalidation?> = _contentInvalidated
    val projection get() = repository.projection
    suspend fun getSnapshot() = repository.snapshot()
    suspend fun getNextUpSnapshot() = repository.snapshot().nextUp
    suspend fun getInProgressSnapshot() = repository.snapshot().inProgress
    suspend fun invalidateContent(profileId: Int, contentIds: Set<String>) {
        if (contentIds.isEmpty()) return
        repository.invalidate(profileId, contentIds)
        _contentInvalidated.value = CwContentInvalidation(profileId, contentIds, (_contentInvalidated.value?.version ?: 0L) + 1)
    }
    /** Clear derived metadata, never the user's membership/progress. */
    suspend fun clearAll() {
        val state = repository.snapshot()
        invalidateContent(state.profileId, state.members.keys)
        _cacheCleared.value++
    }
}
