package com.nuvio.tv.livetv.sports

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nuvio.tv.livetv.ui.formatClock

// ------------------------------------------------------------------ colors

/** A team's color that reads on a dark screen: the alternate color when the main one is near white. */
internal fun teamColor(t: TeamInfo?): Color {
    stateCricketColor(t)?.let { return Color(it) }
    SportsCricketColors.color(t)?.let { return Color(it) }
    val main = t?.color?.let { Color(it) }
    val alt = t?.altColor?.let { Color(it) }
    val pick = when {
        main == null -> alt
        main.luminance() > 0.62f && alt != null && alt.luminance() < 0.62f -> alt
        else -> main
    }
    return pick ?: Color(0xFF3A4150)
}

internal fun leagueColor(l: League?): Color = Color(l?.color ?: 0xFF2A2F3A)

// ------------------------------------------------------------------ words for times and states

internal fun startLabel(ev: SportsEvent, use24h: Boolean): String = when {
    ev.hasTime -> formatClock(ev.startMs, use24h)
    // Several days (a tournament, a race weekend): when it begins, not "All day".
    ev.endMs != null && ev.endMs - ev.startMs > 20 * 3_600_000L -> "Starts"
    else -> "All day"
}

/** "in 1 h 51", "in 12 min", "Tonight", "Tomorrow". */
internal fun untilLabel(startMs: Long, now: Long, dayEnd: Long): String {
    val m = ((startMs - now) / 60_000L).toInt()
    return when {
        m <= 0 -> "Starting"
        m < 60 -> "in $m min"
        m < 6 * 60 -> "in ${m / 60} h ${m % 60}"
        startMs < dayEnd -> "Later today"
        else -> ""
    }
}

/** What the corner of a card says: the game clock, "Started 1:00", "Final", or the start time. */
internal fun cornerStatus(ev: SportsEvent, use24h: Boolean, hideScores: Boolean): String = if (ev.awaitingUpdate) "Updating…" else when (ev.state) {
    GameState.LIVE -> if (!hideScores && ev.status != null && ev.source != EventSource.GUIDE && ev.hasScore) ev.status
    else (if (ev.source == EventSource.GUIDE) "Airing since " else "Started ") + formatClock(ev.startMs, use24h)
    GameState.POST -> if (ev.source == EventSource.GUIDE) "Ended" else (ev.status ?: "Final")
    GameState.OFF -> ev.status ?: "Postponed"
    GameState.PRE -> startLabel(ev, use24h)
}

/** Time bar: how far through, from the game clock when known, otherwise from the start time and usual length. */
internal fun progressOf(ev: SportsEvent, now: Long): Float {
    if (ev.state == GameState.POST) return 1f
    if (ev.state != GameState.LIVE) return 0f
    ev.progress?.let { return it }
    val league = SportsCatalog.league(ev.leagueId)
    val end = ev.endMs ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
    return ((now - ev.startMs).toFloat() / (end - ev.startMs).coerceAtLeast(1L)).coerceIn(0.02f, 0.98f)
}

internal fun endEstimate(ev: SportsEvent): Long {
    val league = SportsCatalog.league(ev.leagueId)
    return ev.endMs?.takeIf { ev.hasTime } ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
}

/** A followed team plays, a league you follow, a name you follow, or a reminder you set. */
internal fun isMine(ev: SportsEvent, f: SportsFollows): Boolean =
    f.leagues.any { SportsTaxonomy.includesLeague(it, ev.leagueId) } || ev.word != null ||
        // Saved whole-league follows are migrated to pins, including team sports such as NBA.
        f.pins.any { SportsTaxonomy.includesLeague(it, ev.leagueId) } ||
        ev.sides.any { f.followsTeam(it.team) } ||
        f.reminders.any { it.eventId == ev.id }

// Flag artwork: https://flagcdn.com (Flagpedia / Wikimedia Commons).
private val nationalFlagCodes by lazy {
    java.util.Locale.getISOCountries().associate { code ->
        SportsTaxonomy.normalize(java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH)) to code.lowercase(java.util.Locale.ROOT)
    } + mapOf("england" to "gb-eng", "scotland" to "gb-sct", "wales" to "gb-wls",
        "usa" to "us", "united states of america" to "us", "uk" to "gb", "south korea" to "kr",
        "north korea" to "kp", "ivory coast" to "ci", "turkey" to "tr")
}
private val nationalTeamSuffix = Regex("(?: womens?| mens?| national| rugby league| rugby union| rugby| football| soccer| cricket| basketball| team)+$")

/** Exact country names only: a club containing a country name must keep its own identity. */
internal fun nationalFlagCode(team: TeamInfo?): String? = team?.fullName?.let {
    nationalFlagCodes[SportsTaxonomy.normalize(it.replace("'", "").replace("\u2019", "")).replace(nationalTeamSuffix, "")]
}

/** Fixed state identities, restricted to the two Australian domestic competitions. */
internal fun stateCricketColor(t: TeamInfo?, league: League? = t?.let { SportsCatalog.league(it.leagueId) }): Long? {
    if (t == null) return null
    val domestic = t.leagueId in setOf("ca/shield", "ca/one-day") ||
        (league?.sport == Sport.CRICKET && SportsTaxonomy.normalize(league.name) in
            setOf("sheffield shield", "one day cup", "domestic one day cup", "australia domestic one day cup"))
    if (!domestic) return null
    val names = listOf(t.fullName, t.name).map {
        SportsTaxonomy.normalize(it).replace(Regex("\\b(women|womens|men|mens)\\b"), "").trim().replace(Regex(" +"), " ")
    }
    return names.firstNotNullOfOrNull { name -> when (name) {
        "new south wales", "new south wales blues", "nsw", "nsw blues", "new south wales breakers" -> 0xFF6CACE4L
        "queensland", "queensland bulls", "queensland fire" -> 0xFF7A0019L
        "victoria", "victoria bushrangers" -> 0xFF002B5CL
        "south australia", "south australia redbacks", "south australian scorpions" -> 0xFFE4002BL
        "western australia", "western warriors", "western australia warriors" -> 0xFFFFC72CL
        "tasmania", "tasmania tigers", "tasmanian tigers" -> 0xFF00843DL
        else -> null
    } }
}

internal fun teamBandColor(t: TeamInfo?): Color {
    val base = teamColor(t)
    return if (stateCricketColor(t) in setOf(0xFF6CACE4L, 0xFFFFC72CL))
        androidx.compose.ui.graphics.lerp(base, SportsLook.Ink, 0.5f) else bandColor(base)
}
