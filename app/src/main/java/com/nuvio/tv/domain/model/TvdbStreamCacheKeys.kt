package com.nuvio.tv.domain.model

/** Keys for the existing last-link cache. Coordinates and titles never identify TVDB content. */
internal fun streamLinkCacheKeys(type: String, videoId: String, context: TvdbPlaybackContext?,
    identity: EpisodeIdentity? = null): List<String> {
    val prefix = type.lowercase()
    if (context == null) return listOf("$prefix|$videoId")
    val ids = context.episodeIds.toSet()
    if (ids.isEmpty()) return emptyList()
    val canonical = "$prefix|tvdb:${context.seriesId}:${ids.sorted().joinToString(".")}"
    val aliases = identity?.takeIf { it.tvdbSeriesId == context.seriesId && it.trackingEpisodeIds.toSet() == ids }
        ?.providerSources.orEmpty().filter { it.episodeIds.toSet() == ids }.map { "$prefix|${it.videoId}" }
    return (listOf(canonical) + aliases).distinct()
}
