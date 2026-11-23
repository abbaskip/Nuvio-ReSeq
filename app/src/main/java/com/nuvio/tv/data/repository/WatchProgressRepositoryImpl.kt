package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.cw.isRemovedCwPause
import com.nuvio.tv.data.cw.isRevokedHistory
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerWatchMark
import com.nuvio.tv.data.mediaserver.ServerWatched
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.tvdbPlaybackContext
import com.nuvio.tv.domain.model.storageKey
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.mergeWatchedEpisodeProjection
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton

private const val REMOTE_PROGRESS_WRITE_DEDUP_WINDOW_MS = 5_000L

private data class RemoteProgressWriteKey(
    val profileId: Int,
    val progressKey: String
)

private data class RemoteProgressWrite(
    val progress: WatchProgress,
    val sentAtMs: Long
)

internal class RemoteProgressWriteDeduplicator(
    private val windowMs: Long = REMOTE_PROGRESS_WRITE_DEDUP_WINDOW_MS
) {
    private val lock = Any()
    private val recentWrites = mutableMapOf<RemoteProgressWriteKey, RemoteProgressWrite>()

    fun shouldSend(
        profileId: Int,
        progressKey: String,
        progress: WatchProgress,
        nowMs: Long
    ): Boolean = synchronized(lock) {
        recentWrites.entries.removeAll { (_, write) ->
            val elapsedMs = nowMs - write.sentAtMs
            elapsedMs < 0L || elapsedMs >= windowMs
        }
        val key = RemoteProgressWriteKey(profileId, progressKey)
        val normalizedProgress = progress.copy(lastWatched = 0L)
        if (recentWrites[key]?.progress == normalizedProgress) {
            return@synchronized false
        }
        recentWrites[key] = RemoteProgressWrite(normalizedProgress, nowMs)
        true
    }
}

internal fun resolveProviderEpisodeProgress(
    contentId: String,
    season: Int,
    episode: Int,
    episodeProgress: Map<Pair<Int, Int>, WatchProgress>,
    allProgress: List<WatchProgress>
): WatchProgress? {
    val liveProgress = allProgress
        .asSequence()
        .filter { progress ->
            progress.contentId.equals(contentId, ignoreCase = true) &&
                progress.season == season &&
                progress.episode == episode
        }
        .maxByOrNull(WatchProgress::lastWatched)
    return listOfNotNull(episodeProgress[season to episode], liveProgress)
        .maxByOrNull(WatchProgress::lastWatched)
}

internal suspend fun survivingNextUpSeeds(
    seeds: List<WatchProgress>,
    deviceCompletions: List<WatchProgress>,
    furthest: Boolean,
    applyWatchEvents: (List<WatchProgress>) -> List<WatchProgress>,
    completedHistory: suspend (String) -> List<WatchProgress>
): List<WatchProgress> {
    val surviving = applyWatchEvents(seeds)
    val retainedIds = surviving.mapTo(mutableSetOf()) { it.contentId }
    // A delayed tracker snapshot can keep returning the removed watch as its single
    // seed. Only those series need a second look at the complete source history.
    val missing = seeds.mapTo(mutableSetOf()) { it.contentId } - retainedIds
    val recovered = missing.flatMap { id -> completedHistory(id).filter { it.contentId == id } }
    val other = applyWatchEvents(recovered + deviceCompletions)
        .filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 && it.isCompleted() }
    val order = compareBy<WatchProgress>({ it.tvdbContext?.season ?: it.season }, { it.tvdbContext?.episode ?: it.episode })
    val byTime = compareBy(WatchProgress::lastWatched)
    val preference = if (furthest) order.then(byTime) else byTime.then(order)
    return (surviving + other).groupBy { it.contentId }.values.map { rows ->
        val selected = rows.maxWith(preference)
        val activity = rows.maxOf { maxOf(it.lastWatched, it.latestSeriesActivity ?: 0L) }
        if (activity > selected.lastWatched) selected.copy(latestSeriesActivity = activity) else selected
    }
}

// Keep exact local resume, but let the selected tracker own completed history.
internal fun TrackingProgressProvider?.retainsTvdbLocalProgress(progress: WatchProgress): Boolean =
    this == null || !progress.isCompleted() || retainsLocalProgress(progress.contentId)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressRepositoryImpl @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val layoutPreferenceDataStore: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val authManager: AuthManager,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    private val trackingProgressProviders: TrackingProgressProviderRegistry,
    private val trackingHistoryWriters: TrackingHistoryWriterRegistry,
    private val mutationStore: WatchStateMutationStore,
    private val serverWatched: ServerWatched,
    private val tvdbTracking: com.nuvio.tv.data.tvdb.TvdbTrackingResolver? = null,
    private val cwRepository: com.nuvio.tv.data.cw.ContinueWatchingRepository? = null,
    private val traktEvents: javax.inject.Provider<TraktProgressService>? = null,
    private val deviceEvents: javax.inject.Provider<com.nuvio.tv.data.local.DeviceWatchEventStore>? = null,
) : WatchProgressRepository {
    companion object {
        private const val TAG = "WatchProgressRepo"
        private const val METADATA_MAX_ATTEMPTS = 3
    }

    private data class EpisodeMetadata(
        val title: String?,
        val thumbnail: String?,
        val runtimeMs: Long = 0L
    )

    private data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>,
        val runtimeMs: Long = 0L
    )

    private data class ProfileContentKey(
        val profileId: Int,
        val contentId: String
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hydratedProgressKeys = mutableSetOf<ProfileContentKey>()
    private data class MetadataAttempt(val count: Int, val lastAttemptEpochMs: Long)
    private val metadataAttempts = mutableMapOf<ProfileContentKey, MetadataAttempt>()
    internal var metadataRetryWindowMs = 2_000L
    internal var metadataClock: () -> Long = System::currentTimeMillis
    private var lastHydratedProfileId: Int? = null
    private val syncJobs = mutableMapOf<Int, Job>()
    private val syncJobsLock = Any()
    private val remoteProgressWriteDeduplicator = RemoteProgressWriteDeduplicator()
    var isSyncingFromRemote = false
    var hasCompletedInitialPull = false
    var hasCompletedInitialWatchedItemsPull = false

    private val metadataState = MutableStateFlow<Map<String, ContentMetadata>>(emptyMap())
    private val optimisticContinueWatchingUpdates = MutableSharedFlow<WatchProgress>(
        replay = 1,
        extraBufferCapacity = 16
    )
    // Optimistic watched-movie toggles, keyed by the profile that made them.
    private val optimisticWatchedMovieAdditions = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())
    private val optimisticWatchedMovieRemovals = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())
    private val metadataMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<ProfileContentKey>()
    private val metadataHydrationLimit = 30

    private fun triggerRemoteSync(profileId: Int) {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialPull) return
        if (!authManager.isAuthenticated) return
        synchronized(syncJobsLock) {
            syncJobs.remove(profileId)?.cancel()
            syncJobs[profileId] = syncScope.launch {
                delay(2000)
                withContext(NonCancellable) {
                    watchProgressSyncService.pushToRemote(profileId)
                }
            }
        }
    }

    private fun triggerWatchedItemsSync(
        items: Collection<WatchedItem>,
        profileId: Int
    ) {
        if (items.isEmpty()) return
        triggerWatchedItemsSync(profileId)
    }

    private fun triggerWatchedItemsSync(profileId: Int) {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialWatchedItemsPull) return
        if (!authManager.isAuthenticated) return
        syncScope.launch {
            withContext(NonCancellable) {
                watchedItemsSyncService.pushToRemote(profileId)
            }
        }
    }

    private fun canAttemptMetadata(key: ProfileContentKey, now: Long = metadataClock()): Boolean =
        synchronized(metadataAttempts) {
            val attempt = metadataAttempts[key] ?: return true
            if (attempt.count >= METADATA_MAX_ATTEMPTS) return false
            now - attempt.lastAttemptEpochMs >= (metadataRetryWindowMs * attempt.count)
        }

    private fun recordMetadataAttempt(key: ProfileContentKey, now: Long = metadataClock()) {
        synchronized(metadataAttempts) {
            val current = metadataAttempts[key]
            val nextCount = (current?.count ?: 0) + 1
            metadataAttempts[key] = MetadataAttempt(count = nextCount, lastAttemptEpochMs = now)
        }
    }

    private fun hydrateMetadata(progressList: List<WatchProgress>, profileId: Int) {
        val sorted = progressList.sortedByDescending { it.lastWatched }
        val uniqueByContent = linkedMapOf<String, WatchProgress>()
        sorted.forEach { progress ->
            if (uniqueByContent.size < metadataHydrationLimit) {
                uniqueByContent.putIfAbsent(progress.contentId, progress)
            }
        }

        uniqueByContent.values.forEach { progress ->
            val contentId = progress.contentId
            if (contentId.isBlank()) return@forEach
            val key = ProfileContentKey(profileId, contentId)
            if (metadataState.value.containsKey(contentId)) return@forEach
            if (!canAttemptMetadata(key)) return@forEach

            syncScope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (profileManager.activeProfileId.value != profileId) return@withLock false
                    if (metadataState.value.containsKey(contentId)) return@withLock false
                    if (inFlightMetadataKeys.contains(key)) return@withLock false
                    if (synchronized(hydratedProgressKeys) { key in hydratedProgressKeys }) return@withLock false
                    if (!canAttemptMetadata(key)) return@withLock false
                    inFlightMetadataKeys.add(key)
                    recordMetadataAttempt(key)
                    true
                }
                if (!shouldFetch) return@launch

                try {
                    val metadata = fetchContentMetadata(
                        contentId = contentId,
                        contentType = progress.contentType
                    ) ?: return@launch
                    if (profileManager.activeProfileId.value != profileId) return@launch
                    synchronized(hydratedProgressKeys) {
                        hydratedProgressKeys.add(key)
                    }
                    synchronized(metadataAttempts) {
                        metadataAttempts.remove(key)
                    }
                    metadataState.update { current ->
                        current + (contentId to metadata)
                    }
                } finally {
                    metadataMutex.withLock {
                        inFlightMetadataKeys.remove(key)
                    }
                }
            }
        }
    }

    private suspend fun fetchContentMetadata(
        contentId: String,
        contentType: String
    ): ContentMetadata? {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            } else {
                add("movie")
            }
        }.distinct()

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromPrimaryAddon(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val episodes = meta.videos
                    .mapNotNull { video ->
                        val season = video.season ?: return@mapNotNull null
                        val episode = video.episode ?: return@mapNotNull null
                        (season to episode) to EpisodeMetadata(
                            title = video.title,
                            thumbnail = video.thumbnail,
                            runtimeMs = (video.runtime ?: 0).toLong() * 60_000L
                        )
                    }
                    .toMap()

                return ContentMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    backdrop = meta.backdropUrl,
                    logo = meta.logo,
                    episodes = episodes,
                    runtimeMs = parseRuntimeToMs(meta.runtime)
                )
            }
        }
        return null
    }

    private fun enrichWithMetadata(
        progress: WatchProgress,
        metadataMap: Map<String, ContentMetadata>
    ): WatchProgress {
        val metadata = metadataMap[progress.contentId] ?: return progress
        val episodeMeta = if (progress.season != null && progress.episode != null) {
            metadata.episodes[progress.season to progress.episode]
        } else {
            null
        }
        val shouldOverrideName = progress.name.isBlank() || progress.name == progress.contentId
        val backdrop = progress.backdrop
            ?: metadata.backdrop
            ?: episodeMeta?.thumbnail

        val episodeRuntimeMs = episodeMeta?.runtimeMs ?: 0L
        val runtimeMs = episodeRuntimeMs.takeIf { it > 0 } ?: metadata.runtimeMs

        return progress.copy(
            name = if (shouldOverrideName) metadata.name ?: progress.name else progress.name,
            poster = progress.poster ?: metadata.poster,
            backdrop = backdrop,
            logo = progress.logo ?: metadata.logo,
            duration = if (progress.duration > 0) progress.duration
                       else if (runtimeMs > 0) runtimeMs
                       else progress.duration,
            episodeTitle = progress.episodeTitle ?: episodeMeta?.title
        )
    }

    @Volatile private var activeProgressProviderId: TrackingProviderId? = null

    // A temporarily disconnected/loading selection must never fall back to another
    // history. Authentication belongs to the selected provider's readiness barrier.
    private val activeProgressProviderState = profileManager.resolvedActiveProfileId
        .flatMapLatest(traktSettingsDataStore::resolvedWatchProgressSource)
        .map { it.providerId?.let(trackingProgressProviders::provider) }.distinctUntilChanged()
        .onEach { activeProgressProviderId = it?.providerId }
        // Subscribe only after the persisted source is known; no provisional local source.
        .shareIn(syncScope, SharingStarted.Eagerly, replay = 1)

    private fun activeProgressProviderFlow(): Flow<TrackingProgressProvider?> = activeProgressProviderState

    private fun profileProgressProviderFlow(profileId: Int): Flow<TrackingProgressProvider?> =
        profileManager.activeProfileId.flatMapLatest { activeProfileId ->
            if (activeProfileId == profileId) activeProgressProviderFlow() else flowOf(null)
        }

    private suspend fun activeProgressProvider(): TrackingProgressProvider? =
        activeProgressProviderFlow().first()

    private fun withWatchEvents(rows: Flow<List<WatchProgress>>, profile: Int): Flow<List<WatchProgress>> {
        val events = deviceEvents?.get() ?: return rows
        return combine(rows, events.observe(profile)) { values, journal -> events.decorate(values, journal) }.distinctUntilChanged()
    }

    private fun withEpisodeEvents(rows: Flow<Map<Pair<Int, Int>, WatchProgress>>, profile: Int): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        val events = deviceEvents?.get() ?: return rows
        return combine(rows, events.observe(profile)) { values, journal ->
            events.decorate(values.values.toList(), journal).associateBy { requireNotNull(it.season) to requireNotNull(it.episode) }
        }.distinctUntilChanged()
    }

    private suspend fun connectedProgressProviders(): List<TrackingProgressProvider> =
        trackingProgressProviders.providers().filter { provider -> provider.isAuthenticated.first() }

    override fun observeRemoteProgressLoaded(): Flow<Boolean> {
        return activeProgressProviderFlow().flatMapLatest { provider ->
            if (provider == null) flowOf(true) else combine(provider.isAuthenticated, provider.remoteProgressLoaded) { connected, loaded -> connected && loaded }
        }.distinctUntilChanged()
    }

    override suspend fun confirmSelectedDeviceProgress() {
        val provider = activeProgressProvider() ?: return
        val profile = profileManager.activeProfileId.value
        watchProgressPreferences.confirmDeviceProgress(profile, provider.providerId.name,
            provider.confirmedContinueWatchingProgress().filter { it.source != WatchProgress.SOURCE_LOCAL })
    }

    override val allProgress: Flow<List<WatchProgress>>
        get() = profileManager.activeProfileId.flatMapLatest { profileId ->
            if (lastHydratedProfileId != profileId) {
                lastHydratedProfileId = profileId
                metadataState.value = emptyMap()
                synchronized(hydratedProgressKeys) {
                    hydratedProgressKeys.removeAll { it.profileId == profileId }
                }
                synchronized(metadataAttempts) {
                    metadataAttempts.keys.removeAll { it.profileId == profileId }
                }
            }
            withWatchEvents(activeProgressProviderFlow().flatMapLatest { provider ->
                if (provider != null) {
                    combine(
                        provider.allProgress,
                        watchProgressPreferences.observeDeviceProgress(profileId, provider.providerId.name),
                        metadataState,
                        tvdbTracking?.observeSelectedSeries(profileId) ?: flowOf(emptySet<Long>())
                    ) { items, localItems, metadata, selectedSeries ->
                        (items + localItems.filter { it.tvdbContext == null || it.tvdbContext.seriesId in selectedSeries })
                            .map { progress -> enrichWithMetadata(progress, metadata) }
                            .sortedByDescending(WatchProgress::lastWatched)
                    }.onEach { items ->
                        val needsMetadata = items.filter { progress ->
                            val key = ProfileContentKey(profileId, progress.contentId)
                            (progress.poster == null || progress.backdrop == null ||
                                progress.episodeTitle == null) &&
                                synchronized(hydratedProgressKeys) { key !in hydratedProgressKeys } &&
                                canAttemptMetadata(key)
                        }
                        if (needsMetadata.isNotEmpty()) hydrateMetadata(needsMetadata, profileId)
                    }
                } else {
                    combine(watchProgressPreferences.observeAllProgress(profileId),
                        tvdbTracking?.observeSelectedSeries(profileId) ?: flowOf(emptySet<Long>())) { items, selectedSeries ->
                        items.filter { it.tvdbContext == null || it.tvdbContext.seriesId in selectedSeries }
                    }
                        .onEach { items ->
                            val needsArtwork = items.filter { progress ->
                                val key = ProfileContentKey(profileId, progress.contentId)
                                progress.poster == null && progress.backdrop == null &&
                                    synchronized(hydratedProgressKeys) { key !in hydratedProgressKeys } &&
                                    canAttemptMetadata(key)
                            }
                            if (needsArtwork.isNotEmpty()) {
                                syncScope.launch { hydrateProgressArtwork(needsArtwork, profileId) }
                            }
                        }
                }
            }, profileId)
        }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { list -> list.filter { it.isInProgress() } }

    override val watchedItems: Flow<List<WatchedItem>>
        get() = activeProgressProviderFlow().flatMapLatest { provider ->
            provider?.watchedItems ?: watchedItemsPreferences.allItems
        }.distinctUntilChanged()

    override fun watchedEpisodeTimes(contentId: String): Flow<Map<Pair<Int, Int>, Long>> =
        activeProgressProviderFlow().flatMapLatest { provider ->
            val watched = if (provider == null) watchedItemsPreferences.getWatchedEpisodesWithTimestamps(contentId)
                else kotlinx.coroutines.flow.flow { emit(provider.watchedShowEpisodes()[contentId].orEmpty().associateWith { 0L }) }
            combine(watched, watchedItems, getAllEpisodeProgress(contentId)) { known, rows, progress ->
                val times = known.toMutableMap()
                rows.filter { it.contentId == contentId && it.season != null && it.episode != null }.forEach {
                    times[it.season!! to it.episode!!] = maxOf(times[it.season!! to it.episode!!] ?: 0, it.watchedAt)
                }
                progress.filterValues { it.isCompleted() }.forEach { (key, row) -> times[key] = maxOf(times[key] ?: 0, row.lastWatched) }
                times
            }
        }

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            getProgress(contentId, profileId)
        }
    }

    override fun getProgress(contentId: String, profileId: Int): Flow<WatchProgress?> {
        return profileProgressProviderFlow(profileId)
            .flatMapLatest { provider ->
                if (provider != null) {
                    combine(provider.allProgress, watchProgressPreferences.observeDeviceProgress(profileId, provider.providerId.name)) { remote, local ->
                        (remote + local)
                            .filter { it.contentId.equals(contentId, ignoreCase = true) }
                            .maxByOrNull(WatchProgress::lastWatched)
                    }
                } else {
                    watchProgressPreferences.getProgress(contentId, profileId)
                }
            }
    }

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            getEpisodeProgress(contentId, season, episode, profileId)
        }
    }

    override fun getProviderEpisodeProgress(
        contentId: String,
        season: Int,
        episode: Int,
        profileId: Int
    ): Flow<WatchProgress?> {
        val existing = profileProgressProviderFlow(profileId)
            .flatMapLatest { provider ->
                if (provider != null) {
                    combine(
                        provider.episodeProgress(contentId),
                        provider.allProgress
                    ) { items, allProgress ->
                        resolveProviderEpisodeProgress(
                            contentId = contentId,
                            season = season,
                            episode = episode,
                            episodeProgress = items,
                            allProgress = allProgress
                        )
                    }
                } else {
                    watchProgressPreferences.getEpisodeProgress(contentId, season, episode, profileId)
                }
            }
        return existing
    }

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int, profileId: Int): Flow<WatchProgress?> {
        val existing = getProviderEpisodeProgress(contentId, season, episode, profileId)
        val tvdb = tvdbTracking ?: return getAllEpisodeProgress(contentId, profileId).map { it[season to episode] }
        return tvdb.observeSelection(profileId, contentId).flatMapLatest { selected ->
            if (selected != null) getAllEpisodeProgress(contentId, profileId).map { it[season to episode] } else existing
        }
    }

    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            getAllEpisodeProgress(contentId, profileId)
        }
    }

    override fun getEpisodeProgress(contentId: String, context: com.nuvio.tv.domain.model.TvdbPlaybackContext, profileId: Int): Flow<WatchProgress?> =
        combine(selectedTvdbProgress(contentId, profileId, context).map { it[context.season to context.episode] },
            kotlinx.coroutines.flow.flow {
                val cloud = if (traktSettingsDataStore.getWatchProgressSource(profileId) == WatchProgressSource.NUVIO_SYNC &&
                    authManager.isAuthenticated && !context.providerSources.isNullOrEmpty()) {
                    runCatching {
                        withTimeoutOrNull(2_500L) { watchProgressSyncService.readTvdbResume(profileId) }
                            ?.let { com.nuvio.tv.data.tvdb.nativeCloudResume(contentId, context, it) }
                    }.getOrNull()
                } else null
                emit(cloud)
            }) { local, cloud ->
            if (cloud != null && (local == null || cloud.lastWatched > local.lastWatched)) cloud else local
        }

    private fun selectedTvdbProgress(contentId: String, profileId: Int, context: com.nuvio.tv.domain.model.TvdbPlaybackContext? = null): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        val tvdb = tvdbTracking ?: return getAllEpisodeProgress(contentId, profileId)
        return profileProgressProviderFlow(profileId).flatMapLatest { provider ->
            val nativeRecords = if (com.nuvio.tv.BuildConfig.FLAVOR == "mobile" && provider?.providerId == TrackingProviderId.MDBLIST)
                provider.nativeEpisodeRecords(contentId) else null
            val remoteRows = if (nativeRecords != null && provider != null)
                combine(nativeRecords, provider.allProgress) { native, other ->
                    // A mapped catalogue can omit a split episode or merge several into one slot.
                    // Keep native history and pauses separate until TVDB verifies their coverage.
                    native + other.filter { it.contentId != contentId }
                }
            else if (provider?.providerId == TrackingProviderId.TRAKT)
                tvdb.canonicalTraktRecords(profileId, contentId, context) ?: provider.episodeProgress(contentId).map { it.values.toList() }
                else if (provider != null) combine(provider.episodeProgress(contentId), provider.allProgress) { episodes, all ->
                    // A completed row and a new pause can share coordinates. Keep both
                    // events until the selected-order projection resolves their precedence.
                    (episodes.values + all).distinct()
                } else flowOf(emptyList())
            val remote = withWatchEvents(remoteRows, profileId)
            val revoked = cwRepository?.projection?.map { state ->
                state?.takeIf { it.profileId == profileId && it.source == provider?.providerId?.name }?.ignoredProgress.orEmpty()
            }?.distinctUntilChanged() ?: flowOf(emptyMap())
            val local = if (provider == null) watchProgressPreferences.observeEpisodeRecords(contentId, profileId)
                else watchProgressPreferences.observeDeviceProgress(profileId, provider.providerId.name).map { rows -> rows.filter { it.contentId == contentId } }
            combine(remote, local, revoked) { entries, records, ignored ->
                tvdb.projectProgress(profileId, contentId, emptyMap(),
                    (records + entries)
                        .filterNot { it.isRemovedCwPause(ignored) }, context).filterValues { part ->
                    part.tvdbContentIds.none { id ->
                        (ignored[com.nuvio.tv.data.cw.tvdbRemovalKey(contentId, id)] ?: Long.MIN_VALUE) >= part.lastWatched
                    }
                }
            }

        }.distinctUntilChanged()
    }

    override fun getAllEpisodeProgress(
        contentId: String,
        profileId: Int
    ): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        val existing = profileProgressProviderFlow(profileId)
            .flatMapLatest { provider ->
                if (provider != null) {
                    combine(
                        provider.episodeProgress(contentId),
                        combine(provider.allProgress, watchProgressPreferences.observeDeviceProgress(profileId, provider.providerId.name)) { remote, local -> remote + local }.map { items ->
                            items.filter {
                                it.contentId.equals(contentId, ignoreCase = true) &&
                                    it.season != null && it.episode != null
                            }
                        }
                    ) { remoteMap, liveEpisodes ->
                        val merged = remoteMap.toMutableMap()
                        liveEpisodes.forEach { episodeProgress ->
                            val seasonNum = episodeProgress.season ?: return@forEach
                            val episodeNum = episodeProgress.episode ?: return@forEach
                            val key = seasonNum to episodeNum
                            if ((merged[key]?.lastWatched ?: Long.MIN_VALUE) <= episodeProgress.lastWatched) merged[key] = episodeProgress
                        }
                        merged
                    }.distinctUntilChanged()
                } else {
                    watchProgressPreferences.getAllEpisodeProgress(contentId, profileId)
                }
            }
        val tvdb = tvdbTracking ?: return withEpisodeEvents(existing, profileId)
        return withEpisodeEvents(tvdb.observeSelection(profileId, contentId).flatMapLatest { selected ->
            if (selected == null) existing else selectedTvdbProgress(contentId, profileId)
        }, profileId)
    }

    override fun getAiredEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                provider?.airedEpisodeOrder(contentId) ?: flowOf(emptyList())
            }
            .distinctUntilChanged()
    }

    override fun observeNextUpSeeds(): Flow<List<WatchProgress>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        activeProgressProviderFlow().flatMapLatest { provider ->
            val history = provider?.nextUpSeeds ?: localNextUpSeeds()
            val local = if (provider == null) watchProgressPreferences.observeAllRawProgress(profileId)
                else watchProgressPreferences.observeDeviceProgress(profileId, provider.providerId.name)
            val candidates = combine(history, local,
                tvdbTracking?.observeSelectedSeries(profileId) ?: flowOf(emptySet<Long>())) { seeds, records, selected ->
                seeds to records.filter { (it.tvdbContext == null || it.tvdbContext.seriesId in selected) && it.isCompleted() }
            }
            val journal = deviceEvents?.get()
            if (com.nuvio.tv.BuildConfig.FLAVOR == "mobile" && journal != null) {
                val source = provider?.providerId?.name ?: WatchProgressSource.NUVIO_SYNC.name
                val revoked = cwRepository?.projection?.map { state ->
                    state?.takeIf { it.profileId == profileId && it.source == source }?.ignoredProgress.orEmpty()
                }?.distinctUntilChanged() ?: flowOf(emptyMap())
                combine(candidates, journal.observe(profileId), revoked, layoutPreferenceDataStore.nextUpFromFurthestEpisode) { (seeds, parts), events, ignored, furthest ->
                    survivingNextUpSeeds(seeds, parts, furthest, { rows ->
                        journal.decorate(rows, events).filterNot { it.isRevokedHistory(ignored) }
                    }) { id ->
                        provider?.completedNextUpHistory(id) ?: watchedItemsPreferences.getAllItems(profileId)
                            .filter { it.contentId == id }.map { it.toNextUpSeed() }
                    }
                }.flowOn(Dispatchers.Default)
            } else withWatchEvents(candidates.map { (seeds, parts) ->
                if (parts.isEmpty()) seeds else (seeds + parts).groupBy { it.contentId }.values.map { it.maxBy(WatchProgress::lastWatched) }
            }, profileId)
        }
    }.distinctUntilChanged()

    private fun localNextUpSeeds() = combine(watchedItemsPreferences.allItems, layoutPreferenceDataStore.nextUpFromFurthestEpisode) { items, furthest ->
        // Watched items retain paginated history beyond the watch-progress limit.
        items.filter { item ->
            (item.contentType.equals("series", true) || item.contentType.equals("tv", true)) &&
                item.season != null && item.season != 0 && item.episode != null && !isMalformedNextUpSeedContentId(item.contentId)
        }.groupBy { it.contentId }.values.map { episodes ->
            val order = compareBy<WatchedItem>({ it.season }, { it.episode })
            val byTime = compareBy(WatchedItem::watchedAt)
            val selected = episodes.maxWith(if (furthest) order.then(byTime) else byTime.then(order))
            val seed = selected.toNextUpSeed()
            val activity = if (com.nuvio.tv.BuildConfig.FLAVOR == "mobile") episodes.maxOf { it.watchedAt } else selected.watchedAt
            if (activity > selected.watchedAt) seed.copy(latestSeriesActivity = activity) else seed
        }
    }.flowOn(Dispatchers.Default)

    private fun WatchedItem.toNextUpSeed() = WatchProgress(contentId, contentType, title, null, null, null,
        contentId, season, episode, null, 1L, 1L, watchedAt, progressPercent = 100f,
        tvdbEpisodeId = tvdbEpisodeId, tvdbEpisodeIds = tvdbEpisodeIds)

    private fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
        val trimmed = contentId?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        val lowered = trimmed.lowercase()
        return lowered == "tmdb" ||
            lowered == "imdb" ||
            lowered == "trakt" ||
            lowered == "tmdb:" ||
            lowered == "imdb:" ||
            lowered == "trakt:"
    }

    override fun observeOptimisticContinueWatchingUpdates(): Flow<WatchProgress> {
        return optimisticContinueWatchingUpdates
    }

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress {
        return activeProgressProvider()?.prepareNextUpSeed(progress) ?: progress
    }

    @OptIn(FlowPreview::class)
    override fun observeWatchedMovieIds(): Flow<Set<String>> {
        val baseFlow = activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    provider.watchedMovieIds
                } else {
                    combine(
                        watchProgressPreferences.allProgress,
                        watchedItemsPreferences.allItems
                    ) { progressList, watchedItems ->
                        val completedIds = mutableSetOf<String>()
                        val replayingIds = mutableSetOf<String>()
                        for (progress in progressList) {
                            if (progress.isCompleted()) {
                                completedIds.add(progress.contentId)
                            } else if (progress.position > 0L ||
                                progress.progressPercent?.let { it > 0f } == true
                            ) {
                                replayingIds.add(progress.contentId)
                            }
                        }
                        val watchedItemIds = watchedItems
                            .filter { it.season == null && it.episode == null }
                            .map { it.contentId }
                            .toSet()
                        (completedIds + watchedItemIds) - replayingIds
                    }.debounce(500)
                }
            }
        return combine(
            baseFlow,
            profileManager.activeProfileId,
            optimisticWatchedMovieAdditions,
            optimisticWatchedMovieRemovals
        ) { base, profileId, additions, removals ->
            (base + additions[profileId].orEmpty()) - removals[profileId].orEmpty()
        }.distinctUntilChanged()
    }

    override fun applyOptimisticWatchedMovie(ids: Set<String>, add: Boolean) {
        val profileId = profileManager.activeProfileId.value
        if (add) {
            optimisticWatchedMovieAdditions.update { it.plusIds(profileId, ids) }
            optimisticWatchedMovieRemovals.update { it.minusIds(profileId, ids) }
        } else {
            optimisticWatchedMovieRemovals.update { it.plusIds(profileId, ids) }
            optimisticWatchedMovieAdditions.update { it.minusIds(profileId, ids) }
        }
    }

    override fun revertOptimisticWatchedMovie(ids: Set<String>, add: Boolean) {
        // The active profile may have changed since the toggle, so revert it wherever it is.
        val target = if (add) optimisticWatchedMovieAdditions else optimisticWatchedMovieRemovals
        target.update { byProfile -> byProfile.keys.fold(byProfile) { acc, profileId -> acc.minusIds(profileId, ids) } }
    }

    private fun Map<Int, Set<String>>.plusIds(profileId: Int, ids: Set<String>): Map<Int, Set<String>> =
        this + (profileId to (this[profileId].orEmpty() + ids))

    private fun Map<Int, Set<String>>.minusIds(profileId: Int, ids: Set<String>): Map<Int, Set<String>> {
        val remaining = this[profileId].orEmpty() - ids
        return if (remaining.isEmpty()) this - profileId else this + (profileId to remaining)
    }

    override suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        val profileId = profileManager.activeProfileId.value
        val provider = activeProgressProvider()
        if (provider != null) {
            val providerEpisodes = provider.watchedShowEpisodes()
            if (profileManager.activeProfileId.value != profileId) return emptyMap()
            val localItems = watchProgressPreferences.observeDeviceProgress(profileId, provider.providerId.name).first()
                .filter { it.isCompleted() }.map { it.toWatchedItem() }
            if (profileManager.activeProfileId.value != profileId) return emptyMap()
            return mergeWatchedEpisodeProjection(
                providerEpisodes = providerEpisodes,
                localItems = localItems,
                retainsLocalWatchedEpisode = { true }
            )
        }
        return watchedItemsPreferences.getAllItems(profileId)
            .filter { it.season != null && it.episode != null }
            .groupBy { it.contentId }
            .mapValues { (_, items) -> items.map { it.season!! to it.episode!! }.toSet() }
    }

    override suspend fun getShowIdSiblings(): Map<String, Set<String>> {
        return activeProgressProvider()?.showIdSiblings().orEmpty()
    }

    override suspend fun cachedWatchedShowIds(profileId: Int): Set<String> =
        profileProgressProviderFlow(profileId).first()?.cachedWatchedShowIds(profileId).orEmpty()

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val providerId = activeProgressProviderId ?: return null
        return trackingProgressProviders.provider(providerId)?.isWatchedByVideoId(videoId, episode)
    }

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider == null) {
                    val progressFlow = if (season != null && episode != null) {
                        watchProgressPreferences.getEpisodeProgress(contentId, season, episode)
                    } else {
                        watchProgressPreferences.getProgress(contentId)
                    }
                    return@flatMapLatest combine(
                        progressFlow,
                        watchedItemsPreferences.isWatched(contentId, season, episode)
                    ) { progressEntry, itemWatched ->
                        val hasStartedReplay = progressEntry?.let { entry ->
                            !entry.isCompleted() &&
                                (entry.position > 0L || entry.progressPercent?.let { it > 0f } == true)
                        } == true

                        if (hasStartedReplay) {
                            false
                        } else {
                            (progressEntry?.isCompleted() == true) || itemWatched
                        }
                    }
                }

                provider.isWatched(contentId, videoId, season, episode)
            }
    }

    private suspend fun publishContinueWatching(profileId: Int, updates: List<WatchProgress>) {
        try {
            if (!isSyncingFromRemote) watchProgressPreferences.recordDeviceProgress(profileId, updates)
            val owner = cwRepository ?: return
            owner.committedProgress(profileId, updates)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The existing progress store is already committed. A projection I/O failure
            // must not suppress watched/scrobble delivery; refresh replays that progress.
            Log.e(TAG, "CW projection commit failed; committed progress will be replayed", error)
        }
    }

    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) {
        val profileId = profileManager.activeProfileId.value
        saveProgress(progress, profileId, syncRemote)
    }

    override suspend fun saveProgress(
        progress: WatchProgress,
        profileId: Int,
        syncRemote: Boolean
    ) {
        if (progress.tvdbEpisodeIds == null && tvdbTracking != null) {
            val identified = tvdbTracking.identifyProgress(profileId, progress)
            if (!identified.tvdbEpisodeIds.isNullOrEmpty()) { saveProgress(identified, profileId, syncRemote); return }
        }
        val progress = if (isSyncingFromRemote) progress else deviceEvents?.get()?.record(profileId, progress) ?: progress
        if (progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(progress.contentId, profileId)
        }
        val progressKey = progressKey(progress)
        val shouldPushRemote = syncRemote &&
            authManager.isAuthenticated &&
            remoteProgressWriteDeduplicator.shouldSend(
                profileId = profileId,
                progressKey = progressKey,
                progress = progress,
                nowMs = SystemClock.elapsedRealtime()
            )
        if (syncRemote) {
            mutationStore.queueProgressUpserts(mapOf(progressKey to progress), profileId)
        }
        if (profileManager.activeProfileId.value == profileId && progress.tvdbEpisodeIds == null) {
            activeProgressProvider()?.applyOptimisticProgress(progress, quiet = !syncRemote)
        }
        watchProgressPreferences.saveProgress(progress, profileId = profileId)
        publishContinueWatching(profileId, listOf(progress))

        if (shouldPushRemote) {
            syncScope.launch(NonCancellable) {
                watchProgressSyncService.pushSingleToRemote(progressKey, progress, profileId)
                    .onFailure { error ->
                        Log.w(TAG, "Failed single progress push; pending mutation retained", error)
                        triggerRemoteSync(profileId)
                    }
            }
        } else if (syncRemote && authManager.isAuthenticated) {
            triggerRemoteSync(profileId)
        }

        if (progress.isCompleted()) {
            if (progress.tvdbContext != null) {
                recordTvdbNativeWatched(profileId, listOf(progress), syncRemote)
                broadcastHistoryAdd(profileId, listOf(progress.toTrackingHistoryItem(progress.lastWatched)), coverageChangesOnly = true)
                if (profileManager.activeProfileId.value == profileId) optimisticContinueWatchingUpdates.tryEmit(progress)
                return
            }
            val watchedItem = progress.toWatchedItem()
            watchedItemsPreferences.markAsWatched(watchedItem, profileId = profileId)
            if (syncRemote) {
                mutationStore.queueWatchedUpserts(listOf(watchedItem), profileId)
            }
            if (syncRemote && authManager.isAuthenticated) {
                triggerWatchedItemsSync(listOf(watchedItem), profileId = profileId)
            }
            if (profileManager.activeProfileId.value == profileId) {
                optimisticContinueWatchingUpdates.tryEmit(progress)
            }
        }
    }

    override suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean) {
        if (progressList.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        val progressList = if (tvdbTracking == null) progressList else progressList.map { tvdbTracking.identifyProgress(profileId, it) }
        if (syncRemote) {
            mutationStore.queueProgressUpserts(
                progressList.associateBy(::progressKey),
                profileId
            )
        }
        if (syncRemote) {
            if (profileManager.activeProfileId.value == profileId) {
                activeProgressProvider()?.let { provider ->
                    progressList.filter { it.tvdbContext == null && it.tvdbEpisodeIds == null }.forEach { progress -> provider.applyOptimisticProgress(progress, quiet = false) }
                }
            }
        }
        watchProgressPreferences.saveProgressBatch(progressList, profileId = profileId)
        publishContinueWatching(profileId, progressList)

        if (syncRemote && authManager.isAuthenticated) {
            triggerRemoteSync(profileId = profileId)
        }

        val completedWatchedItems = progressList
            .filter { it.isCompleted() && it.tvdbContext == null }
            .map { progress -> progress.toWatchedItem() }
        if (completedWatchedItems.isNotEmpty()) {
            watchedItemsPreferences.markAsWatchedBatch(completedWatchedItems, profileId = profileId)
            if (syncRemote) {
                mutationStore.queueWatchedUpserts(completedWatchedItems, profileId)
            }
            if (syncRemote && authManager.isAuthenticated) {
                triggerWatchedItemsSync(completedWatchedItems, profileId = profileId)
            }
        }
        recordTvdbNativeWatched(profileId, progressList.filter { it.isCompleted() && it.tvdbContext != null }, syncRemote)
    }

    override suspend fun removeLastTraktWatch(contentId: String, video: Video, event: WatchProgress) {
        val journal = deviceEvents?.get()
        if (journal != null) {
            require(event.contentId == contentId && event.hasRemovableWatchEvent())
            val profile = profileManager.activeProfileId.value
            val removed = journal.requestRemoval(profile, event)
            val local = watchProgressPreferences.getAllRawEntries(profile).filterValues { removed.removes(it) }
            mutationStore.acknowledgeProgressUpserts(local, profile)
            watchProgressPreferences.removeMatchingProgress(local, profile)
            val remaining = watchProgressPreferences.getAllRawEntries(profile)
            val restore = removed.priorLocal.filter { (key, _) -> key !in remaining }.values.toList()
            if (restore.isNotEmpty()) watchProgressPreferences.saveProgressBatch(restore, profile)
            watchProgressPreferences.revokeDeviceProgress(profile, removed.id, local.values.toList())
            val marked = watchedItemsPreferences.getAllItems(profile).filter { it.contentId == contentId &&
                it.season == video.season && it.episode == video.episode && removed.contains(it.watchedAt) }
            mutationStore.acknowledgeWatchedUpserts(marked, profile)
            for (row in marked) watchedItemsPreferences.unmarkAsWatched(row.contentId, row.season, row.episode, profile)
            if (removed.priorWatched.isNotEmpty()) watchedItemsPreferences.markAsWatchedBatch(removed.priorWatched, profile)
            // The durable undo journal filters the removed event from every view.
            // Publish remaining history before waiting for all connected trackers.
            cwRepository?.deviceWatchRemoved(profile, removed)
            journal.reconcile(profile)
            // Reconciliation may restore an earlier watch of the same episode.
            // Consume that committed history without another full tracker refresh.
            cwRepository?.historyCommitted(profile, contentId, before = removed.startedAt)
            if (authManager.isAuthenticated) watchProgressSyncService.removeWatchMirrors(profile) { removed.removes(it) }
            return
        }
        require(event.contentId == contentId && event.hasRemovableTraktWatch())
        val profile = profileManager.activeProfileId.value
        check(profileProgressProviderFlow(profile).first()?.providerId == TrackingProviderId.TRAKT)
        fun matches(row: WatchProgress): Boolean {
            if (row.contentId != contentId || row.lastWatched != event.lastWatched) return false
            val context = video.tvdbPlaybackContext()
            return if (context == null) row.videoId == video.id && row.season == video.season && row.episode == video.episode
            else row.tvdbContext == context || context.providerSources.orEmpty().any { native ->
                row.tvdbContext == null && row.videoId == native.videoId &&
                    row.season == native.coordinates.season && row.episode == native.coordinates.episode
            }
        }
        val local = watchProgressPreferences.getAllRawEntries(profile).filterValues(::matches)
        checkNotNull(traktEvents).get().removeLastEpisodeWatch(profile, event)
        mutationStore.acknowledgeProgressUpserts(local, profile)
        watchProgressPreferences.removeMatchingProgress(local, profile)
        cwRepository?.lastWatchRemoved(profile, event)
        if (authManager.isAuthenticated) watchProgressSyncService.removeWatchMirrors(profile, ::matches)
    }

    override suspend fun removeEpisodePartialWatch(contentId: String, video: Video, resume: WatchProgress) {
        require(resume.hasRemovableEpisodePause())
        val profile = profileManager.activeProfileId.value
        val context = video.tvdbPlaybackContext()
        require(context == null || context.episodeIds.isNotEmpty())
        val source = buildTrackingMediaReference("series", contentId, video.id, seasonNumber = requireNotNull(video.season),
            episodeNumber = requireNotNull(video.episode), episodeTitle = video.title)
        val reference = source.copy(catalog = requireNotNull(source.catalog).copy(tvdbContext = context))
        suspend fun matchesPause(row: WatchProgress): Boolean =
            row.matchesPartialEpisode(contentId, video) || (row.contentId == contentId &&
                row.hasRemovableEpisodePause() && context != null && tvdbTracking?.projectProgress(
                    profile, contentId, emptyMap(), listOf(row))?.values?.any {
                    it.matchesPartialEpisode(contentId, video)
                } == true)
        val local = watchProgressPreferences.getAllRawEntries(profile).filterValues { matchesPause(it) }
        var complete = true
        for (provider in connectedProgressProviders()) {
            check(profileManager.activeProfileId.value == profile)
            try {
                val targets = if (tvdbTracking == null) listOf(reference)
                    else requireNotNull(tvdbTracking.resolveAll(profile, provider.providerId, reference))
                check(targets.isNotEmpty())
                for (target in targets) provider.removeEpisodePause(target, resume)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Episode pause removal unavailable for ${provider.providerId.storageId}", e)
                complete = false
            }
        }
        check(profileManager.activeProfileId.value == profile)
        if (authManager.isAuthenticated && !ServerItemRef.isServerId(contentId)) {
            val remote = watchProgressSyncService.pullFromRemote(profile, forTvdbResume = true, rawKeys = true)
            val keys = remote.getOrNull().orEmpty().filter { (_, row) -> matchesPause(row) }
                .map { it.first }
            if (remote.isFailure || (keys.isNotEmpty() && watchProgressSyncService.deleteFromRemote(keys, profile).isFailure)) {
                complete = false
            }
        }
        check(complete)
        mutationStore.acknowledgeProgressUpserts(local, profile)
        watchProgressPreferences.removeMatchingProgress(local, profile)
        cwRepository?.partialWatchRemoved(profile, resume)
        triggerRemoteSync(profile)
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
        if (season == null && episode == null) cwRepository?.remove(setOf(contentId), profileId)
        if (season != null && episode != null && tvdbTracking?.isSelected(profileId, contentId) == true) {
            removeSelectedTvdbEpisode(profileId, contentId, null, season, episode, history = false)
            return
        }
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode, profileId = profileId)
        mutationStore.queueProgressDeletes(remoteDeleteKeys, profileId)
        val sourceReference = buildTrackingMediaReference(
            contentType = if (episode != null) "series" else "movie", parentMetaId = contentId,
            seasonNumber = season, episodeNumber = episode)
        supervisorScope {
            connectedProgressProviders().map { provider ->
                async {
                    runCatching {
                        val destination = if (tvdbTracking == null) sourceReference else
                            tvdbTracking.resolve(profileId, provider.providerId, sourceReference) ?: return@runCatching
                        // Playback-removal APIs accept coordinates, unlike history writes with IDs.
                        if (provider.providerId == TrackingProviderId.MDBLIST && destination.episode?.usesTvdbSeasonMapping == true) return@runCatching
                        val targetSeason = destination.episode?.season ?: season
                        val targetEpisode = destination.episode?.number ?: episode
                        provider.applyOptimisticRemoval(contentId, targetSeason, targetEpisode)
                        provider.removeProgress(destination.catalog?.contentId ?: contentId, targetSeason, targetEpisode)
                    }
                        .onFailure { error ->
                            Log.w(TAG, "Failed ${provider.providerId.storageId} progress removal", error)
                        }
                }
            }.forEach { operation -> operation.await() }
        }
        watchProgressPreferences.removeProgress(contentId, season, episode, profileId)
        if (authManager.isAuthenticated && remoteDeleteKeys.isNotEmpty()) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys, profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeProgress remote delete failed; pending mutation retained", error)
                }
        }
        triggerRemoteSync(profileId = profileId)
    }

    override suspend fun removeFromHistory(contentId: String, videoId: String?, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
        if (season != null && episode != null && tvdbTracking?.isSelected(profileId, contentId) == true) {
            removeSelectedTvdbEpisode(profileId, contentId, videoId, season, episode, history = true)
            return
        }
        if (season == null || episode == null) cwRepository?.remove(setOf(contentId), profileId)
        val remoteDeleteKeys = resolveRemoteDeleteKeys(contentId, season, episode, profileId = profileId)
        val watchedDeleteKey = WatchedMutationKey(contentId, season, episode)
        mutationStore.queueProgressDeletes(remoteDeleteKeys, profileId)
        mutationStore.queueWatchedDeletes(listOf(watchedDeleteKey), profileId)
        val media = buildTrackingMediaReference(
            contentType = if (season != null || episode != null) "series" else "movie",
            parentMetaId = contentId,
            videoId = videoId,
            seasonNumber = season,
            episodeNumber = episode
        )
        if (tvdbTracking?.isSelected(profileId, contentId) != true) {
            connectedProgressProviders().forEach { provider -> provider.applyOptimisticRemoval(contentId, season, episode) }
        }
        broadcastHistoryRemoval(profileId, listOf(media))
        syncServerWatched(
            listOf(ServerWatchMark(contentId, if (season != null || episode != null) "series" else "movie", videoId, season, episode)),
            played = false,
            profileId = profileId
        )
        watchProgressPreferences.removeProgress(contentId, season, episode, profileId)
        watchedItemsPreferences.unmarkAsWatched(contentId, season, episode, profileId = profileId)
        if (season != null && episode != null) cwRepository?.historyRemoved(profileId, contentId, setOf(season to episode))
        if (authManager.isAuthenticated && remoteDeleteKeys.isNotEmpty()) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys, profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistory remote delete failed; pending mutation retained", error)
                }
        }
        if (authManager.isAuthenticated) {
            watchedItemsSyncService.deleteFromRemote(contentId, season, episode, profileId = profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistory watched item remote delete failed; pending mutation retained", error)
                    triggerWatchedItemsSync(profileId)
                }
        }
        triggerRemoteSync(profileId = profileId)
    }

    override suspend fun removeFromHistoryBatch(
        contentId: String,
        videoId: String?,
        episodes: List<Triple<Int, Int, String?>>
    ) {
        if (episodes.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        if (tvdbTracking?.isSelected(profileId, contentId) == true) {
            episodes.forEach { (season, number, id) -> removeSelectedTvdbEpisode(profileId, contentId, id ?: videoId, season, number, history = true) }
            return
        }
        val episodePairs = episodes.map { (season, episode, _) -> season to episode }
        val remoteDeleteKeys = episodes.map { (season, episode, _) ->
            "${contentId}_s${season}e${episode}"
        } + contentId
        val watchedDeleteKeys = episodePairs.map { (season, episode) ->
            WatchedMutationKey(contentId, season, episode)
        }
        mutationStore.queueProgressDeletes(remoteDeleteKeys, profileId)
        mutationStore.queueWatchedDeletes(watchedDeleteKeys, profileId)
        watchProgressPreferences.removeProgressBatch(contentId, episodePairs, profileId)
        watchedItemsPreferences.unmarkAsWatchedBatch(contentId, episodePairs, profileId = profileId)
        if (tvdbTracking?.isSelected(profileId, contentId) != true) {
            connectedProgressProviders().forEach { provider ->
                episodes.forEach { (season, episode, _) -> provider.applyOptimisticRemoval(contentId, season, episode) }
            }
        }
        val media = episodes.map { (season, episode, epVideoId) ->
            buildTrackingMediaReference(
                contentType = "series",
                parentMetaId = contentId,
                videoId = epVideoId ?: videoId,
                seasonNumber = season,
                episodeNumber = episode
            )
        }
        broadcastHistoryRemoval(profileId, media)
        cwRepository?.historyRemoved(profileId, contentId, episodePairs.toSet())
        syncServerWatched(
            episodes.map { (season, episode, epVideoId) ->
                ServerWatchMark(contentId, "series", epVideoId ?: videoId, season, episode)
            },
            played = false,
            profileId = profileId
        )
        if (authManager.isAuthenticated) {
            watchProgressSyncService.deleteFromRemote(remoteDeleteKeys.distinct(), profileId)
                .onFailure { error -> Log.w(TAG, "removeFromHistoryBatch remote delete failed", error) }
            watchedItemsSyncService.deleteFromRemoteBatch(contentId, episodePairs, profileId = profileId)
                .onFailure { error ->
                    Log.w(TAG, "removeFromHistoryBatch watched item remote delete failed; pending mutation retained", error)
                    triggerWatchedItemsSync(profileId)
                }
        }
        triggerRemoteSync(profileId = profileId)
    }

    override suspend fun markAsCompleted(progress: WatchProgress, broadcastTrackingHistory: Boolean, watchedAt: Long?) {
        val profileId = profileManager.activeProfileId.value
        completeProgress(progress, profileId, broadcastTrackingHistory, watchedAt)
    }

    override suspend fun markAsCompleted(
        progress: WatchProgress,
        profileId: Int,
        broadcastTrackingHistory: Boolean
    ) {
        completeProgress(progress, profileId, broadcastTrackingHistory, watchedAt = null)
    }

    private suspend fun completeProgress(progress: WatchProgress, profileId: Int, broadcastTrackingHistory: Boolean, watchedAt: Long?) {
        if (progress.tvdbCoverageVerified == false) {
            saveProgress(progress, profileId)
            return
        }
        if (progress.tvdbEpisodeIds == null && tvdbTracking != null) {
            val identified = tvdbTracking.identifyProgress(profileId, progress)
            if (!identified.tvdbEpisodeIds.isNullOrEmpty()) { completeProgress(identified, profileId, broadcastTrackingHistory, watchedAt); return }
        }
        if (progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(progress.contentId, profileId)
        }
        val eventTime = watchedAt ?: System.currentTimeMillis()
        val progressKey = progressKey(progress)
        val duration = progress.duration.takeIf { it > 1L }
            ?: watchProgressPreferences.getAllRawEntries(profileId)[progressKey]
                ?.duration
                ?.takeIf { it > 1L }
            ?: 1L
        val completedRow = progress.copy(
            position = duration,
            duration = duration,
            progressPercent = 100f,
            lastWatched = eventTime,
            tvdbCompletedEpisodeIds = progress.tvdbEpisodeIds
        )
        val completed = deviceEvents?.get()?.record(profileId, completedRow, separateEvent = watchedAt != null) ?: completedRow
        mutationStore.queueProgressUpserts(mapOf(progressKey to completed), profileId)
        if (profileManager.activeProfileId.value == profileId) {
            optimisticContinueWatchingUpdates.tryEmit(completed)
            if (completed.tvdbEpisodeIds == null) activeProgressProvider()?.applyOptimisticProgress(completed, quiet = false)
        }
        watchProgressPreferences.saveProgress(completed, profileId = profileId)
        publishContinueWatching(profileId, listOf(completed))
        if (completed.tvdbContext != null) {
            recordTvdbNativeWatched(profileId, listOf(completed), syncRemote = true)
            broadcastHistoryAdd(profileId, listOf(completed.toTrackingHistoryItem(eventTime, watchedAt != null)), coverageChangesOnly = !broadcastTrackingHistory)
            triggerRemoteSync(profileId)
            return
        }
        val watchedItem = completed.toWatchedItem(watchedAt = eventTime)
        watchedItemsPreferences.markAsWatched(watchedItem, profileId = profileId)
        mutationStore.queueWatchedUpserts(listOf(watchedItem), profileId)
        if (broadcastTrackingHistory || completed.tvdbEpisodeIds?.isNotEmpty() == true) {
            broadcastHistoryAdd(profileId, listOf(completed.toTrackingHistoryItem(eventTime, watchedAt != null)), coverageChangesOnly = !broadcastTrackingHistory)
        }
        if (broadcastTrackingHistory) {
            syncServerWatched(listOf(completed.toServerWatchMark()), played = true, profileId = profileId)
        }
        triggerRemoteSync(profileId = profileId)
        triggerWatchedItemsSync(listOf(watchedItem), profileId = profileId)
    }

    override suspend fun markAsCompletedBatch(progressList: List<WatchProgress>, watchedAt: Long?) {
        if (progressList.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        val progressList = if (tvdbTracking == null) progressList else progressList.map { tvdbTracking.identifyProgress(profileId, it) }
        val firstProgress = progressList.first()
        if (firstProgress.contentType.equals("series", ignoreCase = true) ||
            firstProgress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(firstProgress.contentId, profileId)
        }
        val eventTime = watchedAt ?: System.currentTimeMillis()
        val rawEntries = watchProgressPreferences.getAllRawEntries(profileId)

        val completedList = progressList.map { progress ->
            if (progress.tvdbCoverageVerified == false) return@map progress
            val duration = progress.duration.takeIf { it > 1L }
                ?: rawEntries[progressKey(progress)]?.duration?.takeIf { it > 1L }
                ?: 1L
            progress.copy(
                position = duration,
                duration = duration,
                progressPercent = 100f,
                lastWatched = eventTime,
                tvdbCompletedEpisodeIds = progress.tvdbEpisodeIds
            )
        }

        mutationStore.queueProgressUpserts(completedList.associateBy(::progressKey), profileId)
        if (profileManager.activeProfileId.value == profileId) {
            activeProgressProvider()?.let { provider ->
                completedList.forEach { progress ->
                    optimisticContinueWatchingUpdates.tryEmit(progress)
                    if (progress.tvdbEpisodeIds == null) provider.applyOptimisticProgress(progress, quiet = false)
                }
            }
        }
        watchProgressPreferences.saveProgressBatch(completedList, profileId = profileId)
        publishContinueWatching(profileId, completedList)
        val watchedItems = completedList.filter { it.tvdbContext == null }.map { progress -> progress.toWatchedItem(watchedAt = eventTime) }
        recordTvdbNativeWatched(profileId, completedList.filter { it.tvdbContext != null && it.isCompleted() }, syncRemote = true)
        watchedItemsPreferences.markAsWatchedBatch(watchedItems, profileId = profileId)
        mutationStore.queueWatchedUpserts(watchedItems, profileId)
        broadcastHistoryAdd(profileId, completedList.map { it.toTrackingHistoryItem(eventTime, watchedAt != null) })
        syncServerWatched(completedList.map { it.toServerWatchMark() }, played = true, profileId = profileId)
        triggerRemoteSync(profileId = profileId)
        triggerWatchedItemsSync(watchedItems, profileId = profileId)
    }

    private fun syncServerWatched(marks: List<ServerWatchMark>, played: Boolean, profileId: Int) {
        serverWatched.apply(marks, played) { failed -> restoreServerWatched(failed, played, profileId) }
        serverWatched.mirror(marks, played)
    }

    private suspend fun restoreServerWatched(failed: List<ServerWatchMark>, played: Boolean, profileId: Int) {
        if (!played) {
            watchedItemsPreferences.markAsWatchedBatch(
                failed.map { WatchedItem(it.contentId, it.contentType, "", it.season, it.episode, System.currentTimeMillis()) },
                profileId = profileId
            )
            return
        }
        failed.forEach { mark ->
            watchedItemsPreferences.unmarkAsWatched(mark.contentId, mark.season, mark.episode, profileId = profileId)
            watchProgressPreferences.removeProgress(mark.contentId, mark.season, mark.episode, profileId)
        }
    }

    private fun WatchProgress.toServerWatchMark() = ServerWatchMark(contentId, contentType, videoId, season, episode)

    private suspend fun recordTvdbNativeWatched(profileId: Int, items: List<WatchProgress>, syncRemote: Boolean) {
        val tvdb = tvdbTracking ?: return
        val native = items.flatMap { progress -> tvdb.nativeCompletedEpisodes(profileId, progress).map { (coordinates, ids) ->
            progress.toWatchedItem(watchedAt = progress.lastWatched).copy(season = coordinates.season, episode = coordinates.episode,
                tvdbEpisodeId = ids.first(), tvdbEpisodeIds = ids)
        } }.distinctBy { Triple(it.contentId, it.season, it.episode) }
        if (native.isEmpty()) return
        watchedItemsPreferences.markAsWatchedBatch(native, profileId)
        if (syncRemote) {
            mutationStore.queueWatchedUpserts(native, profileId)
            if (authManager.isAuthenticated) triggerWatchedItemsSync(native, profileId)
        }
    }

    private suspend fun removeSelectedTvdbEpisode(profileId: Int, contentId: String, videoId: String?, season: Int, episode: Int, history: Boolean) {
        val tvdb = tvdbTracking ?: return
        val reference = buildTrackingMediaReference("series", contentId, videoId, seasonNumber = season, episodeNumber = episode)
        val context = tvdb.selectionContext(profileId, reference) ?: return
        val media = reference.copy(catalog = reference.catalog?.copy(tvdbContext = context))
        val entries = watchProgressPreferences.getAllRawEntries(profileId)
        val keys = entries.filter { (_, entry) -> entry.contentId == contentId && entry.tvdbContext != null &&
            (entry.tvdbContext == context || entry.tvdbContentIds.any(context.episodeIds::contains)) }.keys
        mutationStore.queueProgressDeletes(keys.toList(), profileId)
        watchProgressPreferences.removeProgressKeys(keys, profileId)
        if (authManager.isAuthenticated && keys.isNotEmpty()) watchProgressSyncService.deleteFromRemote(keys.toList(), profileId)
        if (history) {
            val removed = broadcastHistoryRemoval(profileId, listOf(media))
            val native = tvdb.nativeEpisodes(profileId, contentId, context)
            native.forEach { coordinates -> watchedItemsPreferences.unmarkAsWatched(contentId, coordinates.season, coordinates.episode, profileId) }
            mutationStore.queueWatchedDeletes(native.map { WatchedMutationKey(contentId, it.season, it.episode) }, profileId)
            if (authManager.isAuthenticated && native.isNotEmpty())
                watchedItemsSyncService.deleteFromRemoteBatch(contentId, native.map { it.season to it.episode }, profileId)
            if (cwRepository != null) {
                val primary = profileProgressProviderFlow(profileId).first()?.providerId
                val coordinates = removed[primary].orEmpty().filter { it.catalog?.contentId == contentId }
                    .mapNotNull { it.episode }.filter { it.numberingProvider == primary?.storageId }
                    .mapNotNull { ep -> ep.season?.let { it to ep.number } }.toSet()
                cwRepository.historyRemoved(profileId, contentId, coordinates, context.episodeIds.toSet())
            }
        } else {
            connectedProgressProviders().forEach { provider ->
                tvdb.resolveAll(profileId, provider.providerId, media).orEmpty().forEach { target ->
                    val destination = target.episode ?: return@forEach
                    provider.removeProgress(target.catalog?.contentId ?: contentId, destination.season, destination.number)
                }
            }
        }
        triggerRemoteSync(profileId)
    }

    private fun WatchProgress.toWatchedItem(watchedAt: Long = System.currentTimeMillis()): WatchedItem =
        WatchedItem(
            tvdbEpisodeId = tvdbEpisodeId,
            tvdbEpisodeIds = tvdbEpisodeIds,
            contentId = contentId,
            contentType = contentType,
            title = name,
            season = season,
            episode = episode,
            watchedAt = watchedAt
        )

    private fun WatchProgress.toTrackingHistoryItem(watchedAt: Long, userSelectedWatchTime: Boolean = false): TrackingHistoryItem =
        TrackingHistoryItem(
            media = buildTrackingMediaReference(
                contentType = contentType,
                parentMetaId = contentId,
                videoId = videoId,
                title = name,
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = episodeTitle
            ).let { media -> media.copy(catalog = media.catalog?.copy(tvdbEpisodeIds = tvdbEpisodeIds, tvdbContext = tvdbContext, providerDefault = providerDefaultPlayback, tvdbCoverageVerified = tvdbCoverageVerified != false)) },
            watchedAtEpochMs = watchedAt,
            userSelectedWatchTime = userSelectedWatchTime
        )

    private suspend fun broadcastHistoryAdd(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
        coverageChangesOnly: Boolean = false
    ) {
        val items = items.filterNot { ServerItemRef.isServerId(it.media.catalog?.contentId) }
        if (items.isEmpty()) return
        val connectedIds = connectedProgressProviders().mapTo(mutableSetOf()) { it.providerId }
        supervisorScope {
            trackingHistoryWriters.writers()
                .filter { writer -> writer.providerId in connectedIds }
                .map { writer ->
                    async {
                        runCatching {
                            val resolved = items.flatMap { item ->
                                if (tvdbTracking == null) if (coverageChangesOnly) emptyList() else listOf(item)
                                else tvdbTracking.resolveAll(profileId, writer.providerId, item.media, completed = true,
                                    coverageChangesOnly = coverageChangesOnly).orEmpty().map { item.copy(media = it) }
                            }.distinctBy { it.media.ids to it.media.episode }
                            if (resolved.isNotEmpty()) writer.addToHistory(profileId, resolved)
                        }
                            .onFailure { error ->
                                Log.w(TAG, "Failed ${writer.providerId.storageId} history add", error)
                            }
                    }
                }
                .forEach { operation -> operation.await() }
        }
    }

    private suspend fun broadcastHistoryRemoval(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): Map<TrackingProviderId, Collection<TrackingMediaReference>> {
        val items = items.filterNot { ServerItemRef.isServerId(it.catalog?.contentId) }
        if (items.isEmpty()) return emptyMap()
        val connectedIds = connectedProgressProviders().mapTo(mutableSetOf()) { it.providerId }
        return supervisorScope {
            trackingHistoryWriters.writers()
                .filter { writer -> writer.providerId in connectedIds }
                .map { writer ->
                    async {
                        runCatching {
                            val resolved = if (tvdbTracking == null) items else items.flatMap {
                                tvdbTracking.resolveAll(profileId, writer.providerId, it).orEmpty()
                            }
                            val result = writer.removeFromHistory(profileId, resolved)
                            writer.providerId to resolved.takeIf { result.isComplete }.orEmpty()
                        }
                            .onFailure { error ->
                                Log.w(TAG, "Failed ${writer.providerId.storageId} history removal", error)
                            }.getOrNull()
                    }
                }
                .mapNotNull { operation -> operation.await() }.toMap()
        }
    }

    override suspend fun clearAll() {
        val profileId = profileManager.activeProfileId.value
        val provider = activeProgressProvider()
        if (provider != null) {
            provider.clearOptimistic()
            watchProgressPreferences.clearAllPreservingNonTraktIds(
                profileId = profileId,
                isNonTraktId = provider::retainsLocalProgress
            )
            return
        }
        watchProgressPreferences.clearAll(profileId)
        cwRepository?.snapshot()?.let { cwRepository.remove(it.members.keys, profileId) }
    }

    override fun isDroppedShow(contentId: String): Boolean {
        return activeProgressProviderId
            ?.let(trackingProgressProviders::provider)
            ?.isHiddenFromProgress(contentId) == true
    }

    override fun hasActiveTrackingProgressProvider(): Boolean =
        activeProgressProviderId != null

    override fun activeProviderOwnsCompletedHistoryProjection(): Boolean =
        activeProgressProviderId
            ?.let(trackingProgressProviders::provider)
            ?.ownsCompletedHistoryProjection == true

    override fun activeProviderContinueWatchingCutoffEpochMs(
        daysCap: Int,
        nowEpochMs: Long
    ): Long? = com.nuvio.tv.domain.model.continueWatchingCutoff(daysCap, nowEpochMs)

    override fun shouldUseAsNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean =
        activeProgressProviderId
            ?.let(trackingProgressProviders::provider)
            ?.shouldUseAsNextUpSeed(progress, nowEpochMs)
            ?: progress.isCompleted()

    override suspend fun normalizeParentContentId(parentContentId: String, videoId: String?): String =
        activeProgressProvider()?.normalizeParentContentId(parentContentId, videoId) ?: parentContentId

    override suspend fun normalizeParentContentId(
        parentContentId: String,
        videoId: String?,
        profileId: Int
    ): String {
        if (profileManager.activeProfileId.value != profileId) return parentContentId
        return activeProgressProvider()?.normalizeParentContentId(parentContentId, videoId)
            ?: parentContentId
    }

    private suspend fun hydrateProgressArtwork(items: List<WatchProgress>, profileId: Int) {
        items.take(10).forEach { progress ->
            if (profileManager.activeProfileId.value != profileId) return
            val key = ProfileContentKey(profileId, progress.contentId)
            if (!canAttemptMetadata(key)) return@forEach
            val shouldFetch = metadataMutex.withLock {
                if (profileManager.activeProfileId.value != profileId) return@withLock false
                if (inFlightMetadataKeys.contains(key)) return@withLock false
                if (synchronized(hydratedProgressKeys) { key in hydratedProgressKeys }) return@withLock false
                if (!canAttemptMetadata(key)) return@withLock false
                inFlightMetadataKeys.add(key)
                recordMetadataAttempt(key)
                true
            }
            if (!shouldFetch) return@forEach
            try {
                val metadata = fetchContentMetadata(
                    contentId = progress.contentId,
                    contentType = progress.contentType
                ) ?: return@forEach
                if (profileManager.activeProfileId.value != profileId) return@forEach
                val episodeRuntimeMs = if (progress.season != null && progress.episode != null)
                    metadata.episodes[progress.season to progress.episode]?.runtimeMs ?: 0L
                else 0L
                val durationMs = progress.duration.takeIf { it > 0 }
                    ?: episodeRuntimeMs.takeIf { it > 0 }
                    ?: metadata.runtimeMs

                // If addon returned no backdrop or poster, fall back to TMDB
                var backdropToSave = progress.backdrop ?: metadata.backdrop
                var posterToSave = progress.poster ?: metadata.poster
                if (backdropToSave == null && posterToSave == null) {
                    val tmdbImages = tmdbService.fetchImdbImages(progress.contentId, progress.contentType)
                    if (profileManager.activeProfileId.value != profileId) return@forEach
                    backdropToSave = tmdbImages?.backdropUrl
                    posterToSave = tmdbImages?.posterUrl
                }

                val hasNewData = posterToSave != null || backdropToSave != null
                    || metadata.logo != null || durationMs > 0
                if (hasNewData) {
                    watchProgressPreferences.saveProgress(
                        progress.copy(
                            poster = posterToSave,
                            backdrop = backdropToSave,
                            logo = progress.logo ?: metadata.logo,
                            name = progress.name.takeIf { it.isNotBlank() && it != progress.contentId }
                                ?: metadata.name ?: progress.name,
                            duration = if (durationMs > 0) durationMs else progress.duration
                        ),
                        profileId = profileId
                    )
                }
                synchronized(hydratedProgressKeys) {
                    hydratedProgressKeys.add(key)
                }
                synchronized(metadataAttempts) {
                    metadataAttempts.remove(key)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Progress artwork hydration failed for ${progress.contentId}", e)
            } finally {
                metadataMutex.withLock {
                    inFlightMetadataKeys.remove(key)
                }
            }
        }
    }

    private fun parseRuntimeToMs(raw: String?): Long {
        val minutes = raw?.trim()?.toLongOrNull() ?: return 0L
        return minutes * 60_000L
    }

    private fun progressKey(progress: WatchProgress): String {
        return progress.storageKey
    }

    private suspend fun resolveRemoteDeleteKeys(
        contentId: String,
        season: Int?,
        episode: Int?,
        profileId: Int = profileManager.activeProfileId.value
    ): List<String> {
        val rawEntries = watchProgressPreferences.getAllRawEntries(profileId)
        val keys = if (season != null && episode != null) {
            listOf("${contentId}_s${season}e${episode}", contentId)
        } else {
            val matchingLocalKeys = rawEntries
                .keys
                .filter { key ->
                    key == contentId || key.startsWith("${contentId}_")
                }
            matchingLocalKeys + contentId
        }
        val resolvedKeys = keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return resolvedKeys
    }

}

internal fun WatchProgress.matchesPartialEpisode(parent: String, video: Video): Boolean {
    if (contentId != parent || !hasRemovableEpisodePause()) return false
    val target = video.tvdbPlaybackContext()
    if (target == null) return tvdbContext == null && season == video.season && episode == video.episode &&
        videoId == video.id && (episodeTitle.isNullOrBlank() || episodeTitle.equals(video.title, ignoreCase = true))
    if (tvdbContentIds.isNotEmpty()) return tvdbContentIds == target.episodeIds.toSet()
    return target.providerSources.orEmpty().any { source ->
        source.episodeIds.toSet() == target.episodeIds.toSet() && source.videoId == videoId &&
            source.coordinates.season == season && source.coordinates.episode == episode
    }
}
