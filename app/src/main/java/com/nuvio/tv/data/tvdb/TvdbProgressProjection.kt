package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.*

/** Stable IDs win over coordinates. Unverifiable legacy records are retained in
 * storage but cannot mark a different episode watched in a selected order. */
internal fun reconcileTvdbProgress(meta: Meta?, progress: Map<Pair<Int, Int>, WatchProgress>, local: List<WatchProgress> = emptyList()): Map<Pair<Int, Int>, WatchProgress> {
    if (meta?.episodeOrder?.effectiveType == null) return progress
    val records = meta.episodeOrder.content.associateBy { it.tvdbId }
    val identified = (progress.values + local).map { entry ->
        val ids = entry.tvdbContentIds.flatMap { records[it]?.episodeIds ?: listOf(it) }.distinct()
        if (ids.isEmpty()) entry else entry.copy(tvdbEpisodeId = ids.first(), tvdbEpisodeIds = ids,
            tvdbContext = entry.tvdbContext?.copy(episodeIds = ids),
            tvdbCompletedEpisodeIds = entry.tvdbCompletedEpisodeIds?.flatMap { records[it]?.episodeIds ?: listOf(it) }?.distinct())
    }
    return reconcileTvdbVideoRecords(meta.videos, identified)
}

internal fun reconcileTvdbVideoProgress(videos: List<Video>, progress: Map<Pair<Int, Int>, WatchProgress>): Map<Pair<Int, Int>, WatchProgress> {
    return reconcileTvdbVideoRecords(videos, progress.values.toList())
}

private fun reconcileTvdbVideoRecords(videos: List<Video>, progress: List<WatchProgress>): Map<Pair<Int, Int>, WatchProgress> {
    if (videos.none { it.episodeIdentity != null }) return progress.mapNotNull { p -> p.season?.let { s -> p.episode?.let { e -> (s to e) to p } } }.toMap()
    // Resolve a legacy record only once, not once for every displayed episode.
    // Keep input order for equal timestamps, matching the original reconciliation.
    val byContent = mutableMapOf<Long, MutableList<Int>>()
    val legacyByVideo = mutableMapOf<String, MutableList<Int>>()
    progress.forEachIndexed { index, entry ->
        if (entry.tvdbEpisodeIds?.isEmpty() == true) return@forEachIndexed
        val ids = entry.tvdbContentIds
        if (ids.isNotEmpty()) ids.forEach { byContent.getOrPut(it, ::mutableListOf).add(index) }
        else resolveTvdbProgressVideo(videos, entry)?.id?.let { legacyByVideo.getOrPut(it, ::mutableListOf).add(index) }
    }
    return videos.filter { it.hasResolvedSource }.distinctEpisodeSources().mapNotNull { video ->
        val identity = video.episodeIdentity ?: return@mapNotNull null
        val ids = identity.trackingEpisodeIds.toSet()
        if (ids.isEmpty()) return@mapNotNull null
        val entries = (ids.flatMap { byContent[it].orEmpty() } + legacyByVideo[video.id].orEmpty())
            .distinct().sorted().map(progress::get)
        if (entries.isEmpty()) return@mapNotNull null
        // A newer replay replaces older completion for the content it covers.
        val complete = ids.filterTo(mutableSetOf()) { id ->
            val covering = entries.filter { it.tvdbContentIds.isEmpty() || id in it.tvdbContentIds }
            val latest = covering.maxOfOrNull { it.lastWatched }
            covering.any { entry -> entry.lastWatched == latest &&
                (if (entry.tvdbContentIds.isEmpty()) entry.isCompleted() else id in entry.tvdbWatchedIds) }
        }
        val actual = entries.filter { it.tvdbContentIds.isEmpty() || it.tvdbContentIds == ids }
            .maxByOrNull { it.lastWatched }
        val entry = actual ?: entries.maxBy { it.lastWatched }.copy(position = 0, duration = 0, progressPercent = null)
        val whole = complete.containsAll(ids)
        (video.season!! to video.episode!!) to entry.copy(videoId = video.id, season = video.season, episode = video.episode,
            position = if (whole) entry.duration.coerceAtLeast(1) else entry.position,
            duration = if (whole) entry.duration.coerceAtLeast(1) else entry.duration,
            progressPercent = if (whole) 100f else entry.progressPercent,
            tvdbEpisodeId = ids.first(), tvdbEpisodeIds = ids.toList(), tvdbCompletedEpisodeIds = complete.toList(),
            tvdbContext = video.tvdbPlaybackContext())
    }.toMap()
}

internal fun resolveTvdbProgressVideo(videos: List<Video>, entry: WatchProgress): Video? =
    if (entry.tvdbEpisodeIds?.isEmpty() == true) null else videos.filter { it.hasResolvedSource }.let { resolved ->
        if (entry.tvdbContext != null) {
            resolved.filter { it.episodeIdentity?.trackingEpisodeIds?.toSet() == entry.tvdbContentIds && entry.tvdbContentIds.isNotEmpty() }
                .singleOrNull { it.episodeIdentity?.tvdbEpisodeId == entry.tvdbContext.episodeId }
                ?: resolved.filter { it.episodeIdentity?.trackingEpisodeIds?.toSet() == entry.tvdbContentIds && entry.tvdbContentIds.isNotEmpty() }.singleOrNull()
        } else if (entry.tvdbEpisodeId != null) {
            resolved.singleOrNull { it.episodeIdentity?.tvdbEpisodeId == entry.tvdbEpisodeId }
                ?: resolved.filter { it.episodeIdentity?.trackingEpisodeId == entry.tvdbEpisodeId }.singleEpisodeSource()
        } else if (entry.trackingProviderId == "simkl") {
            resolved.filter { it.episodeIdentity?.aired?.season == entry.season && it.episodeIdentity?.aired?.episode == entry.episode }.singleEpisodeSource()
        } else {
            val title = entry.episodeTitle?.let(::normalizeEpisodeName)?.takeIf { it.isNotBlank() }
            resolved.filter { video -> title != null && (video.episodeIdentity?.providerSources?.any { ref ->
                ref.videoId == entry.videoId && ref.coordinates.season == entry.season && ref.coordinates.episode == entry.episode &&
                    normalizeEpisodeName(ref.title) == title
            } ?: (video.id == entry.videoId && normalizeEpisodeName(video.title) == title)) }.singleEpisodeSource()
        }
    }
