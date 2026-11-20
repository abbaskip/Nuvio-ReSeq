package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.livetv.sports.SportsCatalog
import com.nuvio.tv.livetv.sports.SportsEvent

/** Different channels often start their coverage of the same single-series race session a few minutes apart. */
internal object CalendarLiveBroadcasts {
    private data class Session(val series: String, val phase: String, val number: Int?)
    private val series = Regex("""\b(f(?:ormula)? ?(?:1|one) academy|formula one|formula [123]|f[123]|moto ?gp|moto[23])\b""")
    private val practice = Regex("""\b(?:free practice|practice|fp|p) ?([1-4])?\b""")
    private val race = Regex("""\b(?:race|reverse grid)\b(?: ([1-4])\b)?""")
    private val virtual = Regex("""\b(?:virtual|esports|sim racing)\b""")

    private fun session(event: SportsEvent): Session? {
        val title = SportsCatalog.norm(event.title)
        val matches = series.findAll(title).map { it.value }.toList()
        // "F1 Academy" is a separate championship racing on the same weekend as Formula 1.
        val competition = matches.firstOrNull { "academy" in it } ?: matches.firstOrNull() ?: return null
        if (virtual.containsMatchIn(title)) return null
        val id = when (competition) {
            "formula one", "formula 1" -> "f1"
            "formula 2" -> "f2"
            "formula 3" -> "f3"
            "moto gp" -> "motogp"
            else -> if ("academy" in competition) "f1 academy" else competition
        }
        val phase = when {
            "qualifying" in title || "qualification" in title -> if ("sprint" in title) "sprint qualifying" else "qualifying"
            "sprint" in title -> "sprint"
            else -> null
        }
        if (phase != null) return Session(id, phase, null)
        practice.find(title)?.let { return Session(id, "practice", it.groupValues[1].toIntOrNull()) }
        race.find(title)?.let { return Session(id, "race", it.groupValues[1].toIntOrNull()) }
        return null
    }

    fun merge(events: List<SportsEvent>): List<SportsEvent> {
        val result = mutableListOf<SportsEvent>()
        val races = events.mapNotNull { event ->
            val slot = session(event)
            if (slot == null) { result += event; null } else slot to event
        }
        for (sessions in races.groupBy { (slot, _) -> slot.series to slot.phase }.values) {
            val pending = sessions.sortedBy { it.second.startMs }.toMutableList()
            while (pending.isNotEmpty()) {
                val (slot, first) = pending.removeAt(0)
                val neighbours = pending.takeWhile { (_, event) -> event.startMs - first.startMs <= 20 * 60_000L }
                    .filter { (_, event) -> event.startMs < (first.endMs ?: first.startMs) }
                val number = slot.number ?: neighbours.mapNotNull { it.first.number }.distinct().singleOrNull()
                val same = neighbours.filter { (other, _) -> other.number == null || other.number == number }
                if (same.isEmpty()) { result += first; continue }
                pending.removeAll(same.toSet())
                val broadcasts = listOf(slot to first) + same
                val named = broadcasts.minWith(compareBy<Pair<Session, SportsEvent>> { it.first.number == null }
                    .thenBy { it.second.title.length }).second
                result += first.copy(title = named.title, subtitle = named.subtitle,
                    endMs = broadcasts.mapNotNull { it.second.endMs }.maxOrNull(),
                    guideHits = broadcasts.flatMap { it.second.guideHits }.distinctBy { it.first to it.second.startMs })
            }
        }
        return result
    }
}
