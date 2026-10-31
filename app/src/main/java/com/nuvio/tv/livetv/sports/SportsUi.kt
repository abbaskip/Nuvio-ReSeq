package com.nuvio.tv.livetv.sports

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nuvio.tv.livetv.ui.formatClock

// ------------------------------------------------------------------ colors

/** A team's color that reads on a dark screen: the alternate color when the main one is near white. */
internal fun teamColor(t: TeamInfo?): Color {
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
internal fun cornerStatus(ev: SportsEvent, use24h: Boolean, hideScores: Boolean): String = when (ev.state) {
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
        // A followed event sport (UFC, F1, wrestling…): its events are yours (there's no team to pick).
        f.pins.any { SportsCatalog.league(it)?.kind == LeagueKind.EVENT && SportsTaxonomy.includesLeague(it, ev.leagueId) } ||
        ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } ||
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
