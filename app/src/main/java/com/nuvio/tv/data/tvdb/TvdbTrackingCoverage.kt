package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.*
import com.nuvio.tv.core.tracking.TrackingEpisode
import java.time.LocalDate

internal data class TvdbTrackingTarget(val episode: TrackingEpisode, val contentIds: Set<Long>,
    val parentIds: com.nuvio.tv.core.tracking.TrackingExternalIds? = null,
    val parentTitle: String? = null, val parentYear: Int? = null)

internal fun TvdbTrackingTarget.matchesParent(progress: WatchProgress): Boolean {
    val ids = parentIds ?: return true
    if (progress.traktShowId != null && ids.trakt != null) return progress.traktShowId.toLong() == ids.trakt
    val source = com.nuvio.tv.core.tracking.parseTrackingExternalIds(progress.contentId)
    return source.imdb != null && source.imdb == ids.imdb || source.tmdb != null && source.tmdb == ids.tmdb ||
        source.trakt != null && source.trakt == ids.trakt || source.simkl != null && source.simkl == ids.simkl ||
        source.tvdb != null && source.tvdb == ids.tvdb
}

internal fun tvdbAiredDatesAgree(first: String?, second: String?): Boolean = runCatching {
    first != null && second != null && kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(
        LocalDate.parse(first.take(10)), LocalDate.parse(second.take(10)))) <= 1
}.getOrDefault(false)

private val episodeNumberWords = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
    "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10")
private val episodeNumberWordPattern = Regex("\\b(?:${episodeNumberWords.keys.joinToString("|")})\\b")
internal fun trackerEpisodeTitleKey(value: String): String = episodeNumberWordPattern.replace(tvdbEpisodeMatchTitle(value)) {
    episodeNumberWords.getValue(it.value)
}

/** Prepare once per catalogue projection, rather than normalizing every TVDB
 * title again for each tracker episode (and each progress emission). */
internal class TvdbTrackerContentIndex(meta: Meta) {
    private val records = meta.episodeOrder?.content.orEmpty()
    private val byId = records.groupBy { it.tvdbId }
    private val byTitle = records.groupBy { trackerEpisodeTitleKey(it.title) }

    fun match(id: Long?, title: String?, aired: String?): Set<Long>? {
        val signature = title?.let(::trackerEpisodeTitleKey) ?: return null
        // Catalogue IDs can be stale; corroborate them with an air date or title.
        val candidates = byId[id].orEmpty()
        val record = candidates.singleOrNull()
        if (record != null && tvdbAiredDatesAgree(record.aired, aired)) return record.episodeIds.toSet()
        val exact = candidates.singleOrNull { trackerEpisodeTitleKey(it.title) == signature }
        if (exact != null && (exact.aired == null || aired == null)) return exact.episodeIds.toSet()
        return byTitle[signature].orEmpty().filter { tvdbAiredDatesAgree(it.aired, aired) }
            .map { it.episodeIds.toSet() }.distinct().singleOrNull()
    }
}

/** Partial source percentages cannot describe a larger or smaller tracker item.
 * Whole-source completion can cover several verified destinations; a larger
 * destination additionally requires all its parts in existing local progress. */
internal fun eligibleTvdbTargets(sourceIds: Set<Long>, targets: List<TvdbTrackingTarget>,
    completed: Boolean?, locallyCompleted: Set<Long>): List<TvdbTrackingTarget> {
    if (sourceIds.isEmpty() || targets.isEmpty() || !targets.flatMap { it.contentIds }.containsAll(sourceIds)) return emptyList()
    if (completed == false) return targets.singleOrNull { it.contentIds == sourceIds }?.let(::listOf).orEmpty()
    val covered = if (completed == true) locallyCompleted + sourceIds else sourceIds
    return targets.filter { covered.containsAll(it.contentIds) && it.contentIds.any(sourceIds::contains) }
}
