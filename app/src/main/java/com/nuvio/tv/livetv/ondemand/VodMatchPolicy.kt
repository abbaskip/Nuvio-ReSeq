package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.EpisodeCoordinates
import java.text.Normalizer

internal data class VodStreamTarget(
    val kind: VodKind,
    val tmdbId: String?,
    val title: String,
    val year: Int?,
    val episodeTitles: Set<String> = emptySet(),
    val tvdbEpisodeIds: Set<Long> = emptySet(),
    val selectedTvdbEpisodeId: Long? = null,
    val imdbEpisodeId: String? = null,
    val imdbId: String? = null,
    val providerCoordinates: EpisodeCoordinates? = null
)

/** IDs and titles establish episode identity before a standard metadata numbering fallback. */
internal object VodMatchPolicy {
    fun titleMatches(target: VodStreamTarget, item: VodItem): Boolean {
        if (target.kind != item.kind) return false
        if (target.tmdbId != null && item.tmdbId != null) return target.tmdbId == item.tmdbId
        return target.year != null && item.year == target.year &&
            titleKey(target.title).isNotBlank() && titleKey(target.title) == titleKey(item.name)
    }

    fun parentMatches(target: VodStreamTarget, item: VodItem, detail: VodInfo?): Boolean {
        if (target.kind != item.kind) return false
        if (item.tmdbId != null && detail?.tmdbId != null && item.tmdbId != detail.tmdbId) return false
        val verified = item.copy(tmdbId = detail?.tmdbId ?: item.tmdbId,
            year = item.year ?: detail?.releaseDate?.take(4)?.toIntOrNull())
        if (target.tmdbId != null && verified.tmdbId != null && target.tmdbId != verified.tmdbId) return false
        if (target.imdbId != null && detail?.imdbId != null && target.imdbId != detail.imdbId) return false
        return titleMatches(target, verified) || target.imdbId != null && target.imdbId == detail?.imdbId
    }

    fun episodes(target: VodStreamTarget, candidates: List<VodEpisode>, seriesTitle: String): List<VodEpisode> {
        val identified = candidates.filter { episode ->
            (target.imdbEpisodeId != null && episode.imdbId == target.imdbEpisodeId) ||
                (episode.tvdbId != null && (episode.tvdbId == target.selectedTvdbEpisodeId ||
                    target.tvdbEpisodeIds.size == 1 && episode.tvdbId in target.tvdbEpisodeIds))
        }
        if (identified.isNotEmpty()) return identified.filterNot { contradicts(target, it) }
        val titles = target.episodeTitles.map(::episodeKey).filter { distinctive(it) }.toSet()
        val matches = candidates.filter { candidate ->
            !contradicts(target, candidate) && episodeKey(candidate.title, seriesTitle) in titles
        }
        // Same title in distinct episode positions is ambiguous; leave those in the browser.
        if (matches.isNotEmpty()) return matches.takeIf { it.map { e -> e.season to e.episode }.distinct().size == 1 }.orEmpty()
        val wanted = target.providerCoordinates ?: return emptyList()
        // This is called only after the parent series has been verified. An explicit filename
        // number wins over an unreliable API episode_num (or the array-position fallback).
        return candidates.mapNotNull { candidate ->
            if (contradicts(target, candidate)) return@mapNotNull null
            val numbered = filenameCoordinates(candidate.title) ?: return@mapNotNull null
            if (numbered != wanted) return@mapNotNull null
            candidate.copy(season = numbered.season, episode = numbered.episode)
        }
    }

    private val filenameNumber = Regex("""(?i)(?<![\p{L}\p{N}])S\s*(\d{1,3})\s*E\s*(\d{1,4})(?![\p{L}\p{N}])""")
    private val additionalEpisode = Regex("""(?i)^[\s._]*(?:[-+&][\s._]*(?:E\s*)?\d+\b|E\s*\d+\b)""")
    private fun filenameCoordinates(title: String): EpisodeCoordinates? {
        val match = filenameNumber.findAll(title).singleOrNull() ?: return null
        // A range or multi-episode file cannot safely stand in for one selected episode.
        if (additionalEpisode.containsMatchIn(title.substring(match.range.last + 1))) return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return EpisodeCoordinates(season, episode)
    }

    private fun contradicts(target: VodStreamTarget, episode: VodEpisode): Boolean =
        target.imdbEpisodeId != null && episode.imdbId != null && target.imdbEpisodeId != episode.imdbId ||
            episode.tvdbId != null && target.tvdbEpisodeIds.isNotEmpty() &&
                episode.tvdbId != target.selectedTvdbEpisodeId &&
                (target.tvdbEpisodeIds.size != 1 || episode.tvdbId !in target.tvdbEpisodeIds)

    private val coordinates = Regex("""(?i)(?<![\p{L}\p{N}])(?:S\s*\d{1,3}\s*E\s*\d{1,4}|\d{1,3}x\d{1,4}|season\s*\d+\s*episode\s*\d+)(?![\p{L}\p{N}])""")
    private val edition = Regex("""(?i)\s*[\[(](?:extended(?: cut| edition)?|uncut|director's cut)[\])]\s*""")
    private val part = Regex("""(?i)\((\d+)\)|\bpart\s*(\d+)\b""")
    private val generic = Regex("""(?:episode|ep|part|chapter|episodio|folge|capitulo|odcinek)?\d*""")
    private val placeholders = setOf("noepisodetitle", "episodetitle", "notitle", "untitled", "unknown", "tba", "tbd", "tobeannounced", "specialepisode")
    private fun distinctive(key: String) = key.length >= 3 && key !in placeholders && !generic.matches(key)
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase().replace(Regex("""\p{M}"""), "").replace('&', ' ').replace(Regex("""[^\p{L}\p{N}]+"""), "")
    private fun titleKey(value: String) = normalized(OnDemandDatabase.displayTitle(value))
    internal fun episodeKey(value: String, series: String = ""): String {
        var title = value
        val seriesKey = OnDemandDatabase.coreKey(series)
        val number = coordinates.find(value)
        if (seriesKey.isNotBlank() && number != null &&
            OnDemandDatabase.coreKey(value.substring(0, number.range.first)) == seriesKey) {
            // Remove the series decoration as a unit before interpreting (2) as a part.
            // Otherwise "Tulsa King (2022) - S01E01 - Go West, Old Man" retains "part2022".
            title = value.substring(number.range.last + 1)
        } else {
            val seriesWords = OnDemandDatabase.displayTitle(series).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() }
            if (seriesWords.isNotEmpty()) {
                val prefix = Regex("(?iu)^\\s*" + seriesWords.joinToString("[\\s._-]+") { Regex.escape(it) } +
                    """(?:[\s._-]*(?:\((?:19|20)\d{2}\)|(?:19|20)\d{2}))?\s*[-:|.]""").find(value)
                if (prefix != null) title = value.substring(prefix.range.last + 1)
            }
        }
        title = title.replace(coordinates, " ").replace(edition, " ")
        title = title.replace(part) { " part" + (it.groups[1]?.value ?: it.groups[2]?.value) + " " }
        return normalized(title).replace(Regex("""^(?:episode|ep)\d+(?=[a-z])"""), "")
    }
}
