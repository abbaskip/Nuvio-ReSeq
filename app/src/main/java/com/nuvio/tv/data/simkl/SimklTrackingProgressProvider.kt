package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.movieCompletionIndex
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class SimklTrackingProgressProvider @Inject constructor(
    private val syncRepository: SimklSyncRepository,
    private val apiClient: SimklApiClient,
    private val authStorage: SimklAuthStorage,
    private val layoutPreferences: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.SIMKL
    override val isAuthenticated = authStorage.state.map { state -> state.isAuthenticated }
        .distinctUntilChanged()
    override val allProgress = syncRepository.projection.map { projection ->
        projection.progress
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val remoteProgressLoaded = syncRepository.state.map { state ->
        state.hasLoaded && state.errorMessage == null
    }.distinctUntilChanged()
    override val nextUpSeeds = combine(
        syncRepository.projection,
        layoutPreferences.nextUpFromFurthestEpisode
    ) { projection, preferFurthestEpisode ->
        projection.nextUp(preferFurthestEpisode)
    }.distinctUntilChanged()
    override fun movieCompletionTimes(profileId: Int) = syncRepository.state
        .map { state -> state.snapshot.entries.takeIf { state.profileId == profileId }.orEmpty() }
        .distinctUntilChanged()
        .map { entries -> simklMovieCompletionTimes(entries) }

    override val watchedMovieIds = syncRepository.projection.map { projection ->
        projection.watchedMovieIds
    }.distinctUntilChanged()
    override val watchedItems = syncRepository.projection.map { projection ->
        projection.watched.items
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        syncRepository.projection.map { projection ->
            projection.episodeProgress(contentId)
        }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
            .distinctUntilChanged()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean> = syncRepository.projection.map { projection ->
        projection.isWatched(contentId, videoId, season, episode)
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC)
        return syncRepository.projection.value.watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        syncRepository.ensureLoaded()
        return syncRepository.projection.value.showIdSiblings
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    override suspend fun watchBaseline(target: TrackingMediaReference): com.nuvio.tv.core.tracking.TrackingWatchBaseline {
        syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
        check(syncRepository.state.value.hasLoaded && syncRepository.state.value.errorMessage == null)
        return super.watchBaseline(target)
    }

    override suspend fun removeWatchEvent(target: TrackingMediaReference, event: com.nuvio.tv.core.tracking.TrackingWatchEvent): Boolean {
        val scope = authStorage.currentScope()
        fun checkScope() = check(authStorage.isCurrent(scope) && (event.profileId == null || scope.profileId == event.profileId))
        checkScope()
        val episode = requireNotNull(target.episode)
        val parent = requireNotNull(target.catalog)
        syncRepository.ensureLoaded()
        val entries = syncRepository.state.value.snapshot.entries.filter { it.matchesSimklContentId(parent.contentId) }
        val sessions = SimklApiSyncRemote(apiClient).fetchPlayback().filter { row ->
            val media = row.media ?: return@filter false
            val ep = row.episode ?: return@filter false
            (media.canonicalContentId() == parent.contentId || entries.any { it.media == media }) &&
                (ep.tvdbSeason ?: ep.season) == episode.season && (ep.tvdbNumber ?: ep.number) == episode.number &&
                row.pausedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }?.let(event::contains) == true
        }
        check(sessions.size <= 1) { "Ambiguous Simkl playback event" }
        for (row in sessions) {
            checkScope()
            val id = requireNotNull(row.id)
            apiClient.execute(SimklApiRequest(SimklHttpMethod.DELETE, "/sync/playback/$id"))
            checkScope()
            syncRepository.removePlaybackSessions(setOf(id))
        }
        // The unchanged 9.8 output does not opt into Simkl rewatch sessions.
        // Preserve an older canonical completion instead of unwatching the episode.
        checkScope()
        syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
        val current = watchedItems.first().filter { it.contentId == parent.contentId && it.season == episode.season && it.episode == episode.number }
            .maxByOrNull { it.watchedAt }
        if (event.completionExpected && current != null && event.contains(current.watchedAt)) {
            checkScope()
            check(event.priorKnown && event.priorWatchedAt == null) { "Cannot erase earlier Simkl history" }
            val body = buildSimklHistoryRemovalBody(listOf(target), kotlinx.serialization.json.Json { explicitNulls = false })
            val response = apiClient.execute(SimklApiRequest(SimklHttpMethod.POST, "/sync/history/remove", body = body,
                retryPolicy = SimklRetryPolicy.SYNC_WRITE))
            check(response.status in 200..299)
            syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
            return true
        }
        return if (event.completionExpected) event.priorKnown && current?.watchedAt == event.priorWatchedAt && current != null
            else sessions.isNotEmpty()
    }

    override suspend fun removeEpisodePause(target: TrackingMediaReference, resume: WatchProgress) {
        val episode = requireNotNull(target.episode)
        val parent = requireNotNull(target.catalog)
        syncRepository.ensureLoaded()
        val matchingMedia = syncRepository.state.value.snapshot.entries.filter { it.matchesSimklContentId(parent.contentId) }
        val latest = SimklApiSyncRemote(apiClient).fetchPlayback().filter { row ->
            val media = row.media ?: return@filter false
            val ep = row.episode ?: return@filter false
            (media.canonicalContentId() == parent.contentId || matchingMedia.any { it.media == media }) &&
                (if (resume.simklPlaybackId != null) row.id == resume.simklPlaybackId else
                    (ep.tvdbSeason ?: ep.season) == episode.season && (ep.tvdbNumber ?: ep.number) == episode.number)
        }.maxByOrNull { it.pausedAt.orEmpty() } ?: return
        val id = requireNotNull(latest.id)
        apiClient.execute(SimklApiRequest(SimklHttpMethod.DELETE, "/sync/playback/$id"))
        syncRepository.removePlaybackSessions(setOf(id))
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        val entryIndex = syncRepository.currentEntryIndex()
        val matchingEntries by lazy(LazyThreadSafetyMode.NONE) {
            entryIndex?.matchingEntriesForContent(snapshot.entries, contentId, trackingProviderItemId = null)
                ?: snapshot.entries.filter { entry -> entry.matchesSimklContentId(contentId) }
        }
        val sessions = snapshot.playback.filter { session ->
            val media = session.media ?: return@filter false
            val sameContent = media.canonicalContentId().equals(contentId, ignoreCase = true) ||
                matchingEntries.any { entry -> entry.media == media }
            val sessionSeason = session.episode?.tvdbSeason ?: session.episode?.season
            val sessionEpisode = session.episode?.tvdbNumber ?: session.episode?.number
            sameContent && (season == null || episode == null ||
                sessionSeason == season && sessionEpisode == episode)
        }
        val removed = linkedSetOf<Long>()
        sessions.mapNotNull(SimklPlaybackSession::id).forEach { sessionId ->
            try {
                apiClient.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.DELETE,
                        path = "/sync/playback/$sessionId"
                    )
                )
                removed += sessionId
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
        }
        syncRepository.removePlaybackSessions(removed)
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit

    override fun clearOptimistic() = Unit

    override fun isHiddenFromProgress(contentId: String): Boolean =
        syncRepository.projection.value.isHidden(contentId)

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean =
        syncRepository.projection.value.isWatchedByVideoId(videoId, episode)

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
}

internal fun simklMovieCompletionTimes(entries: List<SimklLibraryEntry>) =
    movieCompletionIndex(entries.asSequence().filter { it.isMovieEntry() },
        { it.media?.simklTrackingContentIds().orEmpty() }, { parseSimklUtcEpochMs(it.lastWatchedAt) })

internal fun checkWatchedByVideoId(
    snapshot: SimklSyncSnapshot,
    videoId: String,
    episode: Int
): Boolean = SimklSnapshotProjection.create(snapshot).isWatchedByVideoId(videoId, episode)
