package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress

/** An undo addresses one play, never all history at an episode coordinate. */
data class TrackingWatchEvent(
    val id: String,
    val progress: WatchProgress,
    val from: Long,
    val through: Long,
    val priorKnown: Boolean = false,
    val priorWatchedAt: Long? = null,
    val completionExpected: Boolean = progress.isCompleted()
) {
    fun contains(at: Long) = at in from..through
}

data class TrackingWatchBaseline(val target: TrackingMediaReference, val known: Boolean, val watchedAt: Long?,
    val completionExpected: Boolean = true)

internal fun TrackingMediaReference.matchesEpisode(row: WatchProgress): Boolean {
    val episode = episode ?: return false
    if (row.contentId != catalog?.contentId) return false
    return when {
        row.tvdbContext != null && catalog?.tvdbContext != null -> row.tvdbContext.episodeId == catalog.tvdbContext.episodeId &&
            row.tvdbContext.seriesId == catalog.tvdbContext.seriesId
        episode.traktId != null && row.traktEpisodeId != null -> episode.traktId == row.traktEpisodeId
        episode.tvdbId != null && row.tvdbEpisodeId != null -> episode.tvdbId == row.tvdbEpisodeId
        else -> row.season == episode.season && row.episode == episode.number
    }
}
