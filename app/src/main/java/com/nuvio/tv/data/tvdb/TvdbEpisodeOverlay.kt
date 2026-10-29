package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.*
import java.text.Normalizer

private val episodeCombiningMarks = Regex("\\p{M}")
private val episodePunctuation = Regex("[^\\p{L}\\p{N}]+")
private val genericEpisodeTitle = Regex("(?:episode|ep|e) ?\\d+")

private data class ProviderCoverage(val video: Video, val episodes: List<TvdbEpisode>) {
    val ids get() = episodes.map { it.id }
    fun reference() = EpisodeSourceIdentity(video.id, EpisodeCoordinates(video.season!!, video.episode!!), ids, video.title, video.runtime)
}

/** The selected order supplies stream-search numbers. Content correspondence is
 * used for metadata restrictions, legacy history and tracker translation only. */
internal fun tvdbEpisodeOverlay(meta: Meta, seriesId: Long, order: TvdbEpisodeOrder,
    sources: Map<Long, TvdbSourceReference> = emptyMap()): Meta {
    val graph = TvdbContentCoverage(order)
    val coverage = graph.byId
    fun oldCoverage(ref: TvdbSourceReference) = ref.trackingEpisodeIds.flatMap { coverage[it]?.map(TvdbEpisode::id) ?: listOf(it) }
    val oldByVideo = sources.values.groupBy { it.videoId }
    val oldByCoordinates = sources.values.groupBy { it.coordinates }
    val eligible = meta.videos.filter { it.id.isNotBlank() && it.season != null && it.season >= 0 && it.episode != null && it.episode > 0 }
    val uniqueIds = eligible.groupingBy { it.id }.eachCount()
    val numberedId = Regex("^(.*):(\\d+):(\\d+)$")
    val prefixes = eligible.mapNotNull { video -> numberedId.matchEntire(video.id)?.takeIf {
        it.groupValues[2].toIntOrNull() == video.season && it.groupValues[3].toIntOrNull() == video.episode
    }?.groupValues?.get(1) }.distinct()
    val requestPrefix = prefixes.singleOrNull() ?: (meta.imdbId ?: meta.id).takeIf {
        prefixes.isEmpty() && it.matches(Regex("(?:tt\\d+|tmdb:\\d+|tvdb:\\d+)"))
    }
    val provider = eligible.filter { uniqueIds[it.id] == 1 }.mapNotNull { video ->
        val explicit = video.episodeIdentity
        val episodes = if (explicit != null) {
            if (explicit.tvdbSeriesId != seriesId || explicit.trackingEpisodeIds.any { it !in coverage }) emptyList()
            else explicit.trackingEpisodeIds.flatMap { coverage.getValue(it) }.distinctBy { it.id }
        } else {
            val date = video.released?.take(10)
            val signature = tvdbEpisodeMatchTitle(video.title)
            if (date == null || signature.isBlank() || genericEpisodeTitle.matches(signature)) emptyList()
            else graph.match(video.title, date)
        }
        episodes.takeIf { it.isNotEmpty() }?.let { ProviderCoverage(video, it) }
    }
    val videos = order.episodes.map { episode ->
        val content = coverage[episode.id].orEmpty()
        val ids = content.map { it.id }.toSet()
        val covering = provider.filter { it.ids.toSet().containsAll(ids) && ids.isNotEmpty() }
        val whole = covering.filter { it.ids.toSet() == ids }.ifEmpty { covering }
        val chosen = if (whole.size == 1) whole else if (whole.isNotEmpty() || ids.isEmpty()) emptyList() else {
            val pieces = provider.filter { it.ids.all(ids::contains) }
            if (ids.all { id -> pieces.count { id in it.ids } == 1 }) pieces else emptyList()
        }
        val verified = chosen.takeIf { matches -> matches.all { candidate ->
            val current = TvdbSourceReference(candidate.ids.first(), candidate.video.id, candidate.reference().coordinates)
            val previous = (sources[episode.id]?.takeIf { matches.size == 1 }
                ?: sources[candidate.ids.first()])?.takeIf { sameEpisodeSourceParent(it, current) }
            (previous == null || (previous.videoId == candidate.video.id && previous.coordinates == candidate.reference().coordinates)) &&
                (oldByVideo[candidate.video.id].orEmpty() + oldByCoordinates[candidate.reference().coordinates].orEmpty().filter { sameEpisodeSourceParent(it, current) })
                    .none { old -> !candidate.ids.containsAll(oldCoverage(old)) }
        } }.orEmpty()
        val refs = verified.map { it.reference() }
        val primary = verified.firstOrNull()
        val requestId = requestPrefix?.let { "$it:${episode.coordinates.season}:${episode.coordinates.episode}" }
        val identity = EpisodeIdentity(episode.id, seriesId, order.effectiveType, episode.coordinates,
            content.firstOrNull()?.coordinates, primary?.video?.id, refs.firstOrNull()?.coordinates,
            canonicalTvdbEpisodeId = content.firstOrNull()?.id?.takeIf { it != episode.id },
            providerTitle = primary?.video?.title, canonicalEpisodeIds = content.map { it.id }, providerSources = refs,
            contentRuntimeMinutes = (content + verified.flatMap { it.episodes }).distinctBy { it.id }.mapNotNull { row -> row.runtime?.takeIf { it > 0 }?.let { row.id to it } }.toMap(),
            streamVideoId = requestId, streamCoordinates = episode.coordinates.takeIf { requestId != null },
            contentVerified = content.isNotEmpty(), usesSelectedOrderForStreams = true)
        val available = when {
            verified.isEmpty() -> null
            verified.any { it.video.available == false } -> false
            verified.all { it.video.available == true } -> true
            else -> null
        }
        val display = Video(requestId ?: "tvdb-unresolved:$seriesId:${episode.id}", episode.title, episode.aired,
            episode.image?.takeIf(String::isNotBlank) ?: primary?.video?.thumbnail,
            streams = emptyList(),
            season = episode.coordinates.season, episode = episode.coordinates.episode, overview = episode.overview,
            runtime = episode.runtime, available = available, episodeIdentity = identity)
        display
    }.sortedWith(episodeSequenceComparator)
    val unresolved = videos.count { it.episodeIdentity?.contentVerified != true }
    val messages = listOfNotNull("Using ${order.effectiveType}".takeIf { order.selectedType != order.effectiveType },
        "Using cached TVDB metadata".takeIf { order.stale },
        "$unresolved episode(s) have no verified tracking correspondence; remote tracking is withheld".takeIf { unresolved > 0 })
    return meta.copy(videos = videos,
        behaviorHints = meta.behaviorHints?.copy(defaultVideoId = null),
        episodeOrder = EpisodeOrderStatus(seriesId, order.selectedType, order.effectiveType,
            messages.takeIf { it.isNotEmpty() }?.joinToString(". "),
            graph.records.mapNotNull { row -> coverage[row.id]?.let { atoms ->
                EpisodeContentIdentity(row.id, row.title, row.aired,
                    order.officialEpisodes.singleOrNull { it.id == row.id }?.coordinates, atoms.map { it.id })
            } }))
}

internal fun normalizeEpisodeName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .lowercase(java.util.Locale.ROOT).replace(episodeCombiningMarks, "")
    .replace(episodePunctuation, " ").trim()
