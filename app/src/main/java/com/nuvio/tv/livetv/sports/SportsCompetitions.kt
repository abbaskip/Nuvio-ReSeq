package com.nuvio.tv.livetv.sports

/** One browse entry per competition; original provider IDs remain valid for teams and fixtures. */
internal object SportsCompetitions {
    // These built-in provider names omit the sport qualifier used by the curated vocabulary.
    // Match by source ID rather than broadening ambiguous guide phrases such as "Test Match".
    private val sourceNodes = mapOf(
        "rugby/289234" to "rugby-union.international-rugby-union.rugby-union-test-matches",
        "soccer/fifa.friendly" to "football-soccer.international-football.international-friendlies-football"
    )
    private data class Index(val extra: List<League>, val canonical: Map<String, String>, val sources: Map<String, List<League>>)
    @Volatile private var saved: Index? = null

    private fun index(): Index {
        val extra = SportsCatalog.extra
        saved?.takeIf { it.extra === extra }?.let { return it }
        val leaves = SportsTaxonomy.nodes.filter { it.children.isEmpty() && it.parentId != null }
        fun identity(l: League): String {
            val name = SportsTaxonomy.normalize(l.name)
            val sameSport = leaves.filter { SportsTaxonomy.sport(it.sportId) == l.sport }
            val exact = sameSport.filter { SportsTaxonomy.normalize(it.label) == name }
            val node = sourceNodes[l.id]?.let(SportsTaxonomy.byId::get) ?: exact.singleOrNull()
                ?: sameSport.filter { n -> n.aliases.any { it.term == name } }.singleOrNull()
                ?: run {
                    // Shared abbreviations such as WCQ are not competition identities.
                    val words = l.guideWords.map(SportsTaxonomy::normalize).filter { it.length >= 3 }.toSet()
                    sameSport.filter { n -> n.aliases.any { it.term in words } }.singleOrNull()
                }
            return l.sport.name + ":" + (node?.id ?: name)
        }
        val raw = SportsCatalog.leagues + extra + SportsTaxonomy.leagues.filter {
            SportsTaxonomy.byId[it.id.removePrefix("taxonomy/")]?.let { n -> n.children.isEmpty() && n.parentId != null } == true
        }
        val canonical = mutableMapOf<String, String>()
        raw.groupBy(::identity).values.forEach { group ->
            // Keep a real team feed ahead of a guide-only alias, with the built-in ESPN feed first.
            val preferred = group.minBy { when {
                it.espnPath != null -> 0
                it.id.startsWith("tsdb/") && it.kind == LeagueKind.TEAMS -> 1
                !it.id.startsWith("taxonomy/") -> 2
                else -> 3
            } }
            group.forEach { canonical[it.id] = preferred.id }
        }
        SportsTaxonomy.roots.forEach { root ->
            val guide = "guide/" + root.id
            if (SportsCatalog.leagues.any { it.id == guide }) canonical["taxonomy/" + root.id] = canonical[guide] ?: guide
        }
        val sources = (SportsCatalog.leagues + extra + SportsTaxonomy.leagues).groupBy { canonical[it.id] ?: it.id }
        return Index(extra, canonical, sources).also { saved = it }
    }

    fun canonicalId(id: String): String = index().canonical[id] ?: id
    fun same(first: String, second: String): Boolean = canonicalId(first) == canonicalId(second)

    fun sources(id: String): List<League> {
        val index = index()
        return index.sources[index.canonical[id] ?: id].orEmpty()
    }

    fun browse(ordered: List<League>): List<League> = ordered.asSequence().filter { l ->
        !l.id.startsWith("taxonomy/") || SportsTaxonomy.byId[l.id.removePrefix("taxonomy/")]
            ?.let { it.parentId != null && it.children.isEmpty() } == true
    }.mapNotNull { SportsCatalog.league(canonicalId(it.id)) }.distinctBy { it.id }.map { league ->
        // Keep concise curated names (IPL, BBL...) when a team provider uses a longer title.
        val label = if (league.id.startsWith("tsdb/")) sources(league.id).firstOrNull {
            it.id.startsWith("taxonomy/") && SportsTaxonomy.byId[it.id.removePrefix("taxonomy/")]?.children?.isEmpty() == true
        } else null
        if (label == null) league else league.copy(name = label.name, short = label.short)
    }.toList()

    /** Do not convert provider team IDs: the same club has different IDs in different feeds. */
    fun follows(f: SportsFollows): SportsFollows = f.copy(
        teams = f.teams.distinctBy { it.key },
        pins = f.pins.map(::canonicalId).distinct(),
        leagues = f.leagues.map(::canonicalId).toSet(),
        unpinned = f.unpinned.map(::canonicalId).toSet(),
        order = f.order.map { if (it.startsWith("l:")) "l:" + canonicalId(it.removePrefix("l:")) else it }.distinct()
    )

    fun hasTeams(id: String): Boolean = sources(id).any {
        it.kind == LeagueKind.TEAMS && (it.espnPath != null || it.id.startsWith("tsdb/"))
    }

    /** Merge complete provider lists and retain followed teams even when a partial feed omits them. */
    fun teams(id: String, loaded: List<TeamInfo>, follows: SportsFollows): List<TeamInfo> {
        val followed = follows.teams.filter { same(it.leagueId, id) }
        val retained = followed.map { TeamInfo(it.leagueId, it.teamId, it.name, it.name, it.abbr, null, it.color, null, it.logo) }
        val keys = followed.map { it.key }.toSet()
        return (loaded + retained).distinctBy { it.key }.sortedByDescending { it.key in keys }
            .distinctBy { SportsTaxonomy.normalize(it.fullName) }.sortedBy { it.fullName.lowercase() }
    }
}
