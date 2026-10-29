package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.EpisodeSourceIdentity
import com.nuvio.tv.domain.model.TvdbPlaybackContext
import com.nuvio.tv.domain.model.WatchProgress

// Fixed protocol cutover: pre-fix cloud rows lost their selected-order identity.
// Keep them stored, but do not reinterpret them as verified native TVDB resume data.
internal const val TVDB_NATIVE_CLOUD_PROGRESS_FROM = 1793239048000L

private fun TvdbPlaybackContext.nativeReferences(): List<EpisodeSourceIdentity>? {
    if (episodeIds.isEmpty()) return null
    val refs = providerSources.orEmpty().filter { it.episodeIds.any(episodeIds::contains) }
    return refs.takeIf { episodeIds.all { id -> refs.count { id in it.episodeIds } == 1 } &&
        refs.all { it.videoId.isNotBlank() && it.episodeIds.isNotEmpty() } }
}

/** Numeric keys are enforced by Nuvio cloud, which also derives watched entries at 90%.
 * Estimate only within verified native coverage; never send selected-order coordinates. */
internal fun WatchProgress.forNuvioCloud(completedIds: Set<Long> = emptySet()): List<WatchProgress> {
    val context = tvdbContext ?: return listOf(this)
    val refs = context.nativeReferences() ?: return emptyList()
    if (refs.size == 1 && refs.single().episodeIds.toSet() == context.episodeIds.toSet() && tvdbCoverageVerified != false) {
        val native = refs.single()
        return listOf(copy(videoId = native.videoId, season = native.coordinates.season,
            episode = native.coordinates.episode, tvdbContext = null))
    }
    if (duration <= 0L) return emptyList()
    val fraction = if (isCompleted()) 1.0 else (position.toDouble() / duration).coerceIn(0.0, 1.0)
    val weights = context.progressWeights(refs)
    val source = context.episodeIds.progressRanges(weights)
    val completed = completedIds + tvdbWatchedIds
    return refs.mapNotNull { native ->
        if (fraction <= 0.0) return@mapNotNull null
        val played = projectTvdbFraction(context.episodeIds, native.episodeIds, weights, fraction)
            ?.takeIf { it > 0.0 } ?: return@mapNotNull null
        val covered = context.episodeIds.containsAll(native.episodeIds) || completed.containsAll(native.episodeIds)
        val lastBoundary = native.episodeIds.mapNotNull { source[it]?.endInclusive }.maxOrNull() ?: return@mapNotNull null
        val complete = tvdbCoverageVerified != false && covered && fraction >= lastBoundary
        // Keep the backend below its automatic completion threshold until the part boundary.
        val percent = if (complete) 1.0 else played.coerceAtMost(0.89)
        val nativeDuration = native.runtimeMinutes?.takeIf { it > 0 }?.times(60_000L)
            ?: (duration * native.episodeIds.sumOf { weights.getValue(it) } /
                context.episodeIds.sumOf { weights.getValue(it) }).toLong().coerceAtLeast(1L)
        copy(videoId = native.videoId, season = native.coordinates.season, episode = native.coordinates.episode,
            position = if (complete) nativeDuration else (nativeDuration * percent).toLong(), duration = nativeDuration,
            progressPercent = null, tvdbContext = null, tvdbEpisodeId = native.episodeIds.first(),
            tvdbEpisodeIds = native.episodeIds, tvdbCompletedEpisodeIds = if (complete) native.episodeIds else emptyList())
    }
}

/** Inverse of the cloud projection. The local player can apply this percentage to
 * its actual video duration; an existing exact local position wins timestamp ties. */
internal fun nativeCloudResume(contentId: String, context: TvdbPlaybackContext, entries: List<WatchProgress>): WatchProgress? {
    val refs = context.nativeReferences() ?: return null
    val weights = context.progressWeights(refs)
    val source = context.episodeIds.progressRanges(weights)
    val candidates = entries.mapNotNull { entry ->
        if (entry.contentId != contentId || entry.duration <= 0) return@mapNotNull null
        val native = refs.singleOrNull { it.videoId == entry.videoId &&
            it.coordinates.season == entry.season && it.coordinates.episode == entry.episode } ?: return@mapNotNull null
        val target = native.episodeIds.progressRanges(weights)
        val fraction = entry.progressPercentage.toDouble()
        val position = context.episodeIds.mapNotNull { id ->
            val part = target[id] ?: return@mapNotNull null
            if (fraction < part.start) return@mapNotNull null
            val within = ((fraction - part.start) / (part.endInclusive - part.start)).coerceIn(0.0, 1.0)
            source.getValue(id).let { it.start + within * (it.endInclusive - it.start) }
        }.maxOrNull() ?: return@mapNotNull null
        Triple(entry, position, native)
    }
    val selected = candidates.maxWithOrNull(compareBy<Triple<WatchProgress, Double, EpisodeSourceIdentity>> { it.first.lastWatched }
        .thenBy { it.second }) ?: return null
    val (entry, fraction, native) = selected
    val completed = context.episodeIds.filter { id ->
        val reference = refs.single { id in it.episodeIds }
        val record = entries.filter { it.contentId == contentId && it.videoId == reference.videoId &&
            it.season == reference.coordinates.season && it.episode == reference.coordinates.episode }
            .maxByOrNull { it.lastWatched }
        record != null && (record.isCompleted() || record.progressPercentage.toDouble() >= reference.episodeIds.progressRanges(weights).getValue(id).endInclusive)
    }
    val estimatedDuration = (entry.duration * context.episodeIds.sumOf { weights.getValue(it) } /
        native.episodeIds.sumOf { weights.getValue(it) }).toLong().coerceAtLeast(1L)
    return entry.copy(videoId = context.requestVideoId, season = context.season, episode = context.episode,
        position = 0, duration = estimatedDuration, progressPercent = (fraction * 100).toFloat(),
        tvdbContext = context, tvdbEpisodeId = context.episodeIds.first(), tvdbEpisodeIds = context.episodeIds,
        tvdbCompletedEpisodeIds = completed,
        tvdbCoverageVerified = fraction < WatchProgress.COMPLETED_THRESHOLD || completed.containsAll(context.episodeIds))
}
