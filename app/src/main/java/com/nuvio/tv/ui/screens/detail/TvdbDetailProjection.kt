package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.*

/** Presentation of the already reconciled selected-order progress. Historical native
 * records are retained separately; selected search slots never rewrite them. */
internal fun MetaDetailsUiState.withTvdbDisplay(): MetaDetailsUiState {
    val original = meta ?: return this
    if (original.episodeOrder?.effectiveType == null) return this
    val bySource = original.videos.filter { it.hasResolvedSource }
        .groupBy { it.season to it.episode }
    fun displayKeys(key: Pair<Int, Int>): List<Pair<Int, Int>> = bySource[key].orEmpty().mapNotNull { video ->
        video.displaySeason?.let { s -> video.displayEpisode?.let { e -> s to e } }
    }
    val nextVideo = original.videos.filter { it.id == nextToWatch?.nextVideoId }.singleEpisodeSource()
    val completedIds = episodeProgressMap.values.flatMap { it.tvdbWatchedIds }.toSet()
    val displayWatched = if (episodeProgressMap.values.any { it.tvdbContentIds.isNotEmpty() }) {
        original.videos.filter { video -> video.episodeIdentity?.contentEpisodeIds?.all(completedIds::contains) == true }
            .mapNotNull { video -> video.displaySeason?.let { s -> video.displayEpisode?.let { e -> s to e } } }.toSet()
    } else watchedEpisodes.flatMap(::displayKeys).toSet()
    return copy(
        meta = original.copy(videos = original.videos.map(Video::forEpisodeDisplay), episodeOrder = original.episodeOrder.let { status ->
            if (unverifiedEpisodeProgress == 0) status else status.copy(message = listOfNotNull(status.message,
                "$unverifiedEpisodeProgress historical progress record(s) could not be verified; history has been retained.").joinToString(". "))
        }),
        episodesForSeason = episodesForSeason.map(Video::forEpisodeDisplay),
        watchedEpisodes = displayWatched,
        episodeProgressMap = episodeProgressMap.flatMap { (key, progress) -> displayKeys(key).map { it to progress } }.toMap(),
        episodeImdbRatings = episodeImdbRatings.flatMap { (key, value) -> displayKeys(key).map { it to value } }.toMap(),
        commentsEpisodeTarget = commentsEpisodeTarget?.forEpisodeDisplay(),
        nextToWatch = nextToWatch?.let { next ->
            if (nextVideo == null) next else next.copy(nextSeason = nextVideo.displaySeason,
                nextEpisode = nextVideo.displayEpisode,
                displayText = "${if (next.isResume) "Resume" else "Play"} S${nextVideo.displaySeason}E${nextVideo.displayEpisode}")
        }
    )
}
