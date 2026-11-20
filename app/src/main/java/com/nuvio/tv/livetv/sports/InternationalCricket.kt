package com.nuvio.tv.livetv.sports

/** A country selection is independent of the provider's Test, ODI and T20 team IDs. */
internal object InternationalCricket {
    const val LEAGUE_ID = "international/cricket"
    val league = League(LEAGUE_ID, Sport.CRICKET, "International Cricket", "INTERNATIONAL", national = true)
    private val countries = listOf(
        "Afghanistan", "Australia", "Bangladesh", "England", "India", "Ireland",
        "New Zealand", "Pakistan", "South Africa", "Sri Lanka", "West Indies", "Zimbabwe"
    )
    val teams: List<TeamInfo> by lazy { countries.map { name ->
        TeamInfo(LEAGUE_ID, SportsTaxonomy.normalize(name).replace(" ", "-"), name, name,
            name.take(3).uppercase(), null, null, null, null)
    } }
    // Only strip format labels. Women's, A, youth and domestic sides stay separate.
    private val format = Regex(" +(?:test(?:s| matches)?|odi(?:s)?|t20(?:is?|s| internationals)?|one day(?: international)?|twenty20)(?: +team)?$")
    private val seniorMen = Regex(" +men(?: s)?(?: cricket)?(?: team)?$")
    private val pair = Regex("""\s+(?:v\.?|vs\.?|versus)\s+""", RegexOption.IGNORE_CASE)
    private val otherSquad = Regex("""\b(?:women|womens|ladies|girls|under ?\d+|u ?\d+|youth|legends|a|xi)\b""")
    private val countryIds by lazy { teams.associate { SportsTaxonomy.normalize(it.name) to it.id } }
    fun country(name: String): String? {
        val plain = SportsTaxonomy.normalize(name).replace(seniorMen, "").replace(format, "").replace(seniorMen, "")
        return countryIds[plain]
    }
    fun matches(followed: FollowedTeam, team: TeamInfo): Boolean =
        followed.leagueId == LEAGUE_ID && SportsCatalog.league(team.leagueId)?.sport == Sport.CRICKET &&
            SportsCatalog.league(team.leagueId)?.women != true &&
            country(team.fullName.ifBlank { team.name }) == followed.teamId

    /** Some fixture feeds provide "home v away" and no team objects. Require an international cricket pairing. */
    fun playsIn(followed: FollowedTeam, event: SportsEvent, savedLeague: League? = null): Boolean {
        val league = SportsCatalog.league(event.leagueId) ?: savedLeague ?: return false
        if (league.sport != Sport.CRICKET || league.women || otherSquad.containsMatchIn(SportsTaxonomy.normalize(league.name))) return false
        if (event.sides.any { country(it.team.fullName.ifBlank { it.team.name }) == followed.teamId }) return true
        if (event.sides.size == 2 || (!includes(league) && league.id != "guide/cricket")) return false
        val sides = pair.split(event.title, limit = 3)
        return sides.size == 2 && sides.none { otherSquad.containsMatchIn(SportsTaxonomy.normalize(it)) } &&
            sides.any { country(it) == followed.teamId }
    }

    fun includes(league: League): Boolean = league.sport == Sport.CRICKET && !league.women &&
        (league.national || Regex("test|odi|international|world cup|champions trophy|asia cup", RegexOption.IGNORE_CASE)
            .containsMatchIn(league.name))
}
