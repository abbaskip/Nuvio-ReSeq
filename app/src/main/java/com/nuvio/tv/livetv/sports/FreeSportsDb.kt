package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Documented public V1 key. Free responses are partial; no premium/live-score endpoint is used. */
internal class FreeSportsDb {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val gate = Mutex()
    private var nextRequestAt = 0L
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    suspend fun get(endpoint: String, vararg params: Pair<String, String>): String = gate.withLock {
        delay((nextRequestAt - clock()).coerceAtLeast(0L))
        val url = "https://www.thesportsdb.com/api/v1/json/123/".toHttpUrl().newBuilder()
            .addPathSegment(endpoint).apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        nextRequestAt = clock() + 2_200L
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (r.code == 429) nextRequestAt = clock() + 60_000L
            if (!r.isSuccessful) throw IOException("TheSportsDB HTTP ${r.code}")
            r.body?.string() ?: throw IOException("TheSportsDB empty response")
        }
    }

    companion object {
        val discoverySports = mapOf(
            Sport.RUGBY to "Rugby", Sport.RUGBY_LEAGUE to "Rugby", Sport.CRICKET to "Cricket",
            Sport.FOOTBALL to "Soccer", Sport.BASKETBALL to "Basketball", Sport.AMERICAN_FOOTBALL to "American Football",
            Sport.BASEBALL to "Baseball", Sport.HOCKEY to "Ice Hockey", Sport.SWIMMING to "Swimming",
            Sport.ATHLETICS to "Athletics", Sport.SNOOKER to "Snooker", Sport.GYMNASTICS to "Gymnastics",
            Sport.CYCLING to "Cycling", Sport.FIGHTING to "Fighting", Sport.WRESTLING to "Fighting", Sport.TENNIS to "Tennis", Sport.GOLF to "Golf")
        // Stable catalogue identifiers, verified by lookup; discovery itself is truncated on free access.
        val seedIds = listOf("4416", "4415", "4461", "5806", "5282")
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
            ?.trim()?.takeUnless { it.isEmpty() || it == "null" }
        private fun rows(raw: String, vararg keys: String): List<JsonObject> {
            val o = Json.parseToJsonElement(raw).jsonObject
            return keys.firstNotNullOfOrNull { o[it] as? JsonArray }.orEmpty().mapNotNull { it as? JsonObject }
        }
        fun leagues(raw: String): List<League> = rows(raw, "leagues", "countries").mapNotNull { o ->
            val id = o.text("idLeague")?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
            val name = o.text("strLeague") ?: return@mapNotNull null
            val declared = o.text("strSport")
            val classified = SportsTaxonomy.classify(name, o.text("strLeagueAlternate"))
            val wrestling = declared == "Fighting" && SportsCatalog.leagues.filter { it.sport == Sport.WRESTLING }
                .any { l -> l.guideWords.any { w -> SportsCatalog.hasWord(SportsCatalog.norm(name), SportsCatalog.norm(w).trim()) } }
            val sport = if (wrestling) Sport.WRESTLING else if (declared == "Rugby") classified?.let { SportsTaxonomy.sport(it.sportId) }
                ?.takeIf { it == Sport.RUGBY || it == Sport.RUGBY_LEAGUE }
                else discoverySports.entries.firstOrNull { it.value == declared }?.key
            if (sport == null) return@mapNotNull null
            League("tsdb/$id", sport, name, name, kind = if (sport in setOf(Sport.FOOTBALL, Sport.RUGBY, Sport.RUGBY_LEAGUE, Sport.CRICKET, Sport.BASKETBALL, Sport.BASEBALL, Sport.HOCKEY, Sport.AMERICAN_FOOTBALL)) LeagueKind.TEAMS else LeagueKind.EVENT, guideWords = listOf(name) +
                o.text("strLeagueAlternate").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty))
        }
        fun events(league: League, raw: String, checkedAt: Long): List<SportsEvent> = rows(raw, "events", "event").mapNotNull { o ->
            val id = o.text("idEvent") ?: return@mapNotNull null
            if (o.text("idLeague") != league.id.removePrefix("tsdb/")) return@mapNotNull null
            val date = o.text("dateEvent") ?: return@mapNotNull null
            val time = o.text("strTime")?.take(8)
            val timestamp = o.text("strTimestamp")?.let { runCatching { Instant.parse(if (it.endsWith("Z") || Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(it)) it else it + "Z").toEpochMilli() }.getOrNull() }
            val at = timestamp ?: runCatching { LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() +
                    (time?.let { java.time.LocalTime.parse(it).toSecondOfDay() * 1000L } ?: 0) }.getOrNull()
                ?: return@mapNotNull null
            val status = o.text("strStatus")
            val state = when (status?.uppercase()) {
                "FT", "AET", "AP", "MATCH FINISHED", "MATCHFINISHED", "FINISHED" -> GameState.POST
                "PST", "CANC", "ABD", "POSTPONED", "CANCELLED", "ABANDONED" -> GameState.OFF
                "1H", "2H", "HT", "ET", "BT", "P", "LIVE", "IN PROGRESS", "IN PLAY", "1Q", "2Q", "3Q", "4Q", "1P", "2P", "3P" -> GameState.LIVE
                else -> GameState.PRE
            }
            fun side(home: Boolean): Side? {
                val prefix = if (home) "Home" else "Away"
                val name = o.text("str${prefix}Team") ?: return null
                val teamId = o.text("id${prefix}Team") ?: return null
                val score = o.text("int${prefix}Score")?.takeIf { state == GameState.LIVE || state == GameState.POST }
                return Side(TeamInfo(league.id, teamId, name, name, name.take(4).uppercase(), null, null, null,
                    o.text("str${prefix}TeamBadge")?.takeIf { it.startsWith("https://") }),
                    score, null, false, home)
            }
            val title = o.text("strEvent") ?: return@mapNotNull null
            SportsEvent("tsdb:${league.id.removePrefix("tsdb/")}:$id", league.id, at, timestamp != null || time != null,
                state, title, null, status, side(false), side(true), source = EventSource.THESPORTSDB,
                note = "TheSportsDB · limited free schedule/results", about = o.text("strDescriptionEN"),
                updatedAt = checkedAt)
        }
        // Bound background lookups; a cached LIVE status must not poll an old fixture forever.
        fun refreshableWhileWatching(event: SportsEvent, now: Long): Boolean =
            event.source == EventSource.THESPORTSDB && event.hasTime &&
                event.state != GameState.POST && event.state != GameState.OFF &&
                now >= event.startMs && now - event.startMs < 24 * 60 * 60_000L

        fun eventLeagueIds(raw: String): List<String> = rows(raw, "events", "event").mapNotNull { it.text("idLeague") }.distinct()
        fun joinEvents(raw: List<String>): String = buildJsonObject {
            put("events", JsonArray(raw.flatMap { rows(it, "events") }.distinctBy { it.text("idEvent") }))
        }.toString()
        fun teams(league: League, raw: String): List<TeamInfo> = rows(raw, "teams").mapNotNull { o ->
            val id = o.text("idTeam") ?: return@mapNotNull null
            val name = o.text("strTeam") ?: return@mapNotNull null
            TeamInfo(league.id, id, name, name, o.text("strTeamShort") ?: name.take(4).uppercase(),
                null, null, null, (o.text("strBadge") ?: o.text("strTeamBadge"))?.takeIf { it.startsWith("https://") })
        }
        fun coverage(raw: String, eventId: String): List<SportsBroadcast> = rows(raw, "tvevent", "tv", "eventtv").mapNotNull { o ->
            if (o.text("idEvent") != eventId) return@mapNotNull null
            val name = o.text("strChannel") ?: return@mapNotNull null
            SportsBroadcast(name, o.text("strCountry"))
        }.distinct()
        fun region(country: String?): String? = when (SportsTaxonomy.normalize(country.orEmpty())) {
            "australia", "new zealand" -> "AU"
            "united kingdom", "uk", "england", "scotland", "wales", "northern ireland" -> "UK"
            "ireland", "republic of ireland" -> "IE"
            "united states", "united states of america", "usa" -> "US"
            "canada" -> "CA"
            else -> null
        }
    }
}
