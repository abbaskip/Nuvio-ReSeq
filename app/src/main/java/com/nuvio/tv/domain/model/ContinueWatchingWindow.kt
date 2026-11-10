package com.nuvio.tv.domain.model

internal fun continueWatchingCutoff(days: Int, now: Long): Long? =
    if (days <= 0) null else now - days * 86_400_000L

/** The history window follows the show's activity even when its chosen seed is an older finale. */
internal fun latestShowActivity(
    progress: List<WatchProgress>, seeds: List<WatchProgress>, watched: List<WatchedItem>
): Map<String, Long> = buildMap {
    fun record(id: String, time: Long) { if (time > (get(id) ?: Long.MIN_VALUE)) put(id, time) }
    for (item in progress) if (item.episode != null) record(item.contentId, item.lastWatched)
    for (seed in seeds) record(seed.contentId, maxOf(seed.lastWatched, seed.latestSeriesActivity ?: 0L))
    for (item in watched) if (item.episode != null) record(item.contentId, item.watchedAt)
}
