package com.nuvio.tv.livetv.sports

/** Cross-feed correspondence requires a competition, a start time and both participants. */
internal object SportsEventIdentity {
    private fun competition(l: League) = SportsTaxonomy.classify(l.name, l.guideWords.joinToString(" ") + " " + l.sport.label)
        ?.takeIf { it.parentId != null && it.children.isEmpty() }?.id
    /** A guide listing must name both participants; one shared national team is not a duplicate. */
    fun guideCoversFixture(guide: SportsEvent, fixture: SportsEvent, league: League): Boolean {
        if (!guide.hasTime || !fixture.hasTime || fixture.sides.size != 2 ||
            kotlin.math.abs(guide.startMs - fixture.startMs) > 90 * 60_000L) return false
        val text = SportsCatalog.norm(guide.title + " " + guide.subtitle.orEmpty())
        val fixtureText = SportsCatalog.norm(fixture.title + " " + league.name)
        fun category(t: String) = when {
            SportsCatalog.hasWord(t, "wheelchair") -> "wheelchair"
            SportsCatalog.hasWord(t, "women") || SportsCatalog.hasWord(t, "womens") -> "women"
            else -> "open"
        }
        if (category(text) != category(fixtureText)) return false
        return fixture.sides.all { side -> SportsCatalog.teamNames(side.team, league).first.any { name ->
            val term = SportsCatalog.norm(name).trim()
            term.length >= 4 && SportsCatalog.hasWord(text, term)
        } }
    }

    fun same(a: SportsEvent, b: SportsEvent, first: League, second: League): Boolean {
        if (first.sport != second.sport || !a.hasTime || !b.hasTime || kotlin.math.abs(a.startMs - b.startMs) > 90 * 60_000L) return false
        val comp = competition(first) ?: return false
        if (comp != competition(second)) return false
        if (a.sides.size != 2 || b.sides.size != 2) return false
        fun names(t: TeamInfo, l: League) = SportsCatalog.teamNames(t, l).first
            .map { SportsCatalog.norm(it).trim() }.filter { it.length >= 4 }.toSet()
        fun matches(x: Side, y: Side) = names(x.team, first).intersect(names(y.team, second)).isNotEmpty()
        return (matches(a.sides[0], b.sides[0]) && matches(a.sides[1], b.sides[1])) ||
            (matches(a.sides[0], b.sides[1]) && matches(a.sides[1], b.sides[0]))
    }
}
