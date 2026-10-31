package com.nuvio.tv.livetv.sports

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A goal, touchdown or basket worth listing on the game page. */
data class ScoringPlay(val clock: String, val teamAbbr: String?, val text: String, val awayScore: String?, val homeScore: String?)

/**
 * The free data sources: ESPN's public scoreboard feed (scores, clocks, logos, team colors and
 * US networks; no key) and sporting-events.org (dates of events ESPN doesn't cover).
 */
internal class SportsFeeds {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return r.body?.string() ?: throw java.io.IOException("empty answer")
        }
    }

    // ------------------------------------------------------------------ ESPN

    /** Raw scoreboard for [league] over the days [fromDay]..[toDay] ("yyyyMMdd", US Eastern days). */
    fun scoreboardRaw(league: League, fromDay: String, toDay: String): String =
        get("$ESPN${league.espnPath}/scoreboard?dates=$fromDay-$toDay&limit=1000${league.espnQuery}")

    /** One day only (for leagues whose feed won't take a range of days). */
    fun scoreboardDayRaw(league: League, day: String): String =
        get("$ESPN${league.espnPath}/scoreboard?dates=$day&limit=1000${league.espnQuery}")

    /** The feed's own pick of days (no date asked for): the last resort. */
    fun scoreboardDefaultRaw(league: League): String =
        get("$ESPN${league.espnPath}/scoreboard?limit=1000${league.espnQuery}")

    fun teamsRaw(league: League): String = get("$ESPN${league.espnPath}/teams?limit=1000")

    /**
     * A team's season from ESPN: results and games to come (US sports), or, for football with
     * [fixtures], the games still to play (without it, the results so far).
     */
    fun teamScheduleRaw(leaguePath: String, teamId: String, fixtures: Boolean): String =
        get("$ESPN$leaguePath/teams/$teamId/schedule" + if (fixtures) "?fixture=true" else "")

    fun summary(league: League, espnId: String): List<ScoringPlay> = parseSummary(summaryJson(league, espnId))

    /** A game's whole summary (scoring, stats, leaders, standings, venue…). */
    fun summaryJson(league: League, espnId: String): JSONObject = JSONObject(get("$ESPN${league.espnPath}/summary?event=$espnId"))

    /** A league table (ESPN's standings address, see [Standings.espnUrl]). */
    fun standingsRaw(url: String): String = get(url)

    // ------------------------------------------------------------------ sporting-events.org

    fun fixturesRaw(slug: String): String = get("https://sporting-events.org/data/$slug.json")

    companion object {
        private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports/"

        /** A non-league league from the nuvio-data files, with its teams (for Pick Your Teams). */
        class TsdbLeague(val league: League, val teams: List<TeamInfo>)

        /** Club colours aren't in the data: a steady colour per club, from its name. */
        private val CLUB_COLORS = longArrayOf(
            0xFFB71C1C, 0xFF0D47A1, 0xFF1B5E20, 0xFF4A148C, 0xFFE65100, 0xFF006064,
            0xFF880E4F, 0xFF263238, 0xFF33691E, 0xFF1A237E, 0xFFBF360C, 0xFF3E2723
        )
        private fun clubColor(name: String): Long = CLUB_COLORS[Math.floorMod(name.lowercase(Locale.ROOT).hashCode(), CLUB_COLORS.size)]

        private fun clubAbbr(name: String): String {
            val words = name.split(' ', '-').filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("fc", "afc", "town", "city", "united", "the", "and", "&") }
            return when {
                words.isEmpty() -> name.take(3).uppercase(Locale.ROOT)
                words.size == 1 -> words[0].take(3).uppercase(Locale.ROOT)
                else -> words.take(3).joinToString("") { it.take(1) }.uppercase(Locale.ROOT).padEnd(3, words[0].getOrElse(1) { 'X' }.uppercaseChar())
            }
        }

        fun parseFootballIndex(raw: String): List<TsdbLeague> {
            val arr = JSONObject(raw).optJSONArray("leagues") ?: return emptyList()
            val out = ArrayList<TsdbLeague>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tid = o.str("id") ?: continue
                val name = o.str("name") ?: continue
                // Other sports from the same files (rugby league, cricket…): the sport, where it's
                // watched, how guides name it and how long a game runs come with the league.
                val sport = o.str("sport")?.let { s -> Sport.entries.firstOrNull { it.name == s } } ?: Sport.FOOTBALL
                val words = o.optJSONArray("words")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty()
                val regions = o.optJSONArray("regions")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }.toSet() }.orEmpty()
                val league = League(
                    id = "tsdb/$tid", sport = SportsTaxonomy.classify(name, words.joinToString(" "))
                        ?.takeIf { it.sportId == "rugby-league" || it.sportId == "rugby-union" }
                        ?.let { SportsTaxonomy.sport(it.sportId) } ?: sport, name = name, short = o.str("short") ?: name.take(6).uppercase(Locale.ROOT),
                    guideWords = (listOf(name) + words).distinct(), regions = regions,
                    minutes = o.optInt("minutes", 115).takeIf { it > 0 } ?: 115, color = 0xFF2E3B4E
                )
                o.str("badge")?.takeIf { it.startsWith("https://") }?.let { leagueLogos[league.id] = it }
                val teams = o.optJSONArray("teams")?.let { ta ->
                    (0 until ta.length()).mapNotNull { k ->
                        val t = ta.optJSONObject(k) ?: return@mapNotNull null
                        val id = t.str("id") ?: return@mapNotNull null
                        val n = t.str("name") ?: return@mapNotNull null
                        clubTeam(league.id, id, n, t.str("badge")?.takeIf { it.startsWith("https://") })
                    }
                }.orEmpty()
                out += TsdbLeague(league, teams)
            }
            return out
        }

        private fun clubTeam(leagueId: String, id: String, name: String, badge: String?) = TeamInfo(
            leagueId = leagueId, id = id, name = name, fullName = name, abbr = clubAbbr(name),
            location = null, color = clubColor(name), altColor = null, logo = badge
        )

        /** A non-league league's games, as the Sports page shows them. */
        fun parseFootballLeague(league: League, raw: String, teams: Map<String, TeamInfo>): List<SportsEvent> {
            val arr = JSONObject(raw).optJSONArray("events") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = e.str("id") ?: continue
                val hasTime = e.optBoolean("hasTime", true)
                val start = if (hasTime) parseIso(e.str("start")) ?: continue else {
                    val p = e.str("start")?.take(10)?.split('-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
                    if (p.size != 3) continue
                    Calendar.getInstance().apply { clear(); set(p[0], p[1] - 1, p[2], 0, 0, 0) }.timeInMillis
                }
                val state = when (e.str("state")) {
                    "in" -> GameState.LIVE
                    "post" -> GameState.POST
                    "off" -> GameState.OFF
                    else -> GameState.PRE
                }
                fun side(nameKey: String, idKey: String, scoreKey: String, home: Boolean): Side? {
                    val n = e.str(nameKey) ?: return null
                    val tid = e.str(idKey) ?: n
                    val team = teams[tid] ?: clubTeam(league.id, tid, n, null)
                    val score = if (state == GameState.LIVE || state == GameState.POST) e.opt(scoreKey)?.toString()?.takeIf { it.isNotBlank() && it != "null" } else null
                    return Side(team = team, score = score, record = null, winner = false, home = home)
                }
                val h = side("home", "homeId", "hs", true) ?: continue
                val a = side("away", "awayId", "as", false) ?: continue
                val hs = h.score?.toIntOrNull()
                val aS = a.score?.toIntOrNull()
                val done = state == GameState.POST && hs != null && aS != null
                val round = e.optInt("round", 0).takeIf { it > 0 }
                out += SportsEvent(
                    id = "tsdb:${league.id}:$id",
                    leagueId = league.id,
                    startMs = start,
                    hasTime = hasTime,
                    state = state,
                    title = "${h.team.name} v ${a.team.name}",
                    subtitle = listOfNotNull(round?.let { "Round $it" }, e.str("venue")).joinToString(" · ").ifBlank { null },
                    status = when (state) {
                        GameState.LIVE -> e.str("status") ?: "Live"
                        GameState.POST -> "FT"
                        GameState.OFF -> e.str("status") ?: "Postponed"
                        GameState.PRE -> null
                    },
                    away = if (done) a.copy(winner = aS!! > hs!!) else a,
                    home = if (done) h.copy(winner = hs!! > aS!!) else h,
                    source = EventSource.FIXTURES,
                    endMs = start + league.minutes * 60_000L,
                    note = if (!hasTime) "Time to be confirmed" else null
                )
            }
            return out
        }

        /** The schedule file's league keys -> our leagues. */
        private fun wrestlingLeague(key: String?): String = when (key) {
            "wwe" -> "guide/wwe"
            "aew" -> "guide/aew"
            "boxing" -> "guide/boxing"
            else -> "guide/wrestling"
        }

        /**
         * The wrestling schedule, by league id. League logos in it (addresses only, on
         * TheSportsDB's image server) go to [leagueLogos]. Shows already over are marked ended;
         * ones on now count as live until about two hours after their start.
         */
        fun parseWrestling(raw: String, now: Long = System.currentTimeMillis()): Map<String, List<SportsEvent>> {
            val root = JSONObject(raw)
            root.optJSONArray("leagues")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    val lid = wrestlingLeague(l.str("key"))
                    l.optJSONArray("brands")?.let { b ->
                        for (j in 0 until b.length()) {
                            val br = b.optJSONObject(j) ?: continue
                            val word = br.str("word")?.let { SportsCatalog.norm(it).trim() }?.takeIf { it.isNotEmpty() } ?: continue
                            val url = br.str("badge")?.takeIf { it.startsWith("https://") } ?: continue
                            brandLogos["$lid|$word"] = url
                        }
                    }
                    val badge = l.str("badge")?.takeIf { it.startsWith("https://") } ?: continue
                    leagueLogos[lid] = badge
                }
            }
            val out = HashMap<String, MutableList<SportsEvent>>()
            val arr = root.optJSONArray("events") ?: return out
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                // "NXT - October 20th 2026" -> "NXT" (the card shows the date).
                val name = e.str("name")?.let { withoutAirDate(it) } ?: continue
                val leagueId = wrestlingLeague(e.str("league"))
                val league = SportsCatalog.league(leagueId)
                val hasTime = e.optBoolean("hasTime", true)
                // A date only: that day on the TV's own calendar (not midnight in London).
                val start = if (hasTime) parseIso(e.str("start")) ?: continue else {
                    val p = e.str("start")?.take(10)?.split('-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
                    if (p.size != 3) continue
                    Calendar.getInstance().apply { clear(); set(p[0], p[1] - 1, p[2], 0, 0, 0) }.timeInMillis
                }
                val end = start + (league?.minutes ?: 120) * 60_000L
                out.getOrPut(leagueId) { ArrayList() } += SportsEvent(
                    id = "tsdb:" + (e.str("id") ?: "$leagueId:$name:$start"),
                    leagueId = leagueId,
                    startMs = start,
                    hasTime = hasTime,
                    state = when {
                        !hasTime -> GameState.PRE
                        now >= end -> GameState.POST
                        now >= start -> GameState.LIVE
                        else -> GameState.PRE
                    },
                    title = name,
                    subtitle = listOfNotNull(e.str("venue"), e.str("city"), e.str("country")).joinToString(", ").ifBlank { null },
                    status = null,
                    away = null, home = null,
                    source = EventSource.FIXTURES,
                    endMs = end,
                    note = if (!hasTime) "Time to be confirmed" else listOfNotNull(e.str("venue"), e.str("city")).joinToString(" · ").ifBlank { null },
                    about = e.str("about")
                )
            }
            return out
        }

        private val iso = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

        fun parseIso(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            for (p in iso) {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                runCatching { return f.parse(s)?.time }
            }
            return null
        }

        fun hexColor(s: String?): Long? {
            val h = s?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6) return null
            return h.toLongOrNull(16)?.let { 0xFF000000L or it }
        }

        private fun JSONObject.str(name: String): String? = optString(name, "").takeIf { it.isNotBlank() && it != "null" }

        fun team(league: League, t: JSONObject?, athlete: JSONObject? = null): TeamInfo? {
            if (t != null) {
                val logo = t.str("logo") ?: t.optJSONArray("logos")?.let { pickLogo(it) }
                val id = t.str("id") ?: return null
                return TeamInfo(
                    leagueId = league.id,
                    id = id,
                    name = t.str("shortDisplayName") ?: t.str("name") ?: t.str("displayName") ?: return null,
                    fullName = SportsCatalog.teamFullName(league, id, t.str("displayName") ?: t.str("name") ?: ""),
                    abbr = t.str("abbreviation") ?: "",
                    location = t.str("location"),
                    color = hexColor(t.str("color")),
                    altColor = hexColor(t.str("alternateColor")),
                    logo = logo,
                    record = t.optJSONObject("record")?.optJSONArray("items")?.optJSONObject(0)?.str("summary")
                )
            }
            if (athlete != null) {
                val name = athlete.str("displayName") ?: athlete.str("fullName") ?: return null
                return TeamInfo(
                    leagueId = league.id,
                    id = athlete.str("id") ?: name,
                    name = athlete.str("shortName") ?: name,
                    fullName = name,
                    abbr = name.split(' ').lastOrNull()?.take(3)?.uppercase() ?: "",
                    location = null,
                    color = null,
                    altColor = null,
                    logo = athlete.optJSONObject("headshot")?.str("href") ?: athlete.optJSONObject("flag")?.str("href")
                )
            }
            return null
        }

        /** The normal logo (not the one made for dark backgrounds: ours sit on a light disc). */
        private fun pickLogo(logos: JSONArray): String? {
            var first: String? = null
            for (i in 0 until logos.length()) {
                val l = logos.optJSONObject(i) ?: continue
                val href = l.str("href") ?: continue
                if (first == null) first = href
                val rel = l.optJSONArray("rel").strings()
                if ("default" in rel && "dark" !in rel) return href
            }
            return first
        }

        private val CLOCK_FIRST = Regex("""^(\d{1,2}:\d{2}(?:\.\d)?)\s*-\s*(.+)$""")

        fun parseTeams(league: League, raw: String): List<TeamInfo> {
            val teams = JSONObject(raw).optJSONArray("sports")?.optJSONObject(0)
                ?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams") ?: return emptyList()
            return (0 until teams.length()).mapNotNull { i -> team(league, teams.optJSONObject(i)?.optJSONObject("team")) }
                .filter { it.fullName.isNotBlank() }
        }

        /**
         * League logos from the feed (the version made for dark backgrounds when there is one),
         * by league id: shown on the Sports page's game cards.
         */
        val leagueLogos = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * Logos for shows with their own branding inside a league ("guide/wwe|nxt" -> NXT's logo),
         * from TheSportsDB by address only, like [leagueLogos].
         */
        val brandLogos = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** The logo of the show brand [title] names, if [leagueId] has one ("NXT #860" -> NXT's). */
        fun brandLogo(leagueId: String, title: String): String? {
            if (brandLogos.isEmpty()) return null
            val t = SportsCatalog.norm(title)
            val prefix = "$leagueId|"
            for ((k, v) in brandLogos) {
                if (k.startsWith(prefix) && SportsCatalog.hasWord(t, k.substring(prefix.length))) return v
            }
            return null
        }

        /**
         * A team's schedule from ESPN. Each game goes under its own competition when the feed
         * says which (a Champions League game in Arsenal's schedule), otherwise under [league].
         */
        fun parseSchedule(league: League, raw: String): List<SportsEvent> {
            val events = JSONObject(raw).optJSONArray("events") ?: return emptyList()
            val prefix = league.espnPath?.substringBefore('/') ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val slug = ev.optJSONObject("league")?.str("slug")
                    ?: ev.optJSONArray("competitions")?.optJSONObject(0)?.optJSONObject("league")?.str("slug")
                val own = slug?.let { SportsCatalog.league("$prefix/$it") }?.takeIf { it.espnPath != null } ?: league
                runCatching { parseEvent(own, ev) }.getOrNull()?.let { out += it }
            }
            return out
        }

        fun parseScoreboard(league: League, raw: String): List<SportsEvent> {
            val root = JSONObject(raw)
            if (!leagueLogos.containsKey(league.id)) runCatching {
                val logos = root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")
                if (logos != null && logos.length() > 0) {
                    var pick: String? = null
                    for (i in 0 until logos.length()) {
                        val l = logos.optJSONObject(i) ?: continue
                        val href = l.optString("href").takeIf { it.startsWith("http") } ?: continue
                        val rel = l.optJSONArray("rel")?.toString().orEmpty()
                        if (pick == null) pick = href
                        if ("dark" in rel) { pick = href; break }
                    }
                    pick?.let { leagueLogos[league.id] = it }
                }
            }
            val events = root.optJSONArray("events") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                runCatching { parseEvent(league, ev) }.getOrNull()?.let { out += it }
            }
            return out
        }

        private fun parseEvent(league: League, ev: JSONObject): SportsEvent? {
            val espnId = ev.str("id") ?: return null
            val start = parseIso(ev.str("date")) ?: return null
            val end = parseIso(ev.str("endDate"))
            val comp = ev.optJSONArray("competitions")?.optJSONObject(0)
            // An event with several sessions (practice, qualifying, the race): the event's own status.
            val st = if (league.kind == LeagueKind.EVENT) ev.optJSONObject("status") ?: comp?.optJSONObject("status")
            else comp?.optJSONObject("status") ?: ev.optJSONObject("status")
            val type = st?.optJSONObject("type")
            val stateRaw = type?.str("state")
            val name = type?.str("name").orEmpty()
            val state = when {
                Regex("POSTPONED|CANCEL|SUSPENDED|FORFEIT|ABANDON").containsMatchIn(name) -> GameState.OFF
                stateRaw == "in" -> GameState.LIVE
                stateRaw == "post" -> GameState.POST
                else -> GameState.PRE
            }
            val short = type?.str("shortDetail") ?: type?.str("detail")
            val status = when (state) {
                GameState.LIVE -> short?.let { s -> CLOCK_FIRST.matchEntire(s)?.let { "${it.groupValues[2]} · ${it.groupValues[1]}" } ?: s }
                GameState.POST -> short ?: "Final"
                GameState.OFF -> type?.str("description") ?: "Postponed"
                GameState.PRE -> null
            }
            val period = st?.optInt("period", 0) ?: 0
            val clock = st?.optDouble("clock", Double.NaN) ?: Double.NaN
            val progress = if (state == GameState.LIVE) progressOf(league, period, clock, st?.str("displayClock")) else null

            val broadcasts = LinkedHashSet<String>()
            comp?.optJSONArray("broadcasts")?.let { a ->
                for (b in 0 until a.length()) broadcasts += a.optJSONObject(b)?.optJSONArray("names").strings()
            }
            comp?.optJSONArray("geoBroadcasts")?.let { a ->
                for (b in 0 until a.length()) a.optJSONObject(b)?.optJSONObject("media")?.str("shortName")?.let { broadcasts += it }
            }
            val venue = comp?.optJSONObject("venue")?.str("fullName") ?: ev.optJSONObject("circuit")?.str("fullName")
            val week = ev.optJSONObject("week")?.optInt("number", 0)?.takeIf { it > 0 }?.let { "Week $it" }
            val headline = comp?.optJSONArray("notes")?.optJSONObject(0)?.str("headline")

            if (league.kind == LeagueKind.EVENT) {
                val multiDay = end != null && end - start > 20 * 3_600_000L
                // A tournament that runs for days: the feed says "final" once a day's play is
                // done (tennis does every evening). It isn't over until its last day.
                // Likewise "not started" between a tournament's first day and its last (no match on
                // yet that day): it's under way.
                val nowMs = System.currentTimeMillis()
                // (The end date is the start of the last day, so the last day counts too.)
                val underWay = multiDay && end != null && end + 24 * 3_600_000L > nowMs && start <= nowMs
                val evState = if (underWay && (state == GameState.POST || state == GameState.PRE)) GameState.LIVE else state
                return SportsEvent(
                    id = "espn:${league.id}:$espnId",
                    leagueId = league.id,
                    startMs = start,
                    hasTime = !multiDay,
                    state = evState,
                    title = ev.str("name") ?: ev.str("shortName") ?: league.name,
                    subtitle = listOfNotNull(headline, venue).joinToString(" · ").ifBlank { null },
                    status = status,
                    away = null, home = null,
                    broadcasts = broadcasts.toList(),
                    espnId = espnId,
                    endMs = end,
                    note = if (multiDay && end != null) "Until " + SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(java.util.Date(end)) else null,
                    standings = runCatching {
                        when {
                            // Tennis matches, a fight card's bouts: two at a time.
                            league.sport == Sport.TENNIS || league.sport == Sport.FIGHTING -> matchesOf(ev, mainFirst = league.sport == Sport.FIGHTING)
                            state == GameState.PRE -> emptyList()
                            else -> standingsOf(ev)
                        }
                    }.getOrDefault(emptyList())
                )
            }

            val competitors = comp?.optJSONArray("competitors") ?: return null
            var home: Side? = null
            var away: Side? = null
            for (c in 0 until competitors.length()) {
                val co = competitors.optJSONObject(c) ?: continue
                val team = team(league, co.optJSONObject("team"), co.optJSONObject("athlete")) ?: continue
                val scoreAny = co.opt("score")
                val score = when (scoreAny) {
                    is JSONObject -> scoreAny.str("displayValue")
                    is String -> scoreAny.takeIf { it.isNotBlank() }
                    is Number -> scoreAny.toInt().toString()
                    else -> null
                }?.takeIf { state != GameState.PRE }
                val periods = co.optJSONArray("linescores")?.let { a ->
                    (0 until a.length()).mapNotNull { k ->
                        val l = a.optJSONObject(k) ?: return@mapNotNull null
                        l.str("displayValue") ?: l.optDouble("value", Double.NaN).takeIf { !it.isNaN() }?.toInt()?.toString()
                    }
                }.orEmpty()
                val side = Side(
                    team = team,
                    score = score,
                    record = co.optJSONArray("records")?.optJSONObject(0)?.str("summary"),
                    winner = co.optBoolean("winner", false),
                    home = co.str("homeAway") == "home",
                    periods = periods
                )
                if (side.home && home == null) home = side else if (away == null) away = side else if (home == null) home = side
            }
            val h: Side = home ?: return null
            val a: Side = away ?: return null

            val situation = comp?.optJSONObject("situation")?.let { s ->
                val dd = s.str("downDistanceText") ?: s.str("shortDownDistanceText")
                val pos = s.str("possession")
                val who = listOf(h, a).firstOrNull { it.team.id == pos }?.team?.abbr
                when {
                    dd != null && who != null -> "$who ball · $dd"
                    dd != null -> dd
                    else -> null
                }
            }?.takeIf { state == GameState.LIVE }

            val sep = if (league.sport.homeFirst) " v " else " at "
            val title = if (league.sport.homeFirst) "${h.team.name}$sep${a.team.name}" else "${a.team.name}$sep${h.team.name}"
            return SportsEvent(
                id = "espn:${league.id}:$espnId",
                leagueId = league.id,
                startMs = start,
                hasTime = true,
                state = state,
                title = title,
                subtitle = listOfNotNull(headline, week, venue).joinToString(" · ").ifBlank { null },
                status = status,
                away = a,
                home = h,
                broadcasts = broadcasts.toList(),
                situation = situation,
                progress = progress,
                espnId = espnId
            )
        }

        /**
         * A race's finishing order (or the running order while it's on), a golf leaderboard: from
         * the race itself when the event has practice and qualifying too, otherwise the session
         * with the most drivers or players.
         */
        private fun standingsOf(ev: JSONObject): List<Standing> {
            val comps = ev.optJSONArray("competitions") ?: return emptyList()
            val all = (0 until comps.length()).mapNotNull { comps.optJSONObject(it) }
                .filter { (it.optJSONArray("competitors")?.length() ?: 0) >= 3 }
            if (all.isEmpty()) return emptyList()
            fun isRace(c: JSONObject): Boolean {
                val t = c.optJSONObject("type")
                val words = listOfNotNull(t?.str("abbreviation"), t?.str("text"), t?.str("name")).joinToString(" ").lowercase(Locale.ROOT)
                return Regex("""\brace\b""").containsMatchIn(words) || words.trim() == "r"
            }
            val started = all.filter { c -> c.optJSONObject("status")?.optJSONObject("type")?.str("state") in setOf("in", "post") }
            val pick = started.lastOrNull { isRace(it) } ?: all.lastOrNull { isRace(it) && it in started }
                ?: started.maxByOrNull { it.optJSONArray("competitors")?.length() ?: 0 } ?: return emptyList()
            val list = pick.optJSONArray("competitors") ?: return emptyList()
            val rows = (0 until list.length()).mapNotNull { i ->
                val co = list.optJSONObject(i) ?: return@mapNotNull null
                val name = co.optJSONObject("athlete")?.let { it.str("displayName") ?: it.str("shortName") ?: it.str("fullName") }
                    ?: co.optJSONObject("team")?.let { it.str("displayName") ?: it.str("name") }
                    ?: return@mapNotNull null
                val order = co.optInt("order", 0).takeIf { it > 0 } ?: (i + 1)
                val score = when (val sc = co.opt("score")) {
                    is JSONObject -> sc.str("displayValue")
                    is String -> sc.takeIf { it.isNotBlank() }
                    else -> null
                }?.takeIf { it != "0" && it != "-" }
                order to Standing(order.toString(), name, score)
            }
            return rows.sortedBy { it.first }.map { it.second }.take(20)
        }

        /**
         * A tennis tournament's matches (the feed groups them by draw: men's singles, women's…):
         * the ones on now, then today's results, then the next ones. "Sinner def. Rune", "6-4 6-3".
         */
        private fun matchesOf(ev: JSONObject, mainFirst: Boolean = false): List<Standing> {
            val comps = ArrayList<JSONObject>()
            ev.optJSONArray("groupings")?.let { g ->
                for (i in 0 until g.length()) {
                    val cs = g.optJSONObject(i)?.optJSONArray("competitions") ?: continue
                    for (j in 0 until cs.length()) cs.optJSONObject(j)?.let { comps += it }
                }
            }
            ev.optJSONArray("competitions")?.let { cs -> for (j in 0 until cs.length()) cs.optJSONObject(j)?.let { comps += it } }
            class M(val state: String, val at: Long, val line: Standing)
            val now = System.currentTimeMillis()
            val all = comps.mapNotNull { c ->
                val cos = c.optJSONArray("competitors") ?: return@mapNotNull null
                if (cos.length() != 2) return@mapNotNull null
                fun name(co: JSONObject?) = co?.optJSONObject("athlete")?.let { it.str("shortName") ?: it.str("displayName") }
                    ?: co?.optJSONObject("team")?.str("displayName")
                val a = cos.optJSONObject(0)
                val b = cos.optJSONObject(1)
                val an = name(a) ?: return@mapNotNull null
                val bn = name(b) ?: return@mapNotNull null
                val st = c.optJSONObject("status")?.optJSONObject("type")
                val state = st?.str("state") ?: "pre"
                val at = parseIso(c.str("date") ?: c.str("startDate")) ?: 0L
                fun sets(x: JSONObject?) = x?.optJSONArray("linescores")?.let { ls ->
                    (0 until ls.length()).mapNotNull { k -> ls.optJSONObject(k)?.optDouble("value", Double.NaN)?.takeIf { !it.isNaN() }?.toInt() }
                }.orEmpty()
                val aw = a?.optBoolean("winner", false) == true
                val bw = b?.optBoolean("winner", false) == true
                // The winner first: "Sinner def. Rune 6-4 6-3".
                val first = if (bw) bn else an
                val second = if (bw) an else bn
                val fs = if (bw) sets(b) else sets(a)
                val ss = if (bw) sets(a) else sets(b)
                val score = fs.zip(ss).joinToString(" ") { (x, y) -> "$x-$y" }
                val round = (c.optJSONObject("round")?.str("displayName") ?: c.optJSONObject("type")?.str("abbreviation"))?.let { r ->
                    // Short, for the narrow column: "Quarterfinal" -> "QF", "Round of 16" -> "R16".
                    when {
                        r.contains("quarter", true) -> "QF"
                        r.contains("semi", true) -> "SF"
                        r.equals("final", true) || r.equals("finals", true) -> "F"
                        Regex("""(\d+)""").find(r) != null && r.length > 4 -> "R" + Regex("""(\d+)""").find(r)!!.value
                        else -> r.take(4)
                    }
                }
                val line = when (state) {
                    "post" -> Standing(round.orEmpty(), if (aw || bw) "$first def. $second" else "$first v $second", score.ifBlank { st?.str("shortDetail") })
                    "in" -> Standing("LIVE", "$an v $bn", score.ifBlank { st?.str("shortDetail") })
                    else -> Standing(round.orEmpty(), "$an v $bn", if (at > 0) SimpleDateFormat("EEE", Locale.getDefault()).format(java.util.Date(at)) + " " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at)) else null)
                }
                M(state, at, line)
            }
            val live = all.filter { it.state == "in" }
            val done = all.filter { it.state == "post" && now - it.at < 30 * 3_600_000L }.sortedByDescending { it.at }
            // A fight card: the main event first (it's the last bout in the feed).
            if (mainFirst) return (live + all.filter { it.state != "in" }.reversed()).map { it.line }.take(14)
            val next = all.filter { it.state == "pre" && it.at >= now - 3_600_000L }.sortedBy { it.at }
            return (live + done + next).map { it.line }.take(12)
        }

        /** How far through the game the clock is (0..1), from the period and the clock. */
        private fun progressOf(league: League, period: Int, clockSec: Double, displayClock: String?): Float? {
            if (period <= 0) return null
            fun countdown(periods: Int, len: Double): Float? {
                if (clockSec.isNaN()) return null
                val done = (period - 1).coerceAtMost(periods) * len + (len - clockSec.coerceIn(0.0, len))
                return (done / (periods * len)).toFloat().coerceIn(0f, 1f)
            }
            return when (league.sport) {
                Sport.AMERICAN_FOOTBALL -> countdown(4, 900.0)
                Sport.BASKETBALL -> when (league.id) {
                    "basketball/nba" -> countdown(4, 720.0)
                    "basketball/mens-college-basketball" -> countdown(2, 1200.0)
                    else -> countdown(4, 600.0)
                }
                Sport.HOCKEY -> countdown(3, 1200.0)
                Sport.BASEBALL -> ((period - 0.5f) / 9f).coerceIn(0f, 1f)
                Sport.FOOTBALL -> displayClock?.let { Regex("""^(\d+)""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
                    ?.let { (it / 90f).coerceIn(0f, 1f) }
                else -> null
            }
        }

        fun parseSummary(root: JSONObject): List<ScoringPlay> {
            val out = ArrayList<ScoringPlay>()
            root.optJSONArray("scoringPlays")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    val period = p.optJSONObject("period")?.optInt("number", 0) ?: 0
                    val clock = p.optJSONObject("clock")?.str("displayValue")
                    val type = p.optJSONObject("scoringType")?.str("abbreviation") ?: p.optJSONObject("type")?.str("abbreviation")
                    out += ScoringPlay(
                        clock = listOfNotNull(if (period > 0) periodLabel(period) else null, clock).joinToString(" "),
                        teamAbbr = p.optJSONObject("team")?.let { it.str("abbreviation") ?: it.str("displayName") },
                        text = listOfNotNull(type, p.str("text")).joinToString(" · "),
                        awayScore = p.opt("awayScore")?.toString(),
                        homeScore = p.opt("homeScore")?.toString()
                    )
                }
            }
            // Hockey, baseball, basketball: no scoring list, but every play, with scoring ones marked.
            if (out.isEmpty()) root.optJSONArray("plays")?.let { a ->
                val abbrs = HashMap<String, String>()
                root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors")?.let { cs ->
                    for (i in 0 until cs.length()) {
                        val t = cs.optJSONObject(i)?.optJSONObject("team") ?: continue
                        val id = t.str("id") ?: continue
                        abbrs[id] = t.str("abbreviation") ?: t.str("shortDisplayName") ?: continue
                    }
                }
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    if (!p.optBoolean("scoringPlay", false)) continue
                    val per = p.optJSONObject("period")
                    val periodText = (per?.str("displayValue")?.replace(Regex("""\s*(Period|Quarter|Half)$""", RegexOption.IGNORE_CASE), ""))
                        ?: per?.optInt("number", 0)?.takeIf { it > 0 }?.let { periodLabel(it) }
                    val clock = p.optJSONObject("clock")?.str("displayValue")
                    val team = p.optJSONObject("team")?.let { it.str("abbreviation") ?: it.str("id")?.let { id -> abbrs[id] } }
                    out += ScoringPlay(
                        clock = listOfNotNull(periodText, clock?.takeIf { it != "0:00" || periodText == null }).joinToString(" "),
                        teamAbbr = team,
                        text = p.str("text") ?: p.optJSONObject("type")?.str("text") ?: "Score",
                        awayScore = p.opt("awayScore")?.toString(),
                        homeScore = p.opt("homeScore")?.toString()
                    )
                }
            }
            if (out.isEmpty()) root.optJSONArray("keyEvents")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    val goal = p.optBoolean("scoringPlay", false) || p.optJSONObject("type")?.str("text")?.contains("Goal", true) == true
                    if (!goal) continue
                    out += ScoringPlay(
                        clock = p.optJSONObject("clock")?.str("displayValue").orEmpty(),
                        teamAbbr = p.optJSONObject("team")?.let { it.str("abbreviation") ?: it.str("displayName") },
                        text = p.str("text") ?: p.optJSONObject("type")?.str("text") ?: "Goal",
                        awayScore = null,
                        homeScore = null
                    )
                }
            }
            return out
        }

        private fun periodLabel(p: Int): String = when (p) {
            1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${p}th"
        }

        /** sporting-events.org: date-only (or dated and timed) events for a sport ESPN doesn't cover. */
        fun parseFixtures(league: League, raw: String): List<SportsEvent> {
            val arr = JSONObject(raw).optJSONArray("events") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val date = e.str("date_utc") ?: continue
                val time = e.str("time_utc")
                val name = e.str("fixture") ?: continue
                val start: Long = if (time != null) {
                    parseIso("${date}T${time.take(5)}Z") ?: continue
                } else {
                    // A date only: that day on the TV's own calendar.
                    val p = date.split('-').mapNotNull { it.toIntOrNull() }
                    if (p.size != 3) continue
                    Calendar.getInstance().apply { clear(); set(p[0], p[1] - 1, p[2], 0, 0, 0) }.timeInMillis
                }
                val home = e.str("home_team")
                val awayTeam = e.str("away_team")
                out += SportsEvent(
                    id = "fx:${league.id}:${e.optString("match", i.toString())}:$date",
                    leagueId = league.id,
                    startMs = start,
                    hasTime = time != null,
                    state = if (e.str("status")?.contains("finish", true) == true) GameState.POST else GameState.PRE,
                    title = if (home != null && awayTeam != null) "$home v $awayTeam" else name,
                    subtitle = listOfNotNull(e.str("city"), e.str("country")).joinToString(", ").ifBlank { null },
                    status = null,
                    away = null, home = null,
                    broadcasts = listOfNotNull(e.str("broadcast")),
                    source = EventSource.FIXTURES,
                    note = if (time == null) "Starts today" else null
                )
            }
            return out
        }
    }
}
