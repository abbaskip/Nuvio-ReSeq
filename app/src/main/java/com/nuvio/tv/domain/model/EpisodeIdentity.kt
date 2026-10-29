package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class EpisodeOrderStatus(
    val seriesId: Long,
    val selectedType: String,
    val effectiveType: String?,
    val message: String? = null,
    val content: List<EpisodeContentIdentity> = emptyList()
)

@Immutable
data class EpisodeContentIdentity(val tvdbId: Long, val title: String, val aired: String?,
    val officialCoordinates: EpisodeCoordinates?, val episodeIds: List<Long>)

val Video.displaySeason: Int? get() = episodeIdentity?.displayed?.season ?: season
val Video.displayEpisode: Int? get() = episodeIdentity?.displayed?.episode ?: episode
val Video.sourceSeason: Int? get() = episodeIdentity?.streamCoordinates?.season ?: episodeIdentity?.providerCoordinates?.season ?: season
val Video.sourceEpisode: Int? get() = episodeIdentity?.streamCoordinates?.episode ?: episodeIdentity?.providerCoordinates?.episode ?: episode
val Video.hasResolvedSource: Boolean get() = episodeIdentity == null ||
    (if (episodeIdentity.usesSelectedOrderForStreams) episodeIdentity.streamVideoId != null && episodeIdentity.streamCoordinates != null
    else ((episodeIdentity.streamVideoId ?: episodeIdentity.providerVideoId) != null &&
        (episodeIdentity.streamCoordinates ?: episodeIdentity.providerCoordinates) != null)
    )
val Video.episodeListKey: String get() = episodeIdentity?.canonicalKey ?: id

val episodeSequenceComparator: Comparator<Video> = compareBy<Video> { it.displaySeason ?: Int.MAX_VALUE }
    .thenBy { it.displayEpisode ?: Int.MAX_VALUE }

fun Video.forEpisodeDisplay(): Video = if (episodeIdentity == null) this else
    copy(season = displaySeason, episode = displayEpisode)

fun Video.forEpisodeSource(): Video = if (episodeIdentity == null) this else
    copy(season = sourceSeason, episode = sourceEpisode)

/** Coordinates always belong to a particular source; they are not an identity. */
@Immutable
data class EpisodeCoordinates(val season: Int, val episode: Int) {
    init {
        require(season >= 0 && episode > 0)
    }
}

@Immutable
data class EpisodeSourceIdentity(val videoId: String, val coordinates: EpisodeCoordinates,
    val episodeIds: List<Long>, val title: String, val runtimeMinutes: Int? = null)

/** Additional identity for an explicitly selected TVDB episode. Ordinary videos need none. */
@Immutable
data class EpisodeIdentity(
    val tvdbEpisodeId: Long,
    val tvdbSeriesId: Long,
    val orderType: String,
    val displayed: EpisodeCoordinates,
    val aired: EpisodeCoordinates?,
    val providerVideoId: String? = null,
    val providerCoordinates: EpisodeCoordinates? = null,
    val canonicalTvdbEpisodeId: Long? = null,
    val providerTitle: String? = null,
    val canonicalEpisodeIds: List<Long> = emptyList(),
    val providerSources: List<EpisodeSourceIdentity> = emptyList(),
    val contentRuntimeMinutes: Map<Long, Int> = emptyMap(),
    val streamVideoId: String? = null,
    val streamCoordinates: EpisodeCoordinates? = null,
    val contentVerified: Boolean = true,
    val usesSelectedOrderForStreams: Boolean = false
) {
    init {
        require(tvdbEpisodeId > 0 && tvdbSeriesId > 0 && orderType.isNotBlank())
    }

    val canonicalKey: String get() = "tvdb:$tvdbSeriesId:episode:$tvdbEpisodeId"
    val contentEpisodeIds: List<Long> get() = canonicalEpisodeIds.ifEmpty { listOf(canonicalTvdbEpisodeId ?: tvdbEpisodeId) }
    val trackingEpisodeIds: List<Long> get() = if (contentVerified) contentEpisodeIds else emptyList()
    val trackingEpisodeId: Long get() = trackingEpisodeIds.firstOrNull() ?: tvdbEpisodeId
}

/** Several display entries may share one verified physical episode, never two destinations. */
fun Iterable<Video>.singleEpisodeSource(): Video? {
    val videos = toList()
    if (videos.none { it.episodeIdentity != null }) return videos.singleOrNull()
    if (videos.size > 1 && (videos.any { !it.hasResolvedSource } ||
        videos.map { it.episodeListKey }.distinct().size != videos.size)) return null
    return videos.distinctBy {
        Triple(it.id, it.episodeIdentity?.trackingEpisodeId, it.season to it.episode)
    }.singleOrNull()
}

fun Iterable<Video>.distinctEpisodeSources(): List<Video> {
    val seen = mutableSetOf<Triple<String, Long, Pair<Int?, Int?>>>()
    return filter { video -> video.episodeIdentity?.let {
        seen.add(Triple(video.id, it.trackingEpisodeId, video.season to video.episode))
    } ?: true }
}
