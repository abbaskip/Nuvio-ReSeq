package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first

/** Keep the latest actual completion across aliases and duplicate history rows.
 * An unknown date still supports a watched badge, but gives CW no removal evidence. */
internal fun <T> movieCompletionIndex(rows: Sequence<T>, ids: (T) -> Iterable<String>,
    watchedAt: (T) -> Long?): Map<String, Long?> = buildMap {
    for (row in rows) {
        val at = watchedAt(row)?.takeIf { it > 0 }
        for (id in ids(row)) {
            val previous = get(id)
            if (previous == null || (at != null && at > previous)) put(id, at)
        }
    }
}

interface TrackingProgressProvider {
    val providerId: TrackingProviderId
    val isAuthenticated: Flow<Boolean>
    val allProgress: Flow<List<WatchProgress>>
    val remoteProgressLoaded: Flow<Boolean>
    val nextUpSeeds: Flow<List<WatchProgress>>
    /** Completed episodes in the same numbering as nextUpSeeds, before selecting one per series. */
    suspend fun completedNextUpHistory(contentId: String): List<WatchProgress> =
        episodeProgress(contentId).first().values.filter { it.isCompleted() }
    val watchedMovieIds: Flow<Set<String>>
    /** Actual watch times, in epoch milliseconds, indexed by movie identity for this profile. */
    fun movieCompletionTimes(profileId: Int): Flow<Map<String, Long?>>
    fun confirmsRemovedWatch(profileId: Int, event: WatchProgress): Boolean = false
    val ownsCompletedHistoryProjection: Boolean
        get() = false
    val clearsLocalProgressOnSelection: Boolean
        get() = false
    val watchedItems: Flow<List<WatchedItem>>
        get() = flowOf(emptyList())

    fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>
    /** Native records for a selected TVDB order, before any addon renumbering or slot merging. */
    fun nativeEpisodeRecords(contentId: String): Flow<List<WatchProgress>>? = null
    fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>>
    fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean>
    suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>>
    /** Series identities with episode history already on this device. Never initiates a tracker sync. */
    suspend fun cachedWatchedShowIds(profileId: Int): Set<String> = emptySet()
    suspend fun showIdSiblings(): Map<String, Set<String>>
    fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? = null
    suspend fun refresh(intent: TrackingRefreshIntent)
    /** Returns after the selected source's playback AND history refresh have completed. */
    suspend fun refreshForContinueWatching(intent: TrackingRefreshIntent) = refresh(intent)
    /** Confirmation excludes locally optimistic echoes of the same device write. */
    suspend fun confirmedContinueWatchingProgress(): List<WatchProgress> = allProgress.first() + nextUpSeeds.first()
    suspend fun watchBaseline(target: TrackingMediaReference): TrackingWatchBaseline {
        val episode = requireNotNull(target.episode)
        val prior = watchedItems.first().filter { it.contentId == target.catalog?.contentId &&
            it.season == episode.season && it.episode == episode.number }.maxByOrNull { it.watchedAt }
        return TrackingWatchBaseline(target, true, prior?.watchedAt)
    }
    /** True only after this dated play was removed. Never fall back to unwatch-all. */
    suspend fun removeWatchEvent(target: TrackingMediaReference, event: TrackingWatchEvent): Boolean =
        throw UnsupportedOperationException("Individual watch removal unavailable")
    suspend fun removeEpisodePause(target: TrackingMediaReference, resume: WatchProgress) {
        val episode = requireNotNull(target.episode)
        removeProgress(requireNotNull(target.catalog).contentId, requireNotNull(episode.season), episode.number)
    }

    suspend fun removeProgress(contentId: String, season: Int?, episode: Int?)
    fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean)
    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?)
    fun clearOptimistic()
    fun retainsLocalProgress(contentId: String): Boolean = false
    fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = false
    fun isHiddenFromProgress(contentId: String): Boolean
    fun shouldUseAsNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean =
        progress.isCompleted()
    fun normalizeParentContentId(parentContentId: String, videoId: String?): String = parentContentId
    suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress
}

@Singleton
class TrackingProgressProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingProgressProvider>
) {
    private val providersById = providers.associateBy(TrackingProgressProvider::providerId)

    init {
        require(providersById.size == providers.size)
    }

    fun providers(): List<TrackingProgressProvider> =
        providersById.values.sortedBy { it.providerId.ordinal }

    fun provider(id: TrackingProviderId): TrackingProgressProvider? = providersById[id]
}

@Singleton
class TrackingHistoryWriterRegistry @Inject constructor(
    writers: Set<@JvmSuppressWildcards TrackingHistoryWriter>
) {
    private val writersById = writers.associateBy(TrackingHistoryWriter::providerId)

    init {
        require(writersById.size == writers.size)
    }

    fun writers(): List<TrackingHistoryWriter> =
        writersById.values.sortedBy { it.providerId.ordinal }

    fun writer(id: TrackingProviderId): TrackingHistoryWriter? = writersById[id]
}
