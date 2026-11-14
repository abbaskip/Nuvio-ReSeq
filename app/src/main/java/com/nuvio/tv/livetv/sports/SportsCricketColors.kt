package com.nuvio.tv.livetv.sports

/** Country identities are independent of provider IDs, gender and age-group teams. */
internal object SportsCricketColors {
    private val colors = mapOf(
        "india" to 0xFF5BA4E6L,
        "england" to 0xFF2350A8L,
        "sri lanka" to 0xFF12307AL,
        "afghanistan" to 0xFF0072CEL,
        "south africa" to 0xFF317873L,
        "west indies" to 0xFF7B1E3AL,
        "new zealand" to 0xFF6E747BL,
        "bangladesh" to 0xFF006A4EL,
        "pakistan" to 0xFF00A651L,
        "ireland" to 0xFF169B62L,
        "zimbabwe" to 0xFFD40000L,
        "netherlands" to 0xFFFF6F00L,
        "scotland" to 0xFF3B2F7AL,
        "nepal" to 0xFFDC143CL,
        "usa" to 0xFF0A3161L,
        "namibia" to 0xFF003580L,
        "uae" to 0xFFA6192EL,
        "oman" to 0xFFC8102EL,
        "canada" to 0xFFE31837L
    )
    private val aliases = mapOf(
        "australian" to "australia", "aussies" to "australia", "southern stars" to "australia",
        "england lions" to "england",
        "kiwis" to "new zealand", "black caps" to "new zealand", "blackcaps" to "new zealand",
        "white ferns" to "new zealand", "windies" to "west indies", "proteas" to "south africa",
        "united states" to "usa", "united states of america" to "usa",
        "united arab emirates" to "uae", "holland" to "netherlands"
    )
    private val qualifiers = Regex(
        "\\b(?:women s|men s|women|womens|men|mens|ladies|a|u\\s*19s?|under\\s*19s?|" +
        "national|cricket|team|tests?|matches|odis?|one day|t20is?|t20s?|twenty20|internationals?)\\b"
    )
    private val t20 = Regex("\\b(?:t20i?s?|wt20|twenty20|twenty 20)\\b")
    private val test = Regex("\\b(?:tests?|wtc|ashes)\\b")
    private val odi = Regex("\\b(?:odis?|one day|50 overs?|world cup|champions trophy)\\b")

    fun color(
        team: TeamInfo?,
        league: League? = team?.let { SportsCatalog.league(it.leagueId) },
        matchFormat: String = ""
    ): Long? {
        if (team == null || league?.sport != Sport.CRICKET) return null
        // Exact remaining names prevent clubs (e.g. South Australia) inheriting a nation's color.
        val country = listOf(team.fullName, team.name).firstNotNullOfOrNull { name ->
            val plain = SportsTaxonomy.normalize(name.replace("'", "").replace("\u2019", ""))
                .replace(qualifiers, " ").trim().replace(Regex(" +"), " ")
            (aliases[plain] ?: plain).takeIf { it == "australia" || it in colors }
        } ?: return null
        if (country != "australia") return colors[country]
        // A bilateral match's explicit format takes precedence over its series label.
        australiaColor(SportsTaxonomy.normalize(matchFormat))?.let { return it }
        // Use competition and team format labels, never the provider's team ID or supplied color.
        val format = SportsTaxonomy.normalize(
            listOf(league.id, league.name, league.short, team.fullName, team.name).joinToString(" ")
        )
        return australiaColor(format) ?: if (league.id == "cricket/19430") 0xFF0A4A2AL else null
    }

    private fun australiaColor(format: String): Long? = when {
        t20.containsMatchIn(format) -> 0xFF0B4F4FL
        test.containsMatchIn(format) -> 0xFF0A4A2AL
        odi.containsMatchIn(format) -> 0xFFFFE600L
        else -> null // A country picker without a format keeps its existing fallback.
    }
}
