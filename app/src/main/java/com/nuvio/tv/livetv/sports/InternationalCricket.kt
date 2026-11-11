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
    private val format = Regex(" +(?:test(?:s| matches)?|odi(?:s)?|t20(?:i|s| internationals)?|one day|twenty20)(?: +team)?$")
    fun country(name: String): String? {
        val plain = SportsTaxonomy.normalize(name).replace(format, "").trim()
        return teams.firstOrNull { SportsTaxonomy.normalize(it.name) == plain }?.id
    }
    fun matches(followed: FollowedTeam, team: TeamInfo): Boolean =
        followed.leagueId == LEAGUE_ID && SportsCatalog.league(team.leagueId)?.sport == Sport.CRICKET &&
            SportsCatalog.league(team.leagueId)?.women != true &&
            country(team.fullName.ifBlank { team.name }) == followed.teamId

    fun includes(league: League): Boolean = league.sport == Sport.CRICKET && !league.women &&
        (league.national || Regex("test|odi|international|world cup|champions trophy|asia cup", RegexOption.IGNORE_CASE)
            .containsMatchIn(league.name))
}
