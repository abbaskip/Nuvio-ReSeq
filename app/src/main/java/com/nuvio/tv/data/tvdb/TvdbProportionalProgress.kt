package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.EpisodeSourceIdentity
import com.nuvio.tv.domain.model.TvdbPlaybackContext

internal fun TvdbPlaybackContext.progressWeights(refs: List<EpisodeSourceIdentity> = providerSources.orEmpty(), additionalIds: Collection<Long> = emptyList()): Map<Long, Double> {
    val ids = (episodeIds + refs.flatMap { it.episodeIds } + additionalIds).distinct()
    val durations = ids.associateWith { id ->
        refs.singleOrNull { it.episodeIds == listOf(id) }?.runtimeMinutes?.takeIf { it > 0 }
            ?: episodeRuntimeMinutes?.get(id)?.takeIf { it > 0 }
    }
    return ids.associateWith { if (durations.values.all { it != null }) durations.getValue(it)!!.toDouble() else 1.0 }
}

internal fun List<Long>.progressRanges(weights: Map<Long, Double>): Map<Long, ClosedFloatingPointRange<Double>> {
    val total = sumOf { weights.getValue(it) }
    var offset = 0.0
    return associateWith { id ->
        val start = offset / total
        offset += weights.getValue(id)
        start..(offset / total)
    }
}

/** Shared timeline math; identity correspondence must already be verified. */
internal fun projectTvdbFraction(sourceIds: List<Long>, targetIds: List<Long>, weights: Map<Long, Double>, fraction: Double): Double? {
    val source = sourceIds.progressRanges(weights)
    val target = targetIds.progressRanges(weights)
    return targetIds.mapNotNull { id ->
        val part = source[id] ?: return@mapNotNull null
        if (fraction < part.start) return@mapNotNull null
        val within = ((fraction - part.start) / (part.endInclusive - part.start)).coerceIn(0.0, 1.0)
        target.getValue(id).let { it.start + within * (it.endInclusive - it.start) }
    }.maxOrNull()
}

internal fun TvdbPlaybackContext.activeProgressPart(percent: Double, weights: Map<Long, Double> = progressWeights()): Long? {
    if (!percent.isFinite() || episodeIds.isEmpty()) return null
    val fraction = (percent / 100).coerceIn(0.0, 1.0)
    return episodeIds.progressRanges(weights).entries.firstOrNull { fraction < it.value.endInclusive }?.key
        ?: episodeIds.last()
}
