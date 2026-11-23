package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.poster.CustomPosterUrlResolver
import com.nuvio.tv.data.tvdb.TvdbTrackingTarget
import com.nuvio.tv.data.tvdb.progressRanges
import com.nuvio.tv.data.tvdb.progressWeights
import com.nuvio.tv.domain.model.EpisodeContentIdentity
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.TvdbPlaybackContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToLong
import tv.seekr.previews.core.SeekrContent

internal data class SeekrWindow(
    val content: SeekrContent,
    val durationMs: Long,
    val startMs: Long = 0,
    val endMs: Long = durationMs,
    val offsetMs: Long = 0,
) {
    fun positionInTrack(positionMs: Long) = (positionMs - startMs + offsetMs).coerceIn(0, durationMs)
}

internal data class SeekrEpisodeMatch(val content: SeekrContent, val episodeIds: List<Long>)

private val imdbId = Regex("tt[0-9]+")
private val episodeAddress = Regex("""(.+):(\d+):(\d+)""")

private fun externalIds(id: String?, imdb: String?) =
    CustomPosterUrlResolver.extractIds(id.orEmpty(), imdb?.takeIf(imdbId::matches))

internal fun seekrTitle(id: String?, state: PlayerUiState, imdb: String? = null): SeekrContent? {
    val ids = externalIds(id, imdb)
    val showImdb = ids.imdbId?.takeIf(imdbId::matches)
    val tmdb = ids.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
    if (showImdb == null && tmdb == null) return null
    return when (state.contentType?.lowercase()) {
        "movie" -> SeekrContent.Movie(tmdb, showImdb)
        "series" -> {
            val season = state.currentSeason?.takeIf { it > 0 } ?: return null
            val episode = state.currentEpisode?.takeIf { it > 0 } ?: return null
            SeekrContent.Episode(tmdb, showImdb, season, episode)
        }
        else -> null
    }
}

/** ReSeq has already checked provider episode coverage using content IDs, titles and air dates.
 * Only use a numbered provider ID when it identifies this parent and agrees with its own row. */
internal fun TvdbPlaybackContext.previewProviderMatches(parentId: String?, parentImdb: String?): List<SeekrEpisodeMatch> {
    val parents = setOfNotNull(parentId, parentImdb)
    return providerSources.orEmpty().mapNotNull { source ->
        val address = episodeAddress.matchEntire(source.videoId) ?: return@mapNotNull null
        val (parent, season, episode) = address.destructured
        if (parent !in parents || season.toIntOrNull() != source.coordinates.season ||
            episode.toIntOrNull() != source.coordinates.episode || source.coordinates.season < 1) return@mapNotNull null
        val ids = externalIds(parent, null)
        val tmdb = ids.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
        val imdb = ids.imdbId?.takeIf(imdbId::matches)
        if (tmdb == null && imdb == null) return@mapNotNull null
        SeekrEpisodeMatch(SeekrContent.Episode(tmdb, imdb, source.coordinates.season, source.coordinates.episode), source.episodeIds)
    }
}

internal fun TvdbTrackingTarget.previewEpisodeMatch(records: List<EpisodeContentIdentity>): SeekrEpisodeMatch? {
    val show = parentIds?.tmdb?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
    val season = episode.season?.takeIf { it > 0 } ?: return null
    if (episode.number < 1) return null
    val rows = records.filter { it.episodeIds.toSet() == contentIds }
    val atoms = rows.singleOrNull { it.tvdbId == episode.tvdbId }?.episodeIds
        ?: rows.map { it.episodeIds }.distinct().singleOrNull() ?: return null
    return SeekrEpisodeMatch(SeekrContent.Episode(showTmdbId = show, season = season, episode = episode.number), atoms)
}

/** Use the same atomic runtimes as ReSeq progress projection. A file can span several catalogue
 * episodes, or its entire timeline can lie inside one catalogue episode. */
internal fun TvdbPlaybackContext.previewWindows(durationMs: Long, matches: List<SeekrEpisodeMatch>): List<SeekrWindow> {
    if (durationMs <= 0 || episodeIds.isEmpty() || episodeIds.distinct() != episodeIds || !permitsTracking(durationMs)) return emptyList()
    val candidates = matches.distinct().filter { match -> match.episodeIds.any(episodeIds::contains) }
    val whole = candidates.filter { it.episodeIds == episodeIds }
    if (whole.isNotEmpty()) return whole.singleOrNull()?.let { listOf(SeekrWindow(it.content, durationMs)) }.orEmpty()
    val targets = episodeIds.map { id -> candidates.singleOrNull { id in it.episodeIds } }
    val allIds = (episodeIds + targets.filterNotNull().flatMap { it.episodeIds }).distinct()
    // A duration estimate is needed for a part inside a track. Equal shares would give plausible
    // but wrong images for two episodes of very different lengths.
    fun minutes(id: Long) = providerSources.orEmpty().singleOrNull { it.episodeIds == listOf(id) }?.runtimeMinutes?.takeIf { it > 0 }
        ?: episodeRuntimeMinutes?.get(id)?.takeIf { it > 0 }
    if (allIds.any { minutes(it) == null }) return emptyList()
    val weights = progressWeights(providerSources.orEmpty().filter { it.episodeIds.all(allIds::contains) }, allIds)
    val sourceWeight = episodeIds.sumOf(weights::getValue)
    // Reject a file which appears to contain a different cut (e.g. the entire double episode for a selected half).
    if (durationMs / (sourceWeight * 60_000) !in 0.75..1.25) return emptyList()
    val source = episodeIds.progressRanges(weights)
    return episodeIds.zip(targets).mapNotNull { (id, target) ->
        if (target == null || target.episodeIds != target.episodeIds.distinct()) return@mapNotNull null
        val targetDuration = (durationMs * target.episodeIds.sumOf(weights::getValue) / sourceWeight).roundToLong()
        val targetRange = target.episodeIds.progressRanges(weights).getValue(id)
        val range = source.getValue(id)
        SeekrWindow(target.content, targetDuration, (durationMs * range.start).roundToLong(),
            (durationMs * range.endInclusive).roundToLong(), (targetDuration * targetRange.start).roundToLong())
    }
}

internal fun List<SeekrWindow>.windowAt(positionMs: Long, durationMs: Long): SeekrWindow? {
    val position = positionMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
    return firstOrNull { position >= it.startMs && position < it.endMs }
}


internal suspend fun seekrWindows(
    parentId: String?, state: PlayerUiState, durationMs: Long, selected: TvdbPlaybackContext?,
    meta: Meta?, providerOrder: Boolean, explicitOrder: Boolean,
    hasSelectedOrder: suspend (String) -> Boolean,
    tmdbMatches: suspend (Meta, Set<Long>) -> List<TvdbTrackingTarget>,
): List<SeekrWindow> {
    if (selected == null) {
        if (state.contentType.equals("series", true) && !providerOrder) {
            // A restored player can receive the video duration before the episode catalogue.
            if (explicitOrder || state.tvdbEpisodeIdentity != null || meta?.episodeOrder != null) return emptyList()
            if (meta == null && parentId != null && hasSelectedOrder(parentId)) return emptyList()
        }
        return listOfNotNull(seekrTitle(parentId, state, meta?.imdbId)?.let { SeekrWindow(it, durationMs) })
    }
    if (selected.episodeIds.isEmpty() || !selected.permitsTracking(durationMs)) return emptyList()
    val provider = selected.previewProviderMatches(parentId, meta?.imdbId)
    val order = meta?.episodeOrder?.takeIf { it.seriesId == selected.seriesId }
    // A TVDB episode can belong to a different parent or season in the destination catalogue.
    val translated = if (order == null) emptyList() else try {
        withTimeoutOrNull(2_500) {
            tmdbMatches(meta, selected.episodeIds.toSet()).mapNotNull { it.previewEpisodeMatch(order.content) }
        }.orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
    return selected.previewWindows(durationMs, translated.ifEmpty { provider })
}
