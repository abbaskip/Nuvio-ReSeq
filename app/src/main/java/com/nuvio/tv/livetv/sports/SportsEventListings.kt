package com.nuvio.tv.livetv.sports

/** A scheduled start alone cannot confirm a live game or a final result. */
internal fun SportsEvent.pastScheduledStart(now: Long): Boolean =
    source != EventSource.GUIDE && state == GameState.PRE && hasTime && startMs < now

internal fun SportsEvent.pendingStatusLabel(now: Long): String =
    if (now >= endEstimate(this)) "Result pending" else "Status pending"

/** Fixtures and TV airings have separate timelines; channel matching never sets event order. */
internal object SportsEventListings {
    private val nonEvent = Regex("""\b(?:replays?|repeats?|highlights?|classics?|encore|condensed|review|preview|magazine|news|countdown|analysis|studio|(?:pre|post) ?(?:game|match|race)|press conference|talk show|in game live|tennis channel live|golf central|football night in america|college game ?day|game ?day morning|betting (?:live|show)|sports ?cent(?:er|re)|nfl live|nba today|match of the day)\b""")
    private val competition = Regex("""\b(?:vs?|versus|round|match|matchplay|race|racing|grand prix|qualifying|practice|sprint|finals?|semifinal|quarterfinal|session|day [1-5]|test|odi|t20i?|innings|tournament|championships?|fight|bout|stage|heats?|wrestlemania|smackdown|wwe (?:raw|nxt)|aew (?:dynamite|collision))\b""")

    private val editorial = Regex("""\b(?:sports betting|betting (?:odds|picks|tips)|panel (?:show|discussion)|studio (?:show|discussion)|news and analysis|previews? and predictions?|latest odds|live from the studio)\b""")
    private val qualifier = Regex("""\bqualifiers?\b""")
    private val companionShow = Regex("""\b(?:(?:pre|post) ?(?:show|qualifying|practice|session)|pressekonferenz|press conference|conference de presse|conferencia de prensa|conferenza stampa)\b""")
    private val recorded = Regex("""\b(?:as live|tape delayed?|delayed (?:coverage|broadcast|telecast)|recorded coverage|originally (?:aired|broadcast)|re ?airs?|re ?runs?|live on tape)\b""")
    private val listingPrefix = Regex("""^(?:live |women s |womens |men s |mens )+""")

    /** A live talk show, a replay and a channel currently on air are not live competitions. */
    fun liveCompetition(p: com.nuvio.tv.livetv.model.EpgProgram): Boolean {
        val title = SportsCatalog.norm(p.title.replace("@", " vs "))
        val description = SportsCatalog.norm(p.description.orEmpty())
        if (companionShow.containsMatchIn(title) || companionShow.containsMatchIn(description.take(45)) ||
            nonEvent.containsMatchIn(title) || nonEvent.containsMatchIn(SportsCatalog.norm(p.category.orEmpty())) ||
            editorial.containsMatchIn(description) || nonEvent.containsMatchIn(description.take(42)) ||
            recorded.containsMatchIn(title) || recorded.containsMatchIn(description)) return false
        val live = SportsCatalog.hasWord(title, "live") || description.trimStart().startsWith("live ") ||
            SportsCatalog.hasWord(description, "live coverage") || SportsCatalog.hasWord(description, "live action")
        if (!live) return false
        if (competition.containsMatchIn(title + description)) return true
        val pairing = title.substringAfter("live ").trim()
        if (" at " in pairing && !pairing.startsWith("at ") && !pairing.startsWith("coverage at ")) return true
        val competitionNode = SportsTaxonomy.classifyProgramme(p.title, p.description) ?: return false
        if (qualifier.containsMatchIn(title)) return true
        // Some guides give a live match only its competition's name, without teams or a round.
        val name = title.trim().replaceFirst(listingPrefix, "").removeSuffix(" live")
        return competitionNode.parentId != null && competitionNode.children.isEmpty() &&
            competitionNode.aliases.any { it.term == name }
    }
    fun guideAiring(event: SportsEvent, now: Long): SportsEvent? {
        val end = event.endMs ?: event.guideHits.maxOfOrNull { it.second.stopMs }
        if (end != null && now >= end) return null
        return event.copy(state = if (now >= event.startMs) GameState.LIVE else GameState.PRE,
            status = null, awaitingUpdate = false)
    }

    fun display(events: List<SportsEvent>, now: Long): List<SportsEvent> = events.mapNotNull {
        if (it.source == EventSource.GUIDE) guideAiring(it, now) else it
    }

    fun onMyChannels(events: List<SportsEvent>, matches: Map<String, List<ChannelMatch>>,
        requested: Boolean, ready: Boolean, now: Long, isMine: (SportsEvent) -> Boolean): List<SportsEvent> {
        if (!requested || !ready || matches.isEmpty()) return events
        return events.filter { it.state == GameState.POST || (it.pastScheduledStart(now) && now >= endEstimate(it)) ||
            isMine(it) || matches[it.id]?.isNotEmpty() == true }
    }

    /** Clock time stays useful even when cached games are still waiting for their final status. */
    fun entryIndex(events: List<SportsEvent>, now: Long): Int {
        val next = events.indices.filter { i ->
            val event = events[i]
            event.state == GameState.PRE && event.hasTime && event.startMs >= now
        }.minByOrNull { events[it].startMs }
        return next ?: events.indexOfFirst { it.state == GameState.LIVE && !it.awaitingUpdate }.coerceAtLeast(0)
    }

    fun allEvents(events: List<SportsEvent>, dayStart: Long, dayEnd: Long, now: Long): List<SportsEvent> =
        events.filter { ev ->
            ev.source != EventSource.GUIDE && when {
                ev.state == GameState.POST -> {
                    val finished = if (ev.hasTime) ev.startMs else minOf(ev.endMs ?: ev.startMs, now)
                    finished in dayStart until dayEnd
                }
                // Keep a game that started yesterday visible while it is still live today.
                ev.state == GameState.LIVE -> ev.startMs < dayEnd &&
                    (ev.startMs >= dayStart || (now in dayStart until dayEnd && (ev.endMs ?: Long.MAX_VALUE) > now))
                else -> ev.startMs in dayStart until dayEnd ||
                    (SportsCatalog.league(ev.leagueId)?.sport == Sport.CRICKET &&
                        ev.startMs < dayEnd && (ev.endMs ?: ev.startMs) > dayStart)
            }
        }.sortedWith(compareBy<SportsEvent> {
            // Today's continuous feed follows status, even when a live event spans days.
            // Recompute from each provider update so a newly finished game moves above live sport.
            if (now in dayStart until dayEnd) when (it.state) {
                GameState.POST -> 0
                GameState.LIVE -> 1
                GameState.PRE -> when {
                    it.pastScheduledStart(now) -> if (now >= endEstimate(it)) 0 else 1
                    it.endMs != null && it.endMs <= now -> 0
                    else -> 2
                }
                GameState.OFF -> 2
            } else 0
        }.thenBy { it.startMs }.thenBy { it.id })

    /** Use programme boundaries, so cached LIVE flags cannot keep an ended show on TV Now. */
    fun tvNow(events: List<SportsEvent>, now: Long): List<SportsEvent> =
        events.filter { ev ->
            ev.source == EventSource.GUIDE && if (ev.guideHits.isNotEmpty()) {
                ev.guideHits.any { (_, program) -> now >= program.startMs && now < program.stopMs }
            } else {
                ev.startMs <= now && ev.endMs?.let { now < it } == true
            }
        }.sortedWith(compareBy<SportsEvent> { it.startMs }.thenBy { it.id })
}
