package com.nuvio.tv.ui.screens.home

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.domain.model.hasResolvedSource
import com.nuvio.tv.domain.model.tvdbPlaybackContext
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class HomeShuffleRefresh(val visit: Long = System.nanoTime(), val metadata: Int = 0)

internal fun HomeViewModel.createShuffleHomeState(): StateFlow<HomeUiState> {
    val finished = finishedShuffleSeeds().stateIn(viewModelScope, SharingStarted.Eagerly, 0 to emptyList())
    requestHomeShuffleMetadata(finished)
    return combine(
        _uiState,
        episodeShuffleStore.profiles,
        combine(watchProgressRepository.watchedItems, watchProgressRepository.allProgress) { watched, progress ->
            val keys = watched.mapNotNull { item ->
                item.season?.let { season -> item.episode?.let { item.contentId to (season to it) } }
            } + progress.filter { it.isCompleted() }.mapNotNull { item ->
                item.season?.let { season -> item.episode?.let { item.contentId to (season to it) } }
            }
            keys.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() } to progress
        }.distinctUntilChanged(),
        shuffleHomeRefresh,
        finished
    ) { state, profile, history, refresh, seedSnapshot ->
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
            if (!shuffleEnabled && meta?.tvdbOrder == true) return@mapNotNull null
            if (meta == null) traceCwCard(seed.contentId) { "tracker seed without cached metadata" }
            seed.toShuffleCard(meta)
        }
        applyHomeShuffle(state, profile, watched, episodeShuffle, refresh.visit, continueWatchingSortMode, cards) { id, type ->
            val cached = cachedCwMeta(type, id)
            if (cached == null) traceCwCard(id) {
                if (cwMetaNegativeCacheTimestamps.containsKey("$type:$id")) "negative metadata entry" else "cold metadata cache"
            }
            cached?.takeIf { hasUsableCwCatalogue(it, profile.profileId, id) }?.videos?.map { video ->
                Video(video.id, video.title.orEmpty(), video.released, video.thumbnail,
                    season = video.season, episode = video.episode, overview = video.overview, available = video.available,
                    episodeIdentity = video.episodeIdentity)
            }
        }.let { withTvdbLabels(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())
}

private const val CW_TRACE_TAG = "NuvioCwTrace"

internal inline fun traceCwCard(id: String, reason: () -> String) {
    if (Log.isLoggable(CW_TRACE_TAG, Log.DEBUG))
        Log.d(CW_TRACE_TAG, "at=${System.currentTimeMillis()} show=$id ${reason()}")
}

private fun HomeViewModel.projectTvdbItem(item: ContinueWatchingItem): ContinueWatchingItem {
    if (item.chooseEpisode) return item
    val id = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentId; is ContinueWatchingItem.NextUp -> item.info.contentId }
    val type = when (item) { is ContinueWatchingItem.InProgress -> item.progress.contentType; is ContinueWatchingItem.NextUp -> item.info.contentType }
    val meta = cachedCwMeta(type, id)
    if (meta?.tvdbOrder != true) {
        val context = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.tvdbContext
            is ContinueWatchingItem.NextUp -> item.info.tvdbContext
        }
        val order = tvdbOrderStore?.cachedSelection(profileManager.activeProfileId.value, id)
        // A cache miss during refresh does not invalidate the episode already shown.
        // Playback still verifies the selected order against fresh metadata.
        if (meta == null && item.shufflePlayback && context != null && order?.seriesId == context.seriesId &&
            order.orderType == context.orderType) return item
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

private fun HomeViewModel.withTvdbLabels(state: HomeUiState): HomeUiState = state.copy(
    continueWatchingItems = state.continueWatchingItems.map(::projectTvdbItem),
    upcomingItems = state.upcomingItems.map(::projectTvdbItem))

private fun HomeViewModel.finishedShuffleSeeds() = combine(
    watchProgressRepository.observeNextUpSeeds(),
    traktSettingsDataStore.continueWatchingDaysCap,
    traktSettingsDataStore.dismissedNextUpKeys,
    episodeShuffleStore.profiles,
    combine(watchProgressRepository.allProgress, watchProgressRepository.watchedItems) { items, watched -> items to watched }
) { seeds, daysCap, dismissed, profile, history ->
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

/** Keep a slow lookup alive across unrelated tracker emissions. Dropping a show or switching
 * profiles cancels its job. Failed lookups wake at their cooldown even on a static Home screen. */
private fun HomeViewModel.requestHomeShuffleMetadata(seeds: Flow<Pair<Int, List<WatchProgress>>>) = viewModelScope.launch {
    val slots = Semaphore(2)
    val nativeCards = _uiState.map { it.continueWatchingItems + it.upcomingItems }.distinctUntilChanged()
    val jobs = mutableMapOf<String, Job>()
    val lastAttempt = mutableMapOf<String, Long>()
    combine(nativeCards, seeds, episodeShuffleStore.profiles, shuffleHomeRefresh, PlayerSessionTracker.isSessionOpen) {
        native, finished, profile, _, playing ->
        val targets = linkedMapOf<String, Pair<WatchProgress, Boolean>>()
        fun add(seed: WatchProgress) {
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
                    if (last != null) delay((last + CW_META_NEGATIVE_CACHE_TTL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    slots.withPermit {
                        if (needsMetadata()) {
                            lastAttempt[key] = SystemClock.elapsedRealtime()
                            try {
                                val resolved = resolveCwMeta(seed)
                                if (resolved?.tvdbOrder == true && !shuffleEnabled && !_uiState.value.let { state ->
                                        (state.continueWatchingItems + state.upcomingItems).any { it.contentId() == seed.contentId }
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
