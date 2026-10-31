package com.nuvio.tv.livetv.sports

import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

/** Curated guide vocabulary; aliases remain data, not additional user-facing categories. */
internal object SportsTaxonomy {
    data class Alias(val term: String, val contextual: Boolean)
    data class Node(val id: String, val label: String, val sportId: String, val parentId: String?,
        val aliases: List<Alias>, val children: List<Node>)

    val roots: List<Node> by lazy {
        val raw = requireNotNull(javaClass.getResourceAsStream("/sports-taxonomy.json"))
            .bufferedReader().use { it.readText() }
        fun read(o: JsonObject, sport: String?, parent: String?): Node {
            val id = o.getValue("id").jsonPrimitive.content
            val label = o.getValue("display").jsonPrimitive.content
            val aliases = o.getValue("synonyms").jsonArray.map { a ->
                Alias(normalize(a.jsonObject.getValue("term").jsonPrimitive.content),
                    a.jsonObject.getValue("match").jsonPrimitive.content == "contextual")
            }
            // A canonical label already listed as contextual must not become a direct match.
            val words = if (aliases.any { it.term == normalize(label) }) aliases
                else aliases + Alias(normalize(label), true)
            return Node(id, label, sport ?: id, parent, words,
                (o["categories"] ?: o["subcategories"])?.jsonArray.orEmpty().map {
                    read(it.jsonObject, sport ?: id, id)
                })
        }
        Json.parseToJsonElement(raw).jsonObject.getValue("sports").jsonArray.map { read(it.jsonObject, null, null) }
    }
    val nodes: List<Node> by lazy {
        fun flatten(n: Node): List<Node> = listOf(n) + n.children.flatMap(::flatten)
        roots.flatMap(::flatten)
    }
    val byId by lazy { nodes.associateBy { it.id } }
    private val index by lazy {
        nodes.flatMap { n -> n.aliases.filter { it.term.isNotEmpty() }.map { it.term.substringBefore(' ') to (n to it) } }
            .groupBy({ it.first }, { it.second })
    }
    private val sharedTerms by lazy {
        nodes.flatMap { n -> n.aliases.map { it.term to n.sportId } }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }.filterValues { it.size > 1 }
    }
    fun normalize(s: String): String = SportsCatalog.normalizedText(s, Normalizer.Form.NFKD).trim()
    private val classifications = com.nuvio.tv.livetv.data.boundedCache<Pair<String, String?>, Node?>(8192)
    fun enabled(id: String, excluded: Set<String>): Boolean {
        if (id in excluded) return false
        var node = byId[id]
        while (node != null) {
            if (node.id in excluded) return false
            node = node.parentId?.let(byId::get)
        }
        return true
    }
    private data class Hit(val node: Node, val alias: Alias, val inTitle: Boolean)
    /** Resolve independently of preferences, so excluded competitions cannot fall back to a broad sport. */
    fun classify(title: String, description: String?): Node? {
        val key = title to description
        synchronized(classifications) {
            if (classifications.containsKey(key)) return classifications[key]
        }
        return classifyUncached(title, description).also { classifications[key] = it }
    }

    private fun classifyUncached(title: String, description: String?): Node? {
        val nt = " " + normalize(title) + " "
        val text = nt + normalize(description.orEmpty()) + " "
        // Test phrases only where their first word occurs, instead of rescanning the full description.
        val found = LinkedHashMap<Pair<String, Alias>, Hit>()
        var start = 0
        while (start < text.length) {
            if (text[start] == ' ') { start++; continue }
            val end = text.indexOf(' ', start).let { if (it < 0) text.length else it }
            for ((node, alias) in index[text.substring(start, end)].orEmpty()) {
                val stop = start + alias.term.length
                if (stop < text.length && text[stop] == ' ' && text.regionMatches(start, alias.term, 0, alias.term.length)) {
                    val key = node.id to alias
                    val inTitle = stop < nt.length
                    if (key !in found || inTitle) found[key] = Hit(node, alias, inTitle)
                }
            }
            start = end + 1
        }
        val hits = found.values.toList()
        var direct = hits.filter { !it.alias.contextual }
        if (direct.isEmpty()) return null
        // A shared competition label cannot outweigh an explicit sport qualifier.
        val ambiguous = direct.filter { it.alias.term in sharedTerms }.map { it.alias.term }.toSet()
        val shared = ambiguous.flatMap { sharedTerms[it].orEmpty() }.toSet()
        val qualified = hits.filter { it.node.sportId in shared && it.alias.term !in ambiguous &&
            (!it.alias.contextual || it.node.parentId == null) }
        if (qualified.map { it.node.sportId }.distinct().size == 1) {
            val sport = qualified.first().node.sportId
            direct = direct.filter { it.alias.term !in ambiguous || it.node.sportId == sport } + qualified
        }
        // Qualified phrases outrank a short generic match; equally strong conflicting sports remain unresolved.
        fun score(h: Hit) = (if (h.inTitle) 10_000 else 0) + h.alias.term.split(' ').size * 100 + h.alias.term.length
        val best = direct.maxOf(::score)
        val sports = direct.filter { score(it) == best }.map { it.node.sportId }.distinct()
        if (sports.size != 1) return null
        val sport = sports.single()
        val evidence = direct.filter { it.node.sportId == sport }
        val confirmed = hits.filter { h -> h.node.sportId == sport && (!h.alias.contextual || evidence.any { e ->
            e.alias.term != h.alias.term && !e.alias.term.contains(h.alias.term) && !h.alias.term.contains(e.alias.term)
        }) }
        fun depth(n: Node): Int = if (n.parentId == null) 0 else 1 + depth(byId.getValue(n.parentId))
        val deepest = confirmed.maxOfOrNull { depth(it.node) } ?: return byId[sport]
        val candidates = confirmed.filter { depth(it.node) == deepest }
        val highest = candidates.maxOf(::score)
        val winners = candidates.filter { score(it) == highest }.map { it.node }.distinctBy { it.id }
        if (winners.size == 1) return winners.single()
        val parents = winners.map { it.parentId }.distinct()
        return if (parents.size == 1) parents.single()?.let(byId::get) else byId[sport]
    }
    /** A unique competition abbreviation plus an explicit fixture pairing is useful EPG evidence.
     * Bare or shared acronyms remain unresolved; this does not broaden generic metadata matching. */
    private val fixturePair = Regex("\\s(?:v|vs\\.?|versus)\\s", RegexOption.IGNORE_CASE)
    fun classifyProgramme(title: String, description: String?): Node? {
        classify(title, description)?.let { return it }
        if (!fixturePair.containsMatchIn(title + " " + description.orEmpty())) return null
        val text = " " + normalize(title + " " + description.orEmpty()) + " "
        val nodes = text.trim().split(' ').distinct().flatMap { word ->
            if (word.length !in 3..6) emptyList() else index[word].orEmpty().filter { (_, a) ->
                a.contextual && a.term == word
            }.map { it.first }
        }.distinctBy { it.id }
        return nodes.singleOrNull()?.takeIf { it.children.isEmpty() }
    }

    fun sport(id: String): Sport = when (id) {
        "cricket" -> Sport.CRICKET
        "rugby-league" -> Sport.RUGBY_LEAGUE
        "rugby-union" -> Sport.RUGBY
        "football-soccer" -> Sport.FOOTBALL
        "basketball" -> Sport.BASKETBALL
        "american-football" -> Sport.AMERICAN_FOOTBALL
        "baseball" -> Sport.BASEBALL
        "ice-hockey" -> Sport.HOCKEY
        "swimming" -> Sport.SWIMMING
        "athletics-track-field" -> Sport.ATHLETICS
        "billiards-cue-sports" -> Sport.SNOOKER
        "gymnastics" -> Sport.GYMNASTICS
        "cycling" -> Sport.CYCLING
        else -> Sport.OTHER
    }
    // Retain the donor dashboard's additional sports without requiring a new taxonomy for them.
    val browseRoots by lazy {
        roots + Sport.entries.filter { s -> s != Sport.OTHER && roots.none { sport(it.id) == s } }.map {
            Node("legacy/" + it.name, it.label, "legacy/" + it.name, null, emptyList(), emptyList())
        }
    }
    val leagueById by lazy { leagues.associateBy { it.id } }
    val leagues by lazy { nodes.map { n -> League("taxonomy/" + n.id, sport(n.sportId), n.label, n.label,
        kind = LeagueKind.EVENT, guideWords = n.aliases.map { it.term }, guideOnly = true) } }
    /** Following a taxonomy sport/category also includes its child competitions. */
    fun includesLeague(followedId: String, candidateId: String): Boolean {
        if (followedId == candidateId) return true
        val parent = when {
            followedId.startsWith("taxonomy/") -> byId[followedId.removePrefix("taxonomy/")]
            followedId.startsWith("guide/") -> roots.firstOrNull { it.id == followedId.removePrefix("guide/") }
            else -> null
        } ?: return false
        val candidate = SportsCatalog.league(candidateId) ?: return false
        if (parent.parentId == null) return candidate.sport == sport(parent.sportId)
        var node = byId[candidateId.removePrefix("taxonomy/")]
            ?: classify(candidate.name, candidate.guideWords.joinToString(" "))
        while (node != null) {
            if (node.id == parent.id) return true
            node = node.parentId?.let(byId::get)
        }
        return false
    }

    fun acceptsEvent(league: League?, title: String, description: String?, excluded: Set<String>): Boolean {
        if (excluded.isEmpty()) return true
        if (league != null && !accepts(league, excluded)) return false
        val node = classify(title, listOfNotNull(description, league?.name).joinToString(" "))
        return node == null || enabled(node.id, excluded)
    }
    fun accepts(league: League, excluded: Set<String>): Boolean {
        if (excluded.isEmpty()) return true
        if ("legacy/" + league.sport.name in excluded) return false
        if (league.id.startsWith("taxonomy/")) return enabled(league.id.removePrefix("taxonomy/"), excluded)
        val root = roots.firstOrNull { sport(it.id) == league.sport } ?: return true
        if (!enabled(root.id, excluded)) return false
        val matched = classify(league.name, league.guideWords.joinToString(" ") + " " + league.sport.label)
        return matched == null || enabled(matched.id, excluded)
    }
}
