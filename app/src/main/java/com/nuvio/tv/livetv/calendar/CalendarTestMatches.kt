package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.sports.*
import java.time.ZoneId

/** Test match coverage moves between channels and sessions during a local calendar day. */
internal object CalendarTestMatches {
    data class Match(val teams: List<String>, val squad: String, val number: Int?) {
        fun compatible(other: Match) = teams == other.teams && squad == other.squad &&
            (number == null || other.number == null || number == other.number)

        fun title(): String {
            val ordinal = number?.let { listOf("", "1st ", "2nd ", "3rd ", "4th ", "5th ", "6th ")[it] }.orEmpty()
            val division = if (squad == "women") "Women’s " else ""
            return teams.joinToString(" v ") + " · " + division + ordinal + "Test"
        }
    }

    private val countries = InternationalCricket.teams.associate { it.name.lowercase() to it.name } + mapOf(
        "nz" to "New Zealand", "nzl" to "New Zealand", "ind" to "India", "aus" to "Australia",
        "eng" to "England", "sa" to "South Africa", "rsa" to "South Africa", "wi" to "West Indies",
        "sl" to "Sri Lanka", "pak" to "Pakistan", "ban" to "Bangladesh", "afg" to "Afghanistan",
        "ire" to "Ireland", "zim" to "Zimbabwe")
    private val team = countries.keys.joinToString("|", "(?:", ")")
    private val pairing = Regex("""\b($team)(?: women(?: s)?)? (?:v|vs|versus|against|at|and|take on|takes on|host|hosts|face|faces|tour of) ($team)\b""")
    private val test = Regex("""\btests?\b""")
    private val otherFormat = Regex("""\b(?:odi|t20i?|t10|one day|twenty20)\b""")
    private val otherSquad = Regex("""\b(?:under ?\d+|u ?\d+|youth|xi|legends)\b|\b$team a\b""")
    private val women = Regex("""\b(?:women|womens|ladies)\b""")
    private val ordinal = Regex("""\b(1st|2nd|3rd|4th|5th|6th|first|second|third|fourth|fifth|sixth) test\b""")
    private val numbers = listOf("first", "second", "third", "fourth", "fifth", "sixth")
    private val generic = Regex("""(?:live )?(?:test cricket|cricket test|test match)(?: live)?""")
    private val dayNumber = Regex("""\bday ([1-6])\b""")

    fun anonymousTest(air: SportsEvent, league: League?) = league?.sport == Sport.CRICKET &&
        generic.matches(SportsTaxonomy.normalize(air.title))

    private fun number(text: String) = ordinal.find(text)?.groupValues?.get(1)?.let {
        it.first().digitToIntOrNull() ?: (numbers.indexOf(it) + 1)
    }

    private fun identify(title: String, description: String?, league: League, sides: List<String> = emptyList()): Match? {
        val headline = SportsTaxonomy.normalize(title)
        val context = SportsTaxonomy.normalize(description.orEmpty())
        val competition = SportsTaxonomy.normalize(league.name)
        val text = "$headline $context"
        if (!test.containsMatchIn("$text $competition") || otherFormat.containsMatchIn(headline) ||
            otherSquad.containsMatchIn("$text $competition")) return null
        fun pairs(s: String) = pairing.findAll(s).map { m ->
            listOf(countries.getValue(m.groupValues[1]), countries.getValue(m.groupValues[2])).sorted()
        }.distinct().toList()
        val names = if (sides.size == 2) sides.sorted() else pairs(headline).ifEmpty { pairs(context) }.singleOrNull() ?: return null
        if (names[0] == names[1]) return null
        return Match(names, if (league.women || women.containsMatchIn(text)) "women" else "open", number(text))
    }

    fun fixture(event: SportsEvent, league: League?): Match? {
        if (league?.sport != Sport.CRICKET) return null
        val sides = event.sides.mapNotNull { side ->
            InternationalCricket.country(side.team.fullName.ifBlank { side.team.name })?.let { id ->
                InternationalCricket.teams.firstOrNull { it.id == id }?.name
            }
        }
        return identify(event.title, event.subtitle, league, sides)
    }

    private data class Broadcast(val event: SportsEvent, val hit: Pair<String, EpgProgram>, val match: Match)
    private data class Day(val teams: List<String>, val squad: String, val date: Long)

    class Fixtures(events: List<SportsEvent>, val zone: ZoneId, leagueOf: (SportsEvent) -> League?) {
        private data class Entry(val event: SportsEvent, val match: Match, val first: Long, val last: Long)
        private val entries = mutableListOf<Entry>()
        private val aliases = mutableMapOf<String, SportsEvent>()

        init {
            for (event in events.distinctBy { it.id }) {
                val match = fixture(event, leagueOf(event)) ?: continue
                val first = event.startMs.calendarDay(zone)
                val known = entries.firstOrNull { it.match.compatible(match) && kotlin.math.abs(it.first - first) <= 1 }
                if (known != null) aliases[event.id] = known.event
                else {
                    val last = event.endMs?.minus(1)?.calendarDay(zone)
                    entries += Entry(event, match, first, if (last != null && last > first) last else first + 4)
                }
            }
        }

        fun canonical(event: SportsEvent) = aliases[event.id] ?: event

        fun liveSession(p: EpgProgram, event: SportsEvent): Boolean {
            val label = dayNumber.find(SportsTaxonomy.normalize(p.title + " " + p.description.orEmpty()))
                ?.groupValues?.get(1)?.toLong()
            val opening = java.time.Instant.ofEpochMilli(event.startMs).atZone(zone)
            val firstDay = opening.toLocalDate().toEpochDay()
            val date = p.startMs.calendarDay(zone)
            if (!event.hasTime) return label == null || label == date - firstDay + 1
            // A late session can cross midnight in the viewer's zone. Apply the same local
            // opening time on each match day, allowing for early starts and coverage.
            return (date - 1..date + 1).any { day ->
                val offset = day - firstDay
                val start = opening.plusDays(offset).toInstant().toEpochMilli()
                offset in 0..5 && (label == null || label == offset + 1) &&
                    p.startMs in (start - 90 * 60_000L) until (start + 10 * 3_600_000L) &&
                    p.stopMs > start - 30 * 60_000L
            }
        }

        fun named(air: SportsEvent, league: League?): SportsEvent? {
            val match = fixture(air, league) ?: return null
            val day = air.startMs.calendarDay(zone)
            return entries.singleOrNull { day in it.first..it.last && it.match.compatible(match) }?.event
        }

        /** A title such as "Test Cricket" needs an independently corroborated fixture and match day. */
        fun unnamed(air: SportsEvent, league: League?, corroborated: Set<String>): SportsEvent? {
            if (league == null || !anonymousTest(air, league)) return null
            val day = air.startMs.calendarDay(zone)
            val hits = air.guideHits.map { (_, p) ->
                val text = SportsTaxonomy.normalize(p.title + " " + p.description.orEmpty())
                val dayOfTest = dayNumber.find(text)?.groupValues?.get(1)?.toInt() ?: return null
                if (otherFormat.containsMatchIn(text) || otherSquad.containsMatchIn(text)) return null
                val squad = if (league.women || women.containsMatchIn(text)) "women" else "open"
                val n = number(text)
                val possible = entries.filter { day in it.first..it.last && it.match.squad == squad &&
                    (n == null || it.match.number == null || n == it.match.number) }
                (possible.singleOrNull() ?: possible.singleOrNull { day - it.first + 1 == dayOfTest.toLong() })?.event ?: return null
            }
            return hits.distinctBy { it.id }.singleOrNull()?.takeIf { it.id in corroborated }
        }
    }

    fun merge(airings: List<SportsEvent>, zone: ZoneId, leagueOf: (SportsEvent) -> League?): List<SportsEvent> {
        val result = mutableListOf<SportsEvent>()
        val groups = mutableMapOf<Day, MutableList<Broadcast>>()
        for (air in airings) {
            val league = leagueOf(air)
            if (league?.sport != Sport.CRICKET) { result += air; continue }
            val unknown = mutableListOf<Pair<String, EpgProgram>>()
            for (hit in air.guideHits) {
                val p = hit.second
                val match = identify(p.title, p.description, league)
                if (match == null) { unknown += hit; continue }
                val day = Day(match.teams, match.squad, p.startMs.calendarDay(zone))
                groups.getOrPut(day) { mutableListOf() }.add(Broadcast(air, hit, match))
            }
            if (unknown.isNotEmpty()) result += if (unknown.size == air.guideHits.size) air else air.copy(guideHits = unknown)
        }
        for ((day, parts) in groups) {
            val singleNumber = parts.mapNotNull { it.match.number }.distinct().singleOrNull()
            for ((number, slots) in parts.groupBy { it.match.number ?: singleNumber }) {
                val first = slots.minBy { it.hit.second.startMs }
                val match = first.match.copy(number = number)
                val hits = slots.map { it.hit }.distinctBy { it.first to it.second.startMs }.sortedBy { it.second.startMs }
                val id = listOf("guide:test", day.teams.joinToString("-"), day.squad, number ?: "test", day.date).joinToString(":")
                result += first.event.copy(id = id, title = match.title(), startMs = hits.first().second.startMs,
                    endMs = hits.maxOf { it.second.stopMs }, guideHits = hits,
                    state = if (slots.any { it.event.state == GameState.LIVE }) GameState.LIVE else first.event.state)
            }
        }
        return result
    }
}
