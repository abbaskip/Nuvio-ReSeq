package com.nuvio.tv.mobile

import android.net.Uri
import android.os.Bundle
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.livetv.data.LiveTvPosterResolver
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.LiveTvSearchBridge

private val seasonEpisode = Regex("""(?i)\b(?:s(?:eason)?\s*(\d{1,3})[\s.,:/-]*e(?:p(?:isode)?)?\.?\s*(\d{1,4})|(\d{1,3})x(\d{1,4}))\b""")
private val separators = Regex("""[^\p{L}\p{N}]+""")
private val releaseYear = Regex("""\b(?:19|20)\d{2}\b""")
private fun titleKey(text: String) = text.lowercase().replace(separators, " ").trim()

internal data class GuideLookup(val query: String, val type: String, val year: String, val number: String, val subtitle: String) {
    fun route() = "guide-find?query=" + Uri.encode(query) + "&kind=" + type + "&year=" + year +
        "&number=" + Uri.encode(number) + "&subtitle=" + Uri.encode(subtitle)

    companion object {
        const val pattern = "guide-find?query={query}&kind={kind}&year={year}&number={number}&subtitle={subtitle}"

        fun from(args: Bundle) = GuideLookup(args.getString("query").orEmpty(), args.getString("kind").orEmpty(),
            args.getString("year").orEmpty(), args.getString("number").orEmpty(), args.getString("subtitle").orEmpty())

        fun from(program: EpgProgram, channel: LiveChannel): GuideLookup {
            val raw = LiveTvPosterResolver.searchTitleFor(program, channel)
            val show = LiveTvSearchBridge.cleanTitle(raw).ifBlank { raw.trim() }
            val marker = seasonEpisode.find(program.episode.orEmpty()) ?: seasonEpisode.find(program.title)
            val hint = LiveTvPosterResolver.typeHint(program, channel)
            val type = when {
                marker != null -> "series"
                hint?.strong == true -> if (hint.kind == LiveTvPosterResolver.Kind.MOVIE) "movie" else "series"
                else -> ""
            }
            val episodeTitle = if (raw != program.title) program.title.removePrefix(raw).trimStart(' ', ':', '-', '–', '—')
                else program.episode?.takeIf { marker == null && it.any(Char::isLetter) }.orEmpty()
            return GuideLookup(show, type, (program.year ?: LiveTvSearchBridge.yearInTitle(program.title))?.toString().orEmpty(),
                marker?.value.orEmpty(), LiveTvSearchBridge.cleanTitle(episodeTitle))
        }
    }

    fun matchesTitle(item: MetaPreview) = titleKey(item.name) == titleKey(query)

    fun exactMatch(rows: List<CatalogRow>): Pair<MetaPreview, String?>? {
        val matches = rows.flatMap { row -> row.items.map { it to row.addonBaseUrl } }.filter { (item, _) ->
            matchesTitle(item) && (type.isEmpty() || type == item.apiType ||
                type == "series" && item.apiType in setOf("tv", "anime")) &&
                (year.isEmpty() || releaseYear.find(item.releaseInfo.orEmpty())?.value == year)
        }
        return matches.distinctBy { (item, _) -> item.apiType to (item.imdbId ?: item.id) }.singleOrNull()
    }
}

internal fun guideEpisode(videos: List<Video>, number: String, subtitle: String): Video? {
    val marker = seasonEpisode.find(number)
    val season = marker?.groupValues?.let { it[1].ifEmpty { it[3] }.toIntOrNull() }
    val episode = marker?.groupValues?.let { it[2].ifEmpty { it[4] }.toIntOrNull() }
    val numbered = if (season == null || episode == null) emptyList() else videos.filter { video ->
        val identity = video.episodeIdentity
        if (identity == null) video.season == season && video.episode == episode
        else identity.aired?.let { it.season == season && it.episode == episode } == true
    }
    val named = if (subtitle.isBlank()) emptyList() else videos.filter { titleKey(it.title) == titleKey(subtitle) }
    return numbered.singleOrNull() ?: numbered.filter { it in named }.singleOrNull() ?: named.singleOrNull()
}
