package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.cw.CwLease
import com.nuvio.tv.data.cw.CwMembershipReducer
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal val HomeViewModel.continueWatchingRepository get() = cwEnrichmentCache.repository

/** The only publication path into Home's CW lists. Metadata workers cannot remove members. */
internal fun HomeViewModel.observeContinueWatchingProjection() {
    viewModelScope.launch {
        val scoped = combine(continueWatchingRepository.projection.filterNotNull(), profileManager.activeProfileId,
            traktSettingsDataStore.watchProgressSource) { state, profile, source ->
            if (state.profileId == profile && state.source == source.name) state
            else com.nuvio.tv.data.cw.CwProjection(profileId = profile, source = source.name)
        }
        combine(scoped,
            traktSettingsDataStore.continueWatchingDaysCap, traktSettingsDataStore.dismissedNextUpKeys,
            layoutPreferenceDataStore.showUnairedNextUp, layoutPreferenceDataStore.continueWatchingSortMode
        ) { state, days, dismissed, unaired, sort ->
            CwMembershipReducer.visible(state, days, dismissed, unaired, System.currentTimeMillis()) to sort
        }.collect { (state, sort) ->
            if (state.profileId != profileManager.activeProfileId.value) return@collect
            val cards = applyContinueWatchingEnrichmentOverlay(state.members.values.mapNotNull { member ->
                member.inProgress?.homeCard(member.progress) ?: member.nextUp?.homeCard()
            })
            val (main, upcoming) = splitUpcomingItems(sortContinueWatchingItems(cards, sort), sort)
            _uiState.update { it.copy(continueWatchingItems = main, upcomingItems = upcoming) }
            _initialCwResolved.value = true
        }
    }
}

internal suspend fun HomeViewModel.publishCwEnrichment(lease: CwLease, cards: List<ContinueWatchingItem>) {
    val ratings = layoutPreferenceDataStore.detailImdbRatingsVisibility.first()
    continueWatchingRepository.enrich(lease,
        inProgress = cards.mapNotNull { item -> (item as? ContinueWatchingItem.InProgress)?.let {
            it.cachedCard().copy(ratingVisible = it.progress.contentType !in setOf("series", "tv", "anime") ||
                ratings.showEpisodeRating(it.progress.isCompleted()))
        } },
        nextUp = cards.toCachedNextUpSnapshot(com.nuvio.tv.ui.components.brokenImageUrls)
            .map { it.copy(ratingVisible = ratings.showEpisodeRating(isWatched = false)) })
}

internal fun CachedInProgressItem.homeCard(committed: WatchProgress? = null) = ContinueWatchingItem.InProgress(
    progress = committed?.copy(name = name, poster = poster, backdrop = backdrop, logo = logo, episodeTitle = episodeTitle)
        ?: WatchProgress(contentId, contentType, name, poster, backdrop, logo, videoId, season, episode,
        episodeTitle, position, duration, lastWatched, progressPercent = progressPercent, tvdbContext = tvdbContext),
    episodeThumbnail = episodeThumbnail, episodeDescription = episodeDescription,
    episodeImdbRating = if (ratingVisible) episodeImdbRating else null, genres = genres,
    releaseInfo = releaseInfo, contentLanguage = contentLanguage, chooseEpisode = chooseEpisode)

internal fun CachedNextUpItem.homeCard() = ContinueWatchingItem.NextUp(
    info = NextUpInfo(contentId = contentId, contentType = contentType, name = name, poster = poster,
        backdrop = backdrop, logo = logo, videoId = videoId, season = season, episode = episode,
        episodeTitle = episodeTitle, episodeDescription = episodeDescription, thumbnail = thumbnail,
        released = released, hasAired = hasAired, airDateLabel = airDateLabel, lastWatched = lastWatched,
        latestSeriesActivity = latestSeriesActivity, imdbRating = if (ratingVisible) imdbRating else null,
        genres = genres, releaseInfo = releaseInfo, sortTimestamp = sortTimestamp, releaseTimestamp = releaseTimestamp,
        isReleaseAlert = isReleaseAlert, isNewSeasonRelease = isNewSeasonRelease, seedSeason = seedSeason,
        seedEpisode = seedEpisode, tvdbEpisodeId = tvdbEpisodeId, tvdbContext = tvdbContext,
        contentLanguage = contentLanguage), chooseEpisode = chooseEpisode)

internal fun ContinueWatchingItem.InProgress.cachedCard(): CachedInProgressItem {
    val p = progress
    return CachedInProgressItem(p.contentId, p.contentType, p.name, p.poster, p.backdrop, p.logo,
        p.videoId, p.season, p.episode, p.episodeTitle, p.position, p.duration, p.lastWatched, p.progressPercent,
        episodeThumbnail, episodeDescription, episodeImdbRating, genres, releaseInfo, contentLanguage,
        tvdbContext = p.tvdbContext)
}
