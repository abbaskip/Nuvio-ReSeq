package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchStateMutationStore
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerWatchMark
import com.nuvio.tv.data.mediaserver.ServerWatched
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.storageKey
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.mergeProgressProjectionWithRetainedLocal
import com.nuvio.tv.core.tracking.mergeWatchedEpisodeProjection
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.core.tracking.effectiveWatchProgressSource
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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

    @OptIn(FlowPreview::class)
    private val progressProviderConnections = combine(
        trackingProgressProviders.providers().map { provider ->
            provider.isAuthenticated.map { authenticated -> provider.providerId to authenticated }
        }
    ) { states -> states.toMap() }
    @Volatile private var activeProgressProviderId: TrackingProviderId? = null

    @OptIn(FlowPreview::class)
    private val activeProgressProviderState: StateFlow<TrackingProgressProvider?> = combine(
        traktSettingsDataStore.watchProgressSource,
        progressProviderConnections
    ) { requested, connections ->
        effectiveWatchProgressSource(requested) { providerId -> connections[providerId] == true }
            .providerId
            ?.let(trackingProgressProviders::provider)
    }.debounce { provider ->
        if (provider == null) 100L else 0L
    }.distinctUntilChanged()
        .stateIn(syncScope, SharingStarted.Eagerly, null)

    init {
        syncScope.launch {
            activeProgressProviderState.collect { provider ->
                activeProgressProviderId = provider?.providerId
            }
        }
    }

    private fun activeProgressProviderFlow(): Flow<TrackingProgressProvider?> = activeProgressProviderState

    private fun profileProgressProviderFlow(profileId: Int): Flow<TrackingProgressProvider?> =
        profileManager.activeProfileId.flatMapLatest { activeProfileId ->
            if (activeProfileId == profileId) activeProgressProviderFlow() else flowOf(null)
        }

    private suspend fun activeProgressProvider(): TrackingProgressProvider? =
        activeProgressProviderFlow().first()

    private suspend fun connectedProgressProviders(): List<TrackingProgressProvider> =
        trackingProgressProviders.providers().filter { provider -> provider.isAuthenticated.first() }

    override fun observeRemoteProgressLoaded(): Flow<Boolean> {
        return activeProgressProviderFlow().flatMapLatest { provider ->
            provider?.remoteProgressLoaded ?: flowOf(true)
        }.distinctUntilChanged()
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
            activeProgressProviderFlow().flatMapLatest { provider ->
                if (provider != null) {
                    combine(
                        provider.allProgress,
                        watchProgressPreferences.observeAllProgress(profileId),
                        metadataState,
                        tvdbTracking?.observeSelectedSeries(profileId) ?: flowOf(emptySet<Long>())
                    ) { items, localItems, metadata, selectedSeries ->
                        (mergeProgressProjectionWithRetainedLocal(
                            providerEntries = items,
                            localEntries = localItems.filter { it.tvdbContext == null },
                            retainsLocalProgress = provider::retainsLocalProgress
                        ) + localItems.filter { it.tvdbContext?.seriesId in selectedSeries && provider.retainsTvdbLocalProgress(it) })
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
            }
        }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { list -> list.filter { it.isInProgress() } }

    override val watchedItems: Flow<List<WatchedItem>>
        get() = activeProgressProviderFlow().flatMapLatest { provider ->
            provider?.watchedItems ?: watchedItemsPreferences.allItems
        }.distinctUntilChanged()

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            getProgress(contentId, profileId)
        }
    }

    override fun getProgress(contentId: String, profileId: Int): Flow<WatchProgress?> {
        return profileProgressProviderFlow(profileId)
            .flatMapLatest { provider ->
                if (provider != null) {
                    provider.allProgress.map { items ->
                        items
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
        val tvdb = tvdbTracking ?: return existing
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
                val cloud = if (authManager.isAuthenticated && !context.providerSources.isNullOrEmpty()) {
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
            val remote = if (provider?.providerId == TrackingProviderId.TRAKT)
                tvdb.canonicalTraktRecords(profileId, contentId, context) ?: provider.episodeProgress(contentId).map { it.values.toList() }
                else if (provider != null) combine(provider.episodeProgress(contentId), provider.allProgress) { episodes, all ->
                    (episodes.values + all).distinctBy { Triple(it.contentId, it.season, it.episode) }
                } else flowOf(emptyList())
            combine(remote, watchProgressPreferences.observeEpisodeRecords(contentId, profileId)) { entries, records ->
                tvdb.projectProgress(profileId, contentId, emptyMap(),
                    records.filter { provider.retainsTvdbLocalProgress(it) } + entries, context)
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
                        provider.episodeProgress(contentId).onStart { emit(emptyMap()) },
                        provider.allProgress.map { items ->
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
                            merged[seasonNum to episodeNum] = episodeProgress
                        }
                        merged
                    }.distinctUntilChanged()
                } else {
                    watchProgressPreferences.getAllEpisodeProgress(contentId, profileId)
                }
            }
        val tvdb = tvdbTracking ?: return existing
        return tvdb.observeSelection(profileId, contentId).flatMapLatest { selected ->
            if (selected == null) existing else selectedTvdbProgress(contentId, profileId)
        }
    }

    override fun getAiredEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                provider?.airedEpisodeOrder(contentId) ?: flowOf(emptyList())
            }
            .distinctUntilChanged()
    }

    override fun observeNextUpSeeds(): Flow<List<WatchProgress>> {
        val existing = activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    provider.nextUpSeeds
                } else {
                    // Use watched items (fully synced with pagination) to build seeds
                    // instead of watch progress (limited to 1000 entries).
                    combine(
                        watchedItemsPreferences.allItems,
                        layoutPreferenceDataStore.nextUpFromFurthestEpisode
                    ) { items, useFurthest ->
                        items
                            .filter { item ->
                                (item.contentType.equals("series", ignoreCase = true) ||
                                    item.contentType.equals("tv", ignoreCase = true)) &&
                                    item.season != null &&
                                    item.episode != null &&
                                    item.season != 0 &&
                                    !isMalformedNextUpSeedContentId(item.contentId)
                            }
                            .groupBy { it.contentId }
                            .mapNotNull { (_, episodes) ->
                                val latest = episodes.maxWithOrNull(
                                    if (useFurthest) {
                                        compareBy<WatchedItem> { it.season ?: 0 }
                                            .thenBy { it.episode ?: 0 }
                                            .thenBy { it.watchedAt }
                                    } else {
                                        compareBy<WatchedItem> { it.watchedAt }
                                            .thenBy { it.season ?: 0 }
                                            .thenBy { it.episode ?: 0 }
                                    }
                                ) ?: return@mapNotNull null
                                WatchProgress(
                                    contentId = latest.contentId,
                                    contentType = latest.contentType,
                                    name = latest.title,
                                    poster = null,
                                    backdrop = null,
                                    logo = null,
                                    videoId = latest.contentId,
                                    season = latest.season,
                                    episode = latest.episode,
                                    episodeTitle = null,
                                    position = 1L,
                                    duration = 1L,
                                    lastWatched = latest.watchedAt,
                                    progressPercent = 100f,
                                    tvdbEpisodeId = latest.tvdbEpisodeId,
                                    tvdbEpisodeIds = latest.tvdbEpisodeIds
                                )
                            }
                    }.flowOn(Dispatchers.Default)
                }
            }
            .distinctUntilChanged()
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            combine(existing, watchProgressPreferences.observeAllProgress(profileId),
                tvdbTracking?.observeSelectedSeries(profileId) ?: flowOf(emptySet<Long>()),
                activeProgressProviderFlow()) { seeds, records, selected, provider ->
                val parts = records.filter { it.tvdbContext?.seriesId in selected && it.isCompleted() && provider.retainsTvdbLocalProgress(it) }
                if (parts.isEmpty()) seeds else (seeds + parts).groupBy { it.contentId }.values.map { it.maxBy(WatchProgress::lastWatched) }
            }
        }.distinctUntilChanged()
    }

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
            val localItems = watchedItemsPreferences.getAllItems(profileId)
            if (profileManager.activeProfileId.value != profileId) return emptyMap()
            return mergeWatchedEpisodeProjection(
                providerEpisodes = providerEpisodes,
                localItems = localItems.filter { item ->
                    (item.tvdbEpisodeId == null && item.tvdbEpisodeIds.isNullOrEmpty()) ||
                        provider.retainsLocalProgress(item.contentId)
                },
                retainsLocalWatchedEpisode = provider::retainsLocalWatchedEpisode
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

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
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

    override suspend fun markAsCompleted(progress: WatchProgress, broadcastTrackingHistory: Boolean) {
        val profileId = profileManager.activeProfileId.value
        markAsCompleted(progress, profileId, broadcastTrackingHistory)
    }

    override suspend fun markAsCompleted(
        progress: WatchProgress,
        profileId: Int,
        broadcastTrackingHistory: Boolean
    ) {
        if (progress.tvdbCoverageVerified == false) {
            saveProgress(progress, profileId)
            return
        }
        if (progress.tvdbEpisodeIds == null && tvdbTracking != null) {
            val identified = tvdbTracking.identifyProgress(profileId, progress)
            if (!identified.tvdbEpisodeIds.isNullOrEmpty()) { markAsCompleted(identified, profileId, broadcastTrackingHistory); return }
        }
        if (progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(progress.contentId, profileId)
        }
        val now = System.currentTimeMillis()
        val progressKey = progressKey(progress)
        val duration = progress.duration.takeIf { it > 1L }
            ?: watchProgressPreferences.getAllRawEntries(profileId)[progressKey]
                ?.duration
                ?.takeIf { it > 1L }
            ?: 1L
        val completed = progress.copy(
            position = duration,
            duration = duration,
            progressPercent = 100f,
            lastWatched = now,
            tvdbCompletedEpisodeIds = progress.tvdbEpisodeIds
        )
        mutationStore.queueProgressUpserts(mapOf(progressKey to completed), profileId)
        if (profileManager.activeProfileId.value == profileId) {
            optimisticContinueWatchingUpdates.tryEmit(completed)
            if (completed.tvdbEpisodeIds == null) activeProgressProvider()?.applyOptimisticProgress(completed, quiet = false)
        }
        watchProgressPreferences.saveProgress(completed, profileId = profileId)
        if (completed.tvdbContext != null) {
            recordTvdbNativeWatched(profileId, listOf(completed), syncRemote = true)
            broadcastHistoryAdd(profileId, listOf(completed.toTrackingHistoryItem(now)), coverageChangesOnly = !broadcastTrackingHistory)
            triggerRemoteSync(profileId)
            return
        }
        val watchedItem = completed.toWatchedItem(watchedAt = now)
        watchedItemsPreferences.markAsWatched(watchedItem, profileId = profileId)
        mutationStore.queueWatchedUpserts(listOf(watchedItem), profileId)
        if (broadcastTrackingHistory || completed.tvdbEpisodeIds?.isNotEmpty() == true) {
            broadcastHistoryAdd(profileId, listOf(completed.toTrackingHistoryItem(now)), coverageChangesOnly = !broadcastTrackingHistory)
        }
        if (broadcastTrackingHistory) {
            syncServerWatched(listOf(completed.toServerWatchMark()), played = true, profileId = profileId)
        }
        triggerRemoteSync(profileId = profileId)
        triggerWatchedItemsSync(listOf(watchedItem), profileId = profileId)
    }

    override suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        val progressList = if (tvdbTracking == null) progressList else progressList.map { tvdbTracking.identifyProgress(profileId, it) }
        val firstProgress = progressList.first()
        if (firstProgress.contentType.equals("series", ignoreCase = true) ||
            firstProgress.contentType.equals("tv", ignoreCase = true)) {
            traktSettingsDataStore.removeDismissedNextUpKeysForContent(firstProgress.contentId, profileId)
        }
        val now = System.currentTimeMillis()
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
                lastWatched = now,
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
        val watchedItems = completedList.filter { it.tvdbContext == null }.map { progress -> progress.toWatchedItem(watchedAt = now) }
        recordTvdbNativeWatched(profileId, completedList.filter { it.tvdbContext != null && it.isCompleted() }, syncRemote = true)
        watchedItemsPreferences.markAsWatchedBatch(watchedItems, profileId = profileId)
        mutationStore.queueWatchedUpserts(watchedItems, profileId)
        broadcastHistoryAdd(profileId, completedList.map { it.toTrackingHistoryItem(now) })
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
            broadcastHistoryRemoval(profileId, listOf(media))
            val native = tvdb.nativeEpisodes(profileId, contentId, context)
            native.forEach { coordinates -> watchedItemsPreferences.unmarkAsWatched(contentId, coordinates.season, coordinates.episode, profileId) }
            mutationStore.queueWatchedDeletes(native.map { WatchedMutationKey(contentId, it.season, it.episode) }, profileId)
            if (authManager.isAuthenticated && native.isNotEmpty())
                watchedItemsSyncService.deleteFromRemoteBatch(contentId, native.map { it.season to it.episode }, profileId)
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

    private fun WatchProgress.toTrackingHistoryItem(watchedAt: Long): TrackingHistoryItem =
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
            watchedAtEpochMs = watchedAt
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
    ) {
        val items = items.filterNot { ServerItemRef.isServerId(it.catalog?.contentId) }
        if (items.isEmpty()) return
        val connectedIds = connectedProgressProviders().mapTo(mutableSetOf()) { it.providerId }
        supervisorScope {
            trackingHistoryWriters.writers()
                .filter { writer -> writer.providerId in connectedIds }
                .map { writer ->
                    async {
                        runCatching {
                            val resolved = if (tvdbTracking == null) items else items.flatMap {
                                tvdbTracking.resolveAll(profileId, writer.providerId, it).orEmpty()
                            }
                            writer.removeFromHistory(profileId, resolved)
                        }
                            .onFailure { error ->
                                Log.w(TAG, "Failed ${writer.providerId.storageId} history removal", error)
                            }
                    }
                }
                .forEach { operation -> operation.await() }
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
    ): Long? = activeProgressProviderId
        ?.let(trackingProgressProviders::provider)
        ?.continueWatchingCutoffEpochMs(daysCap, nowEpochMs)

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
