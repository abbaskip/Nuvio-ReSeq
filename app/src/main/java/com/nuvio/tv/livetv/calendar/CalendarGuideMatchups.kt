package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.livetv.sports.*

/** Reconcile independently worded TV listings without giving guide teams provider IDs. */
internal object CalendarGuideMatchups {
    data class Card(val teams: List<String>, val competition: String) {
        fun sides(leagueId: String) = teams.map { name ->
            val abbr = name.split(' ').joinToString("") { it.take(1) }.let { if (it.length == 1) name.take(3) else it }.uppercase()
            Side(TeamInfo(leagueId, SportsTaxonomy.normalize(name), name, name, abbr, null, null, null, null),
                null, null, false, false)
        }
    }
    data class Listing(val event: SportsEvent, val card: Card?)
    private data class Pairing(val names: List<String>, val tokens: List<List<String>>, val competition: String,
        val leagueKey: String, val division: String, val game: String)
    private data class Airing(val event: SportsEvent, val pairing: Pairing)

    private val separator = Regex("""\s+(?:v\.?|vs\.?|versus|at|@)\s+""", RegexOption.IGNORE_CASE)
    private val segments = Regex("""\s+[-–—|]\s+|[:;|\n]|[.!?](?=\s+[A-Z]|$)""")
    private val rank = Regex("""^(?:#\d+|No\.?\s*\d+|\(\d+\))\s+""", RegexOption.IGNORE_CASE)
    private val live = Regex("""^\s*(?:\(?live\)?(?:\s+coverage\s+of)?|live\s+from)\s+|\s+\(?live\)?$""", RegexOption.IGNORE_CASE)
    private val round = Regex("""\b(?:game|match|leg|round|session)\s*#?\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    private val gameSuffix = Regex("""\s*[(,\[]?\s*\b(?:game|match|leg|round|session)\s*#?\s*\d+\s*[)\]]?\s*$""", RegexOption.IGNORE_CASE)
    private val badName = Regex("""\b(?:coverage|from|hosts?|takes?|watch|live|action|highlights|championship|tournament|semi ?final|quarter ?final|presented|tonight|venue)\b""")
    private val division = Regex("""\b(?:women(?: s|s)?|girls?|boys?|wheelchair|under ?\d+|u ?\d+|junior varsity|varsity|jv|freshman|reserves)\b""")
    private val generic = setOf("university","college","state","school","high","north","south","east","west","central","saint","city","fc","hs","the")
    private val distinctQualifiers = setOf("state", "tech", "a", "b", "ii", "juniors", "reserves", "united", "city", "county",
        "north", "south", "east", "west", "central")
    private val initials = Regex("""\b(?:[A-Za-z]\.){2,}""")
    private val dottedVersus = Regex("""\b(vs|v)\.(?=\s)""", RegexOption.IGNORE_CASE)
    private val dottedWord = Regex("""\b(St|Mt|Ft|No)\.""", RegexOption.IGNORE_CASE)
    private val saint = Regex("""\bst(?= )""")
    private val level = Regex("""\b(?:ncaa|college|high school)\b""", RegexOption.IGNORE_CASE)
    private val stage = Regex("""\b(?:quarter ?final|semi ?final|grand final|final|third place)\b""")
    private val venue = Regex("""\b(?:stadium|arena|grounds?|venue|oval|racecourse)\b""")
    private val unknownTeams = Regex("""(?i)\b(?:teams? (?:tba|tbd|to be (?:announced|decided|confirmed))|tba|tbd)\b""")
    private val competitionSuffix = Regex("""(?i)\s+(?:league|cricket)$""")
    private val championshipFinal = Regex("""(?i)^(?:grand |championship )?final$""")
    private fun words(name: String) = SportsTaxonomy.normalize(name)
        .replace(saint, "saint").removeSuffix(" high school").removeSuffix(" hs").split(' ')

    private fun names(text: String, league: League): List<List<String>> {
        val clean = initials.replace(text) { it.value.replace(".", "") }
            .replace(dottedVersus, "$1").replace(dottedWord, "$1")
        return clean.split(segments).mapNotNull { segment ->
            val parts = gameSuffix.replace(segment, "").trim().removeSurrounding("(", ")").split(separator)
            if (parts.size != 2) return@mapNotNull null
            val pair = parts.map { part ->
                var side = live.replace(part.trim(), "").trim()
                for (prefix in listOf(league.name, league.short)) {
                    if (prefix.isNotBlank() && side.startsWith(prefix + " ", true)) side = side.drop(prefix.length).trim()
                }
                rank.replace(side, "").trim(' ', '.', ',', '(', ')')
            }
            pair.takeIf { values -> values.distinct().size == 2 && values.all { name ->
                val tokens = words(name)
                name.length in 2..60 && name.any(Char::isLetter) && tokens.size <= 7 &&
                    tokens.any { it !in generic } && name != league.name && !name.equals(league.sport.label, true) &&
                    !badName.containsMatchIn(tokens.joinToString(" ")) && !venue.containsMatchIn(tokens.joinToString(" "))
            } }
        }
    }

    private fun fuzzy(a: List<String>, b: List<String>): Boolean {
        if (a == b) return true
        val (short, long) = if (a.size <= b.size) a to b else b to a
        if (short.size >= long.size) return false
        if (short.size == 1 && short[0].length in 2..5) {
            val acronym = long.filterNot { it == "of" || it == "the" }.joinToString("") { it.take(1) }
            if (short[0] == acronym || short[0] == "u$acronym") return true
        }
        if (short.none { it.length >= 3 && it !in generic }) return false
        val extra = when {
            long.take(short.size) == short -> long.drop(short.size)
            long.takeLast(short.size) == short -> long.dropLast(short.size)
            else -> return false
        }
        return extra.none { it in distinctQualifiers }
    }

    private fun same(a: List<List<String>>, b: List<List<String>>) =
        fuzzy(a[0], b[0]) && fuzzy(a[1], b[1]) || fuzzy(a[0], b[1]) && fuzzy(a[1], b[0])

    private fun parse(event: SportsEvent, league: League?): Pairing? {
        if (league == null || event.id.startsWith("guide:test:")) return null
        val title = event.title
        val pairs = names(title, league).ifEmpty { names(event.subtitle.orEmpty(), league) }
        val first = pairs.firstOrNull() ?: return null
        val tokens = first.map(::words)
        if (pairs.drop(1).any { !same(tokens, it.map(::words)) }) return null
        val text = SportsTaxonomy.normalize(title + " " + event.subtitle.orEmpty())
        val divisions = division.findAll(text).map { it.value.replace("under ", "u").replace("under", "u").replace("u ", "u")
            .replace("junior varsity", "jv").let { value -> when {
                value.startsWith("women") || value.startsWith("girl") -> "women"
                value.startsWith("boy") -> "men"
                else -> value
            } } }.toMutableSet()
        if (league.women) divisions += "women"
        val node = SportsTaxonomy.classifyProgramme(title, event.subtitle)?.takeIf { it.parentId != null }
        if (node?.aliases?.any { it.term.startsWith("women s ") || it.term.startsWith("womens ") } == true) divisions += "women"
        val heading = title.split(':', '|').map { it.trim() }.firstOrNull {
            it.length in 4..48 && level.containsMatchIn(it) && !separator.containsMatchIn(it) }
        val competition = heading?.let { live.replace(it, "") } ?: node?.label ?: league.name
        level.find(text)?.let { divisions += if (it.value == "high school") "school" else "college" }
        val leagueKey = node?.id ?: SportsCompetitions.canonicalId(league.id)
        return Pairing(first, tokens, competition, leagueKey, divisions.sorted().joinToString(),
            (round.findAll(text) + stage.findAll(SportsTaxonomy.normalize(title))).map { SportsTaxonomy.normalize(it.value) }
                .distinct().sorted().joinToString())
    }

    fun merge(events: List<SportsEvent>, leagueOf: (SportsEvent) -> League?): List<Listing> {
        val result = mutableListOf<Listing>()
        val active = mutableMapOf<List<String>, MutableList<MutableList<Airing>>>()
        val games = mutableListOf<MutableList<Airing>>()
        for (event in events.sortedWith(compareBy<SportsEvent> { it.startMs }.thenBy { it.id })) {
            val league = leagueOf(event)
            val pair = parse(event, league)
            if (pair == null) { result += Listing(event, null); continue }
            val groups = active.getOrPut(listOf(league!!.sport.name, pair.leagueKey, pair.division, pair.game)) { mutableListOf() }
            groups.removeAll { event.startMs - it.first().event.startMs > 90 * 60_000L }
            val matches = groups.filter { group ->
                group.all { entry ->
                    event.startMs < (entry.event.endMs ?: entry.event.startMs) && same(pair.tokens, entry.pairing.tokens)
                }
            }
            val group = matches.singleOrNull() ?: mutableListOf<Airing>().also { groups.add(it); games.add(it) }
            group += Airing(event, pair)
        }
        for (group in games) {
            val first = group.first().event
            val best = group.maxBy { it.pairing.names.sumOf(String::length) }
            val hits = group.flatMap { it.event.guideHits }.distinctBy { it.first to it.second.startMs }
            val competition = listOf(best.pairing.competition, best.pairing.game.replaceFirstChar { it.uppercase() })
                .filter { it.isNotEmpty() }.joinToString(" · ")
            result += Listing(first.copy(title = best.event.title, subtitle = best.event.subtitle, guideHits = hits,
                endMs = group.mapNotNull { it.event.endMs }.maxOrNull()), Card(best.pairing.names, competition))
        }
        return mergeFinals(result, leagueOf)
    }

    /** A single championship final can have a confirmed start before the finalists are named. */
    private fun mergeFinals(listings: List<Listing>, leagueOf: (SportsEvent) -> League?): List<Listing> {
        data class Final(val listing: Listing, val competition: String)
        val result = mutableListOf<Listing>()
        val candidates = mutableMapOf<List<String>, MutableList<Final>>()
        for (listing in listings) {
            val event = listing.event
            val parts = event.title.split(segments).map { live.replace(it.trim(), "").trim() }.filter { it.isNotEmpty() }
            val index = parts.indexOfFirst { championshipFinal.matches(it) }
            val league = leagueOf(event)
            if (index < 1 || league == null || parts.drop(index + 1).any { part ->
                    !unknownTeams.containsMatchIn(part) && listing.card == null }) {
                result += listing
                continue
            }
            val competition = parts[index - 1].replace(competitionSuffix, "").trim()
            val key = SportsTaxonomy.normalize(competition)
            if (key.split(' ').size < 2 || key == SportsTaxonomy.normalize(league.sport.label) ||
                separator.containsMatchIn(competition) || level.matches(key)) {
                result += listing
                continue
            }
            val text = SportsTaxonomy.normalize(event.title + " " + event.subtitle.orEmpty())
            val bracket = division.findAll(text).map { it.value }.distinct().sorted().joinToString()
            val number = round.find(text)?.value.orEmpty()
            candidates.getOrPut(listOf(league.sport.name, key, bracket, number)) { mutableListOf() }
                .add(Final(listing, competition))
        }
        for (finals in candidates.values) {
            val pending = finals.sortedBy { it.listing.event.startMs }.toMutableList()
            while (pending.isNotEmpty()) {
                val first = pending.removeAt(0)
                val start = first.listing.event.startMs
                val same = pending.takeWhile { it.listing.event.startMs - start <= 30 * 60_000L &&
                    it.listing.event.startMs < (first.listing.event.endMs ?: start) }
                pending.subList(0, same.size).clear()
                val group = listOf(first) + same
                val named = group.filter { it.listing.card != null }
                if (same.isEmpty() || named.size > 1) {
                    result += group.map { it.listing }
                    continue
                }
                val best = named.firstOrNull() ?: first
                val hits = group.flatMap { it.listing.event.guideHits }.distinctBy { it.first to it.second.startMs }
                val event = best.listing.event.copy(startMs = start, endMs = group.mapNotNull { it.listing.event.endMs }.maxOrNull(),
                    title = best.listing.card?.teams?.joinToString(" v ") ?: best.competition + " Final", guideHits = hits)
                result += Listing(event, best.listing.card ?: Card(emptyList(), best.competition))
            }
        }
        return result
    }
}
