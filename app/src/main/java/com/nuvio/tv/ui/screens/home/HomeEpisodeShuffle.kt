package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.domain.model.hasResolvedSource
import com.nuvio.tv.domain.model.tvdbPlaybackContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.domain.model.ShuffleSurface
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class HomeShuffleRefresh(val visit: Long = System.nanoTime(), val metadata: Int = 0)

/** Decoration belongs to the exact durable card list it was calculated from. */
internal data class HomeCwDecoration(
    val input: Pair<List<ContinueWatchingItem>, List<ContinueWatchingItem>>,
    val cards: HomeUiState
)

internal fun mergeHomeCwDecoration(state: HomeUiState, decoration: HomeCwDecoration?): HomeUiState =
    if (decoration == null || decoration.input != (state.continueWatchingItems to state.upcomingItems)) state
    else state.copy(continueWatchingItems = decoration.cards.continueWatchingItems,
        upcomingItems = decoration.cards.upcomingItems)

private data class HomeShuffleInputs(
    val profile: EpisodeShuffleProfile,
    val history: Pair<Map<String, Set<Pair<Int, Int>>>, List<WatchProgress>>,
    val refresh: HomeShuffleRefresh,
    val finished: Pair<Int, List<WatchProgress>>
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun HomeViewModel.createShuffleHomeState(
    projectionDispatcher: CoroutineDispatcher = Dispatchers.Default
): StateFlow<HomeUiState> {
    val inputs = homeContentActive.flatMapLatest { active ->
        flow<HomeShuffleInputs?> {
            if (active) coroutineScope {
                val finished = finishedShuffleSeeds().stateIn(this, SharingStarted.Eagerly, 0 to emptyList())
                requestHomeShuffleMetadata(finished, this)
                emitAll(observeShuffleInputs(finished, projectionDispatcher))
            } else emit(null)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Publish each CW update with its shuffle choice already applied. Focus and other
    // unrelated Home updates reuse the same result without running the picker again.
    var previousInputs: HomeShuffleInputs? = null
    var decoration: HomeCwDecoration? = null
    return combine(_uiState, inputs) { state, current ->
        val items = state.continueWatchingItems to state.upcomingItems
        if (current == null) {
            decoration = null
        } else if (current !== previousInputs || decoration?.input != items) {
            decoration = withContext(projectionDispatcher) {
                val rows = HomeUiState(continueWatchingItems = items.first, upcomingItems = items.second)
                HomeCwDecoration(items, projectShuffleHomeState(rows, current))
            }
        }
        previousInputs = current
        val latest = _uiState.value
        if (inputs.value !== current || items != (latest.continueWatchingItems to latest.upcomingItems)) null
        else mergeHomeCwDecoration(latest, decoration)
    }.filterNotNull().stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value)
}

private fun HomeViewModel.observeShuffleInputs(
    finished: StateFlow<Pair<Int, List<WatchProgress>>>,
    projectionDispatcher: CoroutineDispatcher
): Flow<HomeShuffleInputs> = combine(
    episodeShuffleStore.profiles,
    combine(watchProgressRepository.watchedItems, watchProgressRepository.allProgress) { watched, progress ->
        val keys = watched.mapNotNull { item ->
            item.season?.let { season -> item.episode?.let { item.contentId to (season to it) } }
        } + progress.filter { it.isCompleted() }.mapNotNull { item ->
            item.season?.let { season -> item.episode?.let { item.contentId to (season to it) } }
        }
        keys.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() } to progress
    }.distinctUntilChanged().flowOn(projectionDispatcher),
    shuffleHomeRefresh,
    finished,
    ::HomeShuffleInputs
)

private fun HomeViewModel.projectShuffleHomeState(state: HomeUiState, inputs: HomeShuffleInputs): HomeUiState {
    val (profile, history, refresh, seedSnapshot) = inputs
    val seeds = seedSnapshot.second.takeIf { seedSnapshot.first == profile.profileId }.orEmpty()
    val watched = history.first.toMutableMap()
    (state.continueWatchingItems + state.upcomingItems).forEach { item ->
        val id = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentId; is ContinueWatchingItem.NextUp -> item.info.contentId }
        val type = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentType; is ContinueWatchingItem.NextUp -> item.info.contentType }
        cachedCwMeta(type, id)?.takeIf { it.tvdbOrder }?.let { meta ->
            val videos = meta.videos.map { it.asTvdbVideo() }
            watched[id] = history.second.filter { it.contentId == id && it.isCompleted() }.mapNotNull { progress ->
                com.nuvio.tv.data.tvdb.resolveTvdbProgressVideo(videos, progress)?.let { video -> video.season!! to video.episode!! }
            }.toSet()
        }
    }
    val cards = seeds.mapNotNull { seed ->
        val meta = cachedCwMeta(seed.contentType, seed.contentId)
        val shuffleEnabled = profile.settings(seed.contentId, seed.contentType).enabled
        // Sequential membership and placeholders belong to the settled repository.
        // A cold catalogue cannot synthesize a card outside that publication boundary.
        if (!shuffleEnabled) return@mapNotNull null
        if (meta == null) traceCwCard(seed.contentId) { "tracker seed without cached metadata" }
        seed.toShuffleCard(meta)
    }
    return applyHomeShuffle(state, profile, watched, episodeShuffle, refresh.visit, continueWatchingSortMode, cards) { id, type ->
        val cached = cachedCwMeta(type, id)
        if (cached == null) traceCwCard(id) {
            if (cwMetaNegativeCacheTimestamps.containsKey("$type:$id")) "negative metadata entry" else "cold metadata cache"
        }
        cached?.takeIf { hasUsableCwCatalogue(it, profile.profileId, id) }?.videos?.map { video ->
            Video(video.id, video.title.orEmpty(), video.released, video.thumbnail,
                season = video.season, episode = video.episode, overview = video.overview, available = video.available,
                episodeIdentity = video.episodeIdentity)
        }
    }.let { withTvdbLabels(it, profile) }
}

private const val CW_TRACE_TAG = "NuvioCwTrace"

internal inline fun traceCwCard(id: String, reason: () -> String) {
    if (Log.isLoggable(CW_TRACE_TAG, Log.DEBUG))
        Log.d(CW_TRACE_TAG, "at=${System.currentTimeMillis()} show=$id ${reason()}")
}

private fun HomeViewModel.projectTvdbItem(item: ContinueWatchingItem, shuffleEnabled: Boolean): ContinueWatchingItem {
    // Sequential cards are already settled by the repository's background resolver.
    // A cold/partial catalogue must never reinterpret the saved card on screen.
    if (!item.shufflePlayback && !shuffleEnabled) return when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.tvdbContext?.let {
            item.copy(displaySeason = it.season, displayEpisode = it.episode)
        } ?: item
        is ContinueWatchingItem.NextUp -> item.info.tvdbContext?.let {
            item.copy(info = item.info.copy(displaySeason = it.season, displayEpisode = it.episode))
        } ?: item
    }
    // A retained resume may be awaiting identity verification in the durable projection.
    // Let the selected catalogue resolve it; treating that flag as terminal strands it
    // after unwatch exposes an older provider/default-order resume.
    if (item.chooseEpisode && item is ContinueWatchingItem.NextUp) return item
    val id = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentId; is ContinueWatchingItem.NextUp -> item.info.contentId }
    val type = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentType; is ContinueWatchingItem.NextUp -> item.info.contentType }
    val meta = cachedCwMeta(type, id)
    if (meta?.tvdbOrder != true) {
        val context = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.tvdbContext
            is ContinueWatchingItem.NextUp -> item.info.tvdbContext
        }
        val order = tvdbOrderStore?.cachedSelection(profileManager.activeProfileId.value, id)
        // Missing metadata does not invalidate a verified episode in the
        // unchanged order. Sequential cards also survive a temporary TVDB fallback;
        // a new shuffle pick still requires a resolved catalogue.
        // Playback still verifies the target against fresh metadata.
        if ((meta == null || (meta.tvdbPending && !item.shufflePlayback)) && context != null &&
            order?.seriesId == context.seriesId && order.orderType == context.orderType) {
            return when (item) {
                is ContinueWatchingItem.InProgress -> item.copy(displaySeason = context.season, displayEpisode = context.episode)
                is ContinueWatchingItem.NextUp -> item.copy(info = item.info.copy(
                    displaySeason = context.season, displayEpisode = context.episode))
            }
        }
        if (meta?.tvdbPending == true || order != null || context != null) {
            return item.forSeriesChoice()
        }
        return item
    }
    val videos = meta.videos.map { it.asTvdbVideo() }
    return projectTvdbContinueWatching(item, videos) ?: run {
        traceCwCard(id) {
            val expected = when (item) {
                is ContinueWatchingItem.NextUp -> item.info.tvdbEpisodeId
                is ContinueWatchingItem.InProgress -> item.progress.tvdbEpisodeId
            }
            val source = when (item) {
                is ContinueWatchingItem.NextUp -> item.info.videoId
                is ContinueWatchingItem.InProgress -> item.progress.videoId
            }
            val candidates = videos.filter { it.id == source || (expected != null && (it.episodeIdentity?.trackingEpisodeId == expected || it.episodeIdentity?.tvdbEpisodeId == expected)) }.take(4)
            "unresolved projection expected=$expected candidates=" + candidates.map {
                "tracking=${it.episodeIdentity?.trackingEpisodeId},display=${it.episodeIdentity?.tvdbEpisodeId},resolved=${it.hasResolvedSource}"
            }
        }
        item.forSeriesChoice()
    }
}

private fun ContinueWatchingItem.forSeriesChoice() = when (this) {
    is ContinueWatchingItem.InProgress -> copy(chooseEpisode = true, shufflePlayback = false)
    is ContinueWatchingItem.NextUp -> copy(chooseEpisode = true, shufflePlayback = false)
}

private fun HomeViewModel.withTvdbLabels(state: HomeUiState, profile: EpisodeShuffleProfile): HomeUiState {
    fun project(item: ContinueWatchingItem): ContinueWatchingItem {
        val type = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentType; is ContinueWatchingItem.NextUp -> item.info.contentType }
        return projectTvdbItem(item, profile.settings(item.contentId(), type).enabled)
    }
    return state.copy(continueWatchingItems = state.continueWatchingItems.map(::project),
        upcomingItems = state.upcomingItems.map(::project))
}

private fun HomeViewModel.finishedShuffleSeeds() = combine(
    combine(continueWatchingRepository.projection, traktSettingsDataStore.watchProgressSource) { state, source ->
        state?.takeIf { it.source == source.name }
    },
    traktSettingsDataStore.continueWatchingDaysCap,
    traktSettingsDataStore.dismissedNextUpKeys,
    episodeShuffleStore.profiles,
    combine(watchProgressRepository.allProgress, watchProgressRepository.watchedItems) { items, watched -> items to watched }
) { projection, daysCap, dismissed, profile, history ->
    // Shuffle changes presentation, never membership: a raw tracker replay must not
    // resurrect a card that the repository removed or replaced with in-progress playback.
    val seeds = ownedShuffleSeeds(projection, profile.profileId)
    val cutoff = watchProgressRepository.activeProviderContinueWatchingCutoffEpochMs(daysCap, System.currentTimeMillis())
    val recent = if (cutoff == null) emptyMap() else com.nuvio.tv.domain.model.latestShowActivity(history.first, seeds, history.second)
    // Populate the profile's lightweight selection snapshot once before checking aliases.
    tvdbOrderStore?.let { if (!it.hasSnapshot(profile.profileId)) it.selected(profile.profileId, emptyList()) }
    profile.profileId to seeds.filter { seed ->
        (profile.settings(seed.contentId, seed.contentType).enabled ||
            tvdbOrderStore?.cachedSelection(profile.profileId, seed.contentId) != null) &&
            (seed.season ?: 0) > 0 && seed.episode != null &&
            (cutoff == null || (recent[seed.contentId] ?: 0L) >= cutoff) &&
            nextUpDismissKey(seed.contentId, seed.season, seed.episode) !in dismissed &&
            !watchProgressRepository.isDroppedShow(seed.contentId) &&
            shouldUseAsCompletedSeed(seed)
    }.sortedByDescending { it.lastWatched }.distinctBy { it.contentId }
}.distinctUntilChanged()

internal fun ownedShuffleSeeds(projection: com.nuvio.tv.data.cw.CwProjection?, profileId: Int): List<WatchProgress> =
    projection?.takeIf { it.profileId == profileId }?.members?.values?.mapNotNull { member ->
        member.progress?.takeIf { member.nextUp != null || (member.inProgress == null && it.isCompleted()) }
    }.orEmpty()

/** Keep a slow lookup alive across unrelated tracker emissions. Dropping a show or switching
 * profiles cancels its job. Failed lookups wake at their cooldown even on a static Home screen. */
// Keep the working set below the 64-entry CW cache. Looking up every finished show can
// evict earlier targets and repeatedly refetch whole episode catalogues on each update.
internal const val MAX_HOME_SHUFFLE_METADATA_TARGETS = 32

private fun HomeViewModel.requestHomeShuffleMetadata(
    seeds: Flow<Pair<Int, List<WatchProgress>>>,
    scope: CoroutineScope
) = scope.launch {
    val slots = Semaphore(2)
    val nativeCards = _uiState.map { it.continueWatchingItems + it.upcomingItems }.distinctUntilChanged()
    val jobs = mutableMapOf<String, Job>()
    val lastAttempt = mutableMapOf<String, Long>()
    combine(nativeCards, seeds, episodeShuffleStore.profiles, shuffleHomeRefresh, PlayerSessionTracker.bulkPlaybackActive) {
        native, finished, profile, _, playing ->
        val targets = linkedMapOf<String, Pair<WatchProgress, Boolean>>()
        fun add(seed: WatchProgress) {
            if (targets.size >= MAX_HOME_SHUFFLE_METADATA_TARGETS) return
            val shuffleEnabled = profile.settings(seed.contentId, seed.contentType).enabled
            if (shuffleEnabled ||
                tvdbOrderStore?.cachedSelection(profile.profileId, seed.contentId) != null) {
                targets["${profile.profileId}:${seed.contentType}:${seed.contentId}"] = seed to shuffleEnabled
            }
        }
        if (!playing && profile.profileId == profileManager.activeProfileId.value) {
            for (card in native) when (card) {
                is ContinueWatchingItem.InProgress -> add(card.progress)
                is ContinueWatchingItem.NextUp -> add(card.info.toProgressSeed())
            }
            if (finished.first == profile.profileId) finished.second.forEach(::add)
        }
        targets
    }.collect { targets ->
        for (key in jobs.keys - targets.keys) jobs.remove(key)?.cancel()
        lastAttempt.keys.retainAll(targets.keys)
        for ((key, target) in targets) {
            val (seed, shuffleEnabled) = target
            fun needsMetadata() = cachedCwMeta(seed.contentType, seed.contentId)?.let {
                !hasUsableCwCatalogue(it, profileManager.activeProfileId.value, seed.contentId)
            } ?: true
            if (!needsMetadata() || jobs[key]?.isActive == true) continue
            jobs[key] = launch {
                val cacheKey = "${seed.contentType}:${seed.contentId}"
                while (needsMetadata()) {
                    val last = listOfNotNull(lastAttempt[key], cwMetaNegativeCacheTimestamps[cacheKey]).maxOrNull()
                    if (last != null) delay((last + CW_META_NEGATIVE_CACHE_TTL_MS - cwMetadataLoads.elapsedRealtime()).coerceAtLeast(0))
                    slots.withPermit {
                        if (needsMetadata()) {
                            lastAttempt[key] = cwMetadataLoads.elapsedRealtime()
                            try {
                                val resolved = resolveCwMeta(seed)
                                if (resolved?.tvdbOrder == true && !shuffleEnabled && !_uiState.value.let { state ->
                                        (state.continueWatchingItems + state.upcomingItems).any { it.contentId() == seed.contentId && !it.chooseEpisode }
                                    }) {
                                    cwPipelineRefreshTrigger.update { it + 1 }
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                traceCwCard(seed.contentId) { "metadata lookup failed; retry after cooldown" }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun HomeViewModel.cachedCwMeta(type: String, id: String) =
    cwMetaCache["$type:$id"] ?: cwMetaCache["series:$id"] ?: cwMetaCache["tv:$id"]

private fun WatchProgress.toShuffleCard(meta: CwMetaSummary?) = ContinueWatchingItem.NextUp(NextUpInfo(
    contentId = contentId, contentType = contentType, name = name.ifBlank { meta?.name ?: contentId },
    poster = poster ?: meta?.poster, backdrop = backdrop ?: meta?.backdropUrl, logo = logo ?: meta?.logo,
    videoId = videoId, season = season ?: 1, episode = episode ?: 1, episodeTitle = null, thumbnail = null,
    lastWatched = lastWatched, sortTimestamp = lastWatched, seedSeason = season, seedEpisode = episode
), chooseEpisode = true)

internal fun applyHomeShuffle(
    state: HomeUiState,
    profile: EpisodeShuffleProfile,
    watched: Map<String, Set<Pair<Int, Int>>>,
    shuffle: EpisodeShuffle,
    visit: Long,
    sortMode: ContinueWatchingSortMode,
    finished: List<ContinueWatchingItem.NextUp> = emptyList(),
    videos: (String, String) -> List<Video>?
): HomeUiState {
    if (finished.isEmpty() && (!profile.available || profile.shows.values.none { it.enabled })) return state
    val native = state.continueWatchingItems + state.upcomingItems
    val items = native + finished.filter { card ->
        (card.chooseEpisode || profile.settings(card.info.contentId, card.info.contentType).enabled) &&
            native.none { it.contentId() == card.info.contentId }
    }
    val resumable = items.filterIsInstance<ContinueWatchingItem.InProgress>()
        .filter { profile.settings(it.progress.contentId, it.progress.contentType).enabled }
        .groupBy { it.progress.contentId }
        .mapValues { (_, episodes) -> episodes.maxBy { it.progress.lastWatched } }
    val projected = items.mapNotNull { item ->
        when (item) {
            is ContinueWatchingItem.InProgress -> {
                val latest = resumable[item.progress.contentId]
                if (latest != null && latest != item) {
                    traceCwCard(item.progress.contentId) { "superseded by newer resume" }
                    null
                } else item.copy(shufflePlayback = latest != null)
            }
            is ContinueWatchingItem.NextUp -> {
                val info = item.info
                if (info.contentId in resumable) {
                    traceCwCard(info.contentId) { "next-up grouped with resume" }
                    return@mapNotNull null
                }
                val settings = profile.settings(info.contentId, info.contentType)
                if (!settings.enabled) item else {
                    val catalogue = videos(info.contentId, info.contentType)
                    val selected = if (catalogue == null) {
                        traceCwCard(info.contentId) { "catalogue refreshing; retain displayed shuffle target" }
                        shuffle.homeSelection(profile.profileId, info.contentId, settings.includeWatched)
                    } else shuffle.select(
                        profile.profileId, info.contentId, catalogue, settings.includeWatched,
                        watched[info.contentId].orEmpty(), surface = ShuffleSurface.HOME, visit = visit
                    )
                    if (selected == null) {
                        traceCwCard(info.contentId) { "no eligible shuffle episode" }
                        return@mapNotNull item
                    }
                    item.copy(shufflePlayback = true, chooseEpisode = false, info = info.copy(
                        videoId = selected.id, season = selected.season!!, episode = selected.episode!!,
                        tvdbEpisodeId = selected.episodeIdentity?.trackingEpisodeId,
                        tvdbContext = selected.tvdbPlaybackContext(),
                        displaySeason = selected.episodeIdentity?.displayed?.season,
                        displayEpisode = selected.episodeIdentity?.displayed?.episode,
                        episodeTitle = selected.title, episodeDescription = selected.overview,
                        thumbnail = selected.thumbnail, released = selected.released,
                        hasAired = true, airDateLabel = null, releaseTimestamp = null,
                        isReleaseAlert = false, isNewSeasonRelease = false
                    ))
                }
            }
        }
    }
    val unique = projected.distinctBy { it.shuffleFocusKey ?: continueWatchingItemKey(it) }
    val (current, upcoming) = splitUpcomingItems(unique, sortMode)
    return state.copy(continueWatchingItems = current, upcomingItems = upcoming)
}
