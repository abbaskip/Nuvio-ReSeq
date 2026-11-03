package com.nuvio.tv.domain.model

import java.net.URLEncoder
import kotlin.random.Random

/** A profile-owned collection. Episode metadata and streams are deliberately not persisted. */
data class TvMixShow(
    val id: String,
    val name: String,
    val poster: String? = null,
    val type: String = "series",
    val imdbId: String? = null,
    val addonBaseUrl: String? = null
) {
    fun sameShow(other: TvMixShow) = id == other.id ||
        (!imdbId.isNullOrBlank() && imdbId == other.imdbId) || id == other.imdbId || imdbId == other.id
}

data class TvMix(
    val id: String,
    val name: String,
    val shows: List<TvMixShow> = emptyList(),
    val unwatchedOnly: Boolean = false
) {
    fun adding(show: TvMixShow): TvMix = if (shows.any { it.sameShow(show) }) this else copy(shows = shows + show)
    fun preview(): MetaPreview {
        val art = "tvmix-art:" + shows.mapNotNull { it.poster?.takeIf(String::isNotBlank) }.distinct().take(4)
            .joinToString("|") { URLEncoder.encode(it, "UTF-8") }
        return MetaPreview("tvmix:$id", ContentType.UNKNOWN, "tvmix", name, art, PosterShape.POSTER,
            art, null, null, null, null, emptyList())
    }
}

data class TvMixSelection(val meta: Meta, val video: Video) {
    val key: Pair<String, String> get() = meta.id to video.id
}

/** A uniform permutation gives each eligible show equal probability, regardless of episode count.
 * Metadata is resolved serially, stopping as soon as a candidate is found. */
internal suspend fun selectTvMixEpisode(
    shows: List<TvMixShow>,
    previous: Pair<String, String>?,
    random: Random = Random.Default,
    lastPlayed: Pair<String, String>? = null,
    candidates: suspend (TvMixShow) -> Pair<Meta, List<Video>>?
): TvMixSelection? {
    var previewFallback: TvMixSelection? = null
    var playedFallback: TvMixSelection? = null
    for (show in shows.shuffled(random)) {
        val (meta, episodes) = candidates(show) ?: continue
        val alternatives = episodes.filterNot { meta.id to it.id == previous || meta.id to it.id == lastPlayed }
        // Use the same episode picker and availability/identity rules as episode shuffle.
        val video = RandomEpisodePicker(meta.id, alternatives, emptySet(), emptyMap(), random).pick(true)
        if (video != null) return TvMixSelection(meta, video)
        if (previewFallback == null) previous?.takeIf { it.first == meta.id && it != lastPlayed }?.let { key ->
            RandomEpisodePicker(meta.id, episodes, emptySet(), emptyMap(), random).find(key.second, true)?.let {
                previewFallback = TvMixSelection(meta, it)
            }
        }
        if (playedFallback == null) lastPlayed?.takeIf { it.first == meta.id }?.let { key ->
            RandomEpisodePicker(meta.id, episodes, emptySet(), emptyMap(), random).find(key.second, true)?.let {
                playedFallback = TvMixSelection(meta, it)
            }
        }
    }
    // A two-episode mix can have only the current preview left after excluding the
    // episode just played. Keep that preview rather than immediately replaying it.
    return previewFallback ?: playedFallback
}
