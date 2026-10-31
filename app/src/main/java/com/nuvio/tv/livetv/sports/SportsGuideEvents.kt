package com.nuvio.tv.livetv.sports

/** Combine corroborated airings, retaining every channel and its original programme timing. */
internal object SportsGuideEvents {
    private val pairing = Regex("^([\\p{L}\\p{N} '’-]+?)\\s+(?:v|vs|versus)\\s+([\\p{L}\\p{N} '’-]+)$", RegexOption.IGNORE_CASE)
    private val age = Regex("\\b(?:u|under )\\d{1,2}\\b")
    private fun key(event: SportsEvent): String? {
        if (event.source != EventSource.GUIDE) return null
        val text = SportsCatalog.withoutSmallMarks(event.title + "\n" + event.subtitle.orEmpty())
        val pairs = text.split(':', '\n', '.', '!', '?').mapNotNull { part ->
            pairing.matchEntire(part.trim())?.let { match ->
                listOf(match.groupValues[1], match.groupValues[2]).map { SportsCatalog.norm(it).trim() }
                    .takeIf { it.all { name -> name.length >= 3 } && it.distinct().size == 2 }?.sorted()
            }
        }.distinct()
        if (pairs.size != 1) return null
        val normalized = SportsCatalog.norm(text)
        val category = when {
            SportsCatalog.hasWord(normalized, "wheelchair") -> "wheelchair"
            listOf("women", "womens", "women s").any { SportsCatalog.hasWord(normalized, it) } -> "women"
            else -> "open"
        }
        return listOf(event.leagueId, category, age.findAll(normalized).map { it.value }.distinct().sorted().joinToString(), pairs.single().joinToString("|")).joinToString("|")
    }

    fun merge(events: List<SportsEvent>, protectedIds: Set<String>): List<SportsEvent> {
        val result = ArrayList<SportsEvent>()
        for ((key, candidates) in events.groupBy(::key)) {
            if (key == null) { result += candidates; continue }
            val pending = candidates.sortedWith(compareBy<SportsEvent> { it.startMs }.thenBy { it.id }).toMutableList()
            while (pending.isNotEmpty()) {
                val first = pending.removeAt(0)
                val same = pending.filter { other ->
                    other.startMs - first.startMs <= 90 * 60_000L &&
                        minOf(first.endMs ?: first.startMs, other.endMs ?: other.startMs) > maxOf(first.startMs, other.startMs)
                }
                pending.removeAll(same.toSet())
                val airings = listOf(first) + same
                // Saved picks, blocks, reminders and an open detail page keep their original IDs.
                if (airings.any { it.id in protectedIds }) result += airings
                else result += first.copy(
                    guideHits = airings.flatMap { it.guideHits }.distinctBy { it.first to it.second.startMs },
                    endMs = airings.mapNotNull { it.endMs }.maxOrNull(),
                    state = if (airings.any { it.state == GameState.LIVE }) GameState.LIVE else first.state
                )
            }
        }
        return result.sortedBy { it.startMs }
    }
}
