package com.nuvio.tv.data.tvdb

private val extendedLabel = Regex("\\s*\\((?:extended(?: cut| edition)?|superfan(?: episode)?)\\)\\s*$", RegexOption.IGNORE_CASE)
private val partLabel = Regex("^(.*?)\\s*(?:\\((\\d+)\\)|[-:]?\\s*Part\\s+(\\d+))$", RegexOption.IGNORE_CASE)
private val titleArticle = Regex("\\bthe\\b")
private val titleSpaces = Regex("\\s+")

private fun matchStem(title: String): String = normalizeEpisodeName(title)
    .replace(titleArticle, " ").replace(titleSpaces, " ").trim()

internal fun tvdbEpisodeMatchTitle(title: String): String {
    val cut = title.replace(extendedLabel, "").trim()
    val part = partLabel.matchEntire(cut)
    return if (part == null) matchStem(cut) else matchStem(part.groupValues[1]) + " part " +
        part.groupValues[2].ifBlank { part.groupValues[3] }
}

internal fun tvdbEpisodeBaseTitle(title: String): String {
    val cut = title.replace(extendedLabel, "").trim()
    return matchStem(partLabel.matchEntire(cut)?.groupValues?.get(1) ?: cut)
}

internal fun tvdbEpisodePartNumber(title: String): Int? {
    val part = partLabel.matchEntire(title.replace(extendedLabel, "").trim()) ?: return null
    return part.groupValues[2].ifBlank { part.groupValues[3] }.toIntOrNull()
}

internal fun tvdbEpisodeFamilies(episodes: List<TvdbEpisode>): List<List<TvdbEpisode>> = episodes
    .filter { tvdbEpisodePartNumber(it.title) != null }
    .groupBy { tvdbEpisodeBaseTitle(it.title) to it.coordinates.season }.values.mapNotNull { rows ->
        val sorted = rows.sortedBy { tvdbEpisodePartNumber(it.title) }
        sorted.takeIf { it.size > 1 && it.map { row -> tvdbEpisodePartNumber(row.title) } == (1..it.size).toList() }
    }

/** A bounded metadata correspondence, not a second history store. Complete part
 * families establish content boundaries; display numbers never establish them. */
internal class TvdbContentCoverage(order: TvdbEpisodeOrder) {
    val records = (order.officialEpisodes + order.episodes + order.relatedOrders.toSortedMap().values.flatten()).distinctBy { it.id }
    private val lists = listOf(order.officialEpisodes) +
        order.relatedOrders.toSortedMap(compareBy<String> { if (it == "alternate") "0" else it }).values + listOf(order.episodes)
    private val allFamilies = lists.flatMap(::tvdbEpisodeFamilies)
        .filter { !it.first().aired.isNullOrBlank() }
    private val anchors = allFamilies.flatMap { family -> family.map { it.id to
        Triple(tvdbEpisodeBaseTitle(family.first().title), family.first().aired?.take(10), family.size) } }.groupBy({ it.first }, { it.second })
    private val families = allFamilies
        .distinctBy { tvdbEpisodeBaseTitle(it.first().title) to it.first().aired?.take(10) }
    private val familiesByTitle = families.groupBy { tvdbEpisodeBaseTitle(it.first().title) }
    private val official = order.officialEpisodes.associateBy { it.id }
    private val scalar = tvdbEpisodeCorrespondence(order)
    val byId: Map<Long, List<TvdbEpisode>> = records.mapNotNull { row ->
        val family = familiesByTitle[tvdbEpisodeBaseTitle(row.title)].orEmpty().filter {
            (it.any { part -> part.id == row.id } ||
                (tvdbEpisodePartNumber(row.title) == null && it.first().aired?.take(10) == row.aired?.take(10)) ||
                anchors[row.id].orEmpty().contains(Triple(tvdbEpisodeBaseTitle(it.first().title), it.first().aired?.take(10), it.size))) }.singleOrNull()
        val coverage = if (family != null) {
            val number = tvdbEpisodePartNumber(row.title)
            if (number == null) family else family.singleOrNull { tvdbEpisodePartNumber(it.title) == number }?.let(::listOf)
        } else official[row.id]?.let(::listOf) ?: scalar[row.id]?.let(::listOf)
        coverage?.let { row.id to it }
    }.toMap()

    private val byTitleDate = records.groupBy { tvdbEpisodeMatchTitle(it.title) to it.aired?.take(10) }

    fun match(title: String, date: String?): List<TvdbEpisode> {
        if (date.isNullOrBlank()) return emptyList()
        val signature = tvdbEpisodeMatchTitle(title)
        return byTitleDate[signature to date.take(10)].orEmpty()
            .mapNotNull { byId[it.id] }.distinctBy { it.map(TvdbEpisode::id).toSet() }.singleOrNull().orEmpty()
    }
}

/** Stable IDs lead. Variant names additionally require a unique official title/date
 * anchor; numbered parts require a complete 1..N group, not positional matching. */
internal fun tvdbEpisodeCorrespondence(order: TvdbEpisodeOrder): Map<Long, TvdbEpisode> {
    val official = order.officialEpisodes.groupBy { it.id }
    val byTitle = order.officialEpisodes.groupBy { tvdbEpisodeMatchTitle(it.title) }
    val result = order.episodes.mapNotNull { episode -> official[episode.id]?.singleOrNull()?.let { episode.id to it } }.toMap().toMutableMap()
    val parts = mutableListOf<Triple<TvdbEpisode, String, Int>>()
    for (episode in order.episodes) {
        if (episode.id in result) continue
        val title = episode.title.replace(extendedLabel, "").trim()
        val exact = byTitle[tvdbEpisodeMatchTitle(title)].orEmpty().filter {
            !it.aired.isNullOrBlank() && it.aired.take(10) == episode.aired?.take(10)
        }.singleOrNull()
        if (exact != null) { result[episode.id] = exact; continue }
        val part = partLabel.matchEntire(title)
        if (part != null) {
            val number = (part.groupValues[2].ifBlank { part.groupValues[3] }).toIntOrNull() ?: continue
            parts += Triple(episode, matchStem(part.groupValues[1]), number)
        }
    }
    for (group in parts.groupBy { it.second to it.first.coordinates.season }.values) {
        val sorted = group.sortedBy { it.third }
        if (sorted.size < 2 || sorted.map { it.third } != (1..sorted.size).toList()) continue
        val anchor = sorted.first().first.aired?.take(10) ?: continue
        val canonical = byTitle[sorted.first().second].orEmpty().filter { it.aired?.take(10) == anchor }.singleOrNull() ?: continue
        sorted.forEach { result[it.first.id] = canonical }
    }
    return result
}
