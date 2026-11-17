package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.movieCompletionIndex
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.repository.EpisodeMappingEntry
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

internal fun mdbListMovieCompletionTimes(records: List<MdbListWatchedRecord>) =
    movieCompletionIndex(records.asSequence().filter { it.type == MdbListItemType.MOVIE },
        { it.media.ids.aliases() }, { runCatching { mdbListTimestamp(it.watchedAt) }.getOrNull() })

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MdbListTrackingProgressProvider @Inject constructor(
    private val sync: MdbListSyncRepository,
    private val scrobbles: MdbListScrobbleService,
    private val episodeMappings: MdbListEpisodeMappingService,
    private val auth: MdbListAuthStore,
    profiles: ProfileManager,
    layout: LayoutPreferenceDataStore,
    private val history: MdbListHistoryService? = null
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.MDBLIST
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.distinctUntilChanged()
    private val projection = combine(sync.progressState, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        if (authorization.isAuthenticated && authorization.scope.profileId == profileId &&
            state.scope == authorization.scope) state.projection else MdbListProgressProjection.Empty
    }.distinctUntilChanged()
    override val allProgress = projection.map { it.progress }.inAddonNumbering().loadOnStart()
    override val watchedItems = projection.map { it.watchedItems }.loadOnStart()
    override fun movieCompletionTimes(profileId: Int) = combine(sync.state, auth.state) { state, authorization ->
        if (authorization.isAuthenticated && authorization.scope.profileId == profileId &&
            state.scope == authorization.scope) state.snapshot?.watched.orEmpty() else emptyList()
    }.distinctUntilChanged().map { records -> mdbListMovieCompletionTimes(records) }
    override val watchedMovieIds = projection.map { it.watchedMovieIds }.loadOnStart()
    override val nextUpSeeds = combine(projection, layout.nextUpFromFurthestEpisode) { projection, furthest ->
        projection.nextUp(furthest)
    }.loadOnStart()
    override val remoteProgressLoaded = combine(sync.state, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        state.hasLoaded && !state.isLoading && state.error == null && state.scope == authorization.scope &&
            authorization.scope.profileId == profileId && authorization.isAuthenticated
    }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        projection.transformLatest { projection ->
            val known = episodeMappings.cached(contentId)
            if (known != null) {
                emit(projection.episodeProgressInAddonNumbering(contentId, known))
                return@transformLatest
            }
            val mapping = if (projection.hasEpisodes(contentId)) episodeMapping(contentId, projection) else null
            emit(if (mapping == null) projection.episodeHistoryAndProgress(contentId)
                else projection.episodeProgressInAddonNumbering(contentId, mapping))
        }.loadOnStart()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        projection.map { projection ->
            val tmdb = if (season != null && episode != null && projection.hasEpisodes(contentId)) {
                episodeMapping(contentId, projection)?.toTmdb(season, episode, videoId)
            } else null
            projection.isWatched(contentId, tmdb?.season ?: season, tmdb?.episode ?: episode)
        }.loadOnStart()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        return currentProjection().watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        sync.ensureLoaded()
        return currentProjection().showIdSiblings
    }

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val parts = videoId.split(':')
        if (parts.size < 3 || parts.last().toIntOrNull() != episode) return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val parent = parts.dropLast(2).joinToString(":")
        if (retainsLocalProgress(parent)) return null
        val mapping = episodeMappings.cached(parent)?.takeIf { it.remaps }
            ?: return currentProjection().isWatched(parent, season, episode)
        val tmdb = mapping.toTmdb(season, episode, videoId) ?: return null
        return currentProjection().isWatched(parent, tmdb.season, tmdb.episode)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)
    override suspend fun watchBaseline(target: TrackingMediaReference): com.nuvio.tv.core.tracking.TrackingWatchBaseline {
        sync.refresh(TrackingRefreshIntent.INVALIDATED)
        check(sync.state.value.hasLoaded && sync.state.value.error == null)
        val episode = requireNotNull(target.episode)
        val mapping = if (target.catalog?.providerDefault == true) null else episodeMapping(requireNotNull(target.catalog).contentId, currentProjection())
        val coordinates = mapping?.takeIf { it.remaps }?.toTmdb(requireNotNull(episode.season), episode.number)
        val native = if (coordinates == null) target else target.copy(episode = episode.copy(season = coordinates.season, number = coordinates.episode))
        val snapshot = requireNotNull(sync.currentSnapshot())
        val match = snapshot.mutationTarget(native)
        val prior = match?.let { snapshot.watched.singleOrNull(it::matches) }
        return com.nuvio.tv.core.tracking.TrackingWatchBaseline(target, true, prior?.let { mdbListTimestamp(it.watchedAt) })
    }
    override suspend fun removeWatchEvent(target: TrackingMediaReference, event: com.nuvio.tv.core.tracking.TrackingWatchEvent): Boolean {
        val scope = sync.currentScope()
        check(event.profileId == null || scope.profileId == event.profileId)
        val mapping = if (target.catalog?.providerDefault == true) null else episodeMapping(requireNotNull(target.catalog).contentId, currentProjection())
        val episode = requireNotNull(target.episode)
        val mapped = mapping?.takeIf { it.remaps }?.toTmdb(requireNotNull(episode.season), episode.number)
        val native = if (mapped == null) target else target.copy(episode = episode.copy(season = mapped.season, number = mapped.episode))
        val pause = scrobbles.clearDatedEpisodePause(scope, native, event)
        val watch = event.completionExpected && requireNotNull(history).undo(scope, native, event)
        return if (event.completionExpected) watch else pause
    }

    override suspend fun removeEpisodePause(target: TrackingMediaReference, resume: WatchProgress) {
        val episode = requireNotNull(target.episode)
        val parent = requireNotNull(target.catalog)
        val scope = sync.currentScope()
        val mapping = if (parent.providerDefault) null else episodeMapping(parent.contentId, currentProjection())
        val mapped = mapping?.takeIf { it.remaps }?.let {
            requireNotNull(it.toTmdb(requireNotNull(episode.season), episode.number))
        }
        scrobbles.clearEpisodePause(scope, parent.contentId, mapped?.season ?: requireNotNull(episode.season),
            mapped?.episode ?: episode.number, episode)
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val scope = sync.currentScope()
        val tmdb = if (season != null && episode != null) {
            episodeMapping(contentId, currentProjection())?.toTmdb(season, episode)
        } else null
        if (sync.currentScope() != scope) return
        scrobbles.clear(scope, contentId, tmdb?.season ?: season, tmdb?.episode ?: episode)
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit
    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
    override fun clearOptimistic() = Unit
    override fun isHiddenFromProgress(contentId: String): Boolean = currentProjection().isHidden(contentId)

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress {
        val season = progress.season ?: return progress
        val episode = progress.episode ?: return progress
        val addon = episodeMapping(progress.contentId, currentProjection())
            ?.toAddon(season, episode, progress.episodeTitle) ?: return progress
        return progress.withAddonEpisode(addon)
    }

    override fun retainsLocalProgress(contentId: String): Boolean = parseTrackingExternalIds(contentId).toMdbListIds() == null
    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = retainsLocalProgress(item.contentId)

    private fun currentProjection(): MdbListProgressProjection =
        sync.currentProjection()

    private suspend fun episodeMapping(
        contentId: String,
        projection: MdbListProgressProjection
    ): MdbListEpisodeMapping? {
        val ids = parseTrackingExternalIds(contentId).toMdbListIds() ?: return null
        val tmdbShowId = projection.mediaIndex.resolve(MdbListItemType.SHOW, ids).ids.tmdb
        return episodeMappings.resolve(contentId, tmdbShowId)
    }

    /**
     * Episode progress of [contentId] in the addon's numbering. When the show is split into seasons
     * differently, watched episodes are included too: the detail screen cannot look them up by the
     * addon's video ids until they are remapped.
     */
    private fun MdbListProgressProjection.episodeProgressInAddonNumbering(
        contentId: String,
        mapping: MdbListEpisodeMapping
    ): Map<Pair<Int, Int>, WatchProgress> {
        if (!mapping.remaps) return episodeHistoryAndProgress(contentId)
        return (watchedEpisodeProgress(contentId) + episodeProgress(contentId).values)
            .mapNotNull { progress ->
                val addon = mapping.toAddon(progress.season!!, progress.episode!!, progress.episodeTitle)
                    ?: return@mapNotNull null
                (addon.season to addon.episode) to progress.withAddonEpisode(addon)
            }
            .toMap()
    }

    // Other trackers expose completed episode rows here too. Keep them available
    // after a pause clears, and let a live pause win its own coordinate in this view.
    private fun MdbListProgressProjection.episodeHistoryAndProgress(contentId: String) =
        (watchedEpisodeProgress(contentId) + episodeProgress(contentId).values)
            .associateBy { requireNotNull(it.season) to requireNotNull(it.episode) }

    /**
     * Moves in-progress episodes to the addon's numbering. Shows with a known mapping are remapped
     * at once; the others wait for their mapping before a single emission.
     */
    private fun Flow<List<WatchProgress>>.inAddonNumbering(): Flow<List<WatchProgress>> =
        transformLatest { items ->
            val pending = items.asSequence()
                .filter { it.season != null && it.episode != null && episodeMappings.cached(it.contentId) == null }
                .map(WatchProgress::contentId)
                .distinct()
                .toList()
            if (pending.isNotEmpty()) {
                val projection = currentProjection()
                coroutineScope {
                    pending.map { contentId -> async { episodeMapping(contentId, projection) } }.awaitAll()
                }
            }
            emit(items.map(::withCachedAddonEpisode))
        }

    private fun withCachedAddonEpisode(progress: WatchProgress): WatchProgress {
        val season = progress.season ?: return progress
        val episode = progress.episode ?: return progress
        val addon = episodeMappings.cached(progress.contentId)
            ?.toAddon(season, episode, progress.episodeTitle) ?: return progress
        return progress.withAddonEpisode(addon)
    }

    private fun WatchProgress.withAddonEpisode(addon: EpisodeMappingEntry): WatchProgress = copy(
        season = addon.season,
        episode = addon.episode,
        videoId = addon.videoId ?: "$contentId:${addon.season}:${addon.episode}"
    )

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()
}
