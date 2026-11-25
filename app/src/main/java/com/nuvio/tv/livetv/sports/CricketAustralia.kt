package com.nuvio.tv.livetv.sports

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Secondary fixture/status feed used by cricket.com.au. No account or API key required. */
internal class CricketAustralia {
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    suspend fun fixtures(format: Int, now: Long): String {
        val url = "https://apiv2.cricket.com.au/web/fixtures/yearfilter".toHttpUrl().newBuilder()
            .addQueryParameter("gameType", format.toString())
            .addQueryParameter("startDateTime", Instant.ofEpochMilli(now - 6 * DAY).toString())
            .addQueryParameter("endDateTime", Instant.ofEpochMilli(now + 35 * DAY).toString())
            .addQueryParameter("limit", "100")
            .addQueryParameter("isInningInclude", "true")
            .addQueryParameter("jsconfig", "eccn:true").addQueryParameter("format", "json").build()
        return http.newCall(Request.Builder().url(url).build()).consumeSportsResponse { response ->
            if (!response.isSuccessful) throw IOException("Cricket Australia HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Cricket Australia empty response")
        }
    }

    suspend fun scorecard(id: Int): String {
        require(id > 0)
        val url = "https://apiv2.cricket.com.au/web/views/scorecard".toHttpUrl().newBuilder()
            .addQueryParameter("fixtureId", id.toString()).addQueryParameter("jsconfig", "eccn:true")
            .addQueryParameter("format", "json").build()
        return http.newCall(Request.Builder().url(url).build()).consumeSportsResponse { response ->
            if (!response.isSuccessful) throw IOException("Cricket Australia HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Empty scorecard")
        }
    }

    suspend fun seriesPage(id: Int): String {
        require(id > 0)
        val request = Request.Builder().url("https://www.cricket.com.au/matches/series/CA%3A$id").build()
        return http.newCall(request).consumeSportsResponse { response ->
            if (!response.isSuccessful) throw IOException("Cricket Australia HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Empty series page")
        }
    }

    companion object {
        val domesticTables = setOf("ca/shield", "ca/one-day")

        fun parseTable(leagueId: String, html: String, now: Long): Standings? {
            val doc = org.jsoup.Jsoup.parse(html)
            val title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { SportsCatalog.league(leagueId)?.name.orEmpty() }
            val groups = mutableListOf<StandingsGroup>()
            val offered = linkedMapOf<String, StandingsColumn>()
            val fields = mapOf("played" to StandingsColumn("gamesPlayed", "M"), "won" to StandingsColumn("wins", "W"),
                "lost" to StandingsColumn("losses", "L"), "drawn" to StandingsColumn("draws", "D"),
                "points" to StandingsColumn("points", "PTS"), "netRunRate" to StandingsColumn("netRunRate", "NRR"))
            for (table in doc.select(".w-series-standings table")) {
                val columns = table.select("thead th").drop(1).map { fields[it.attr("aria-labelledby").substringAfterLast('_')] }
                if (columns.none { it?.key == "points" }) continue
                val rows = table.select("tbody tr").mapNotNull { tr ->
                    val team = tr.selectFirst(".o-standings__team-name")?.text() ?: return@mapNotNull null
                    val id = tr.selectFirst(".o-standings__team-flag")?.attr("data-id")?.takeIf { it.isNotBlank() } ?: team
                    val rank = tr.selectFirst(".o-standings__team-position")?.text()?.toIntOrNull() ?: return@mapNotNull null
                    val short = tr.selectFirst(".w-series-standings__team-name--short")?.text() ?: team
                    val cells = tr.select("td")
                    val values = columns.mapIndexedNotNull { i, col -> col?.let { c -> cells.getOrNull(i)?.text()?.let { c.key to it } } }.toMap()
                    if (values["points"].isNullOrBlank()) null else StandingsRow(id, team.removeSuffix(" Men"), short, short,
                        null, rank, values)
                }
                if (rows.isEmpty()) continue
                columns.filterNotNull().forEach { offered[it.key] = it }
                val label = table.closest(".o-standings__group")?.selectFirst("h4,h5")?.text() ?: title
                groups += StandingsGroup(label, null, rows)
            }
            if (groups.isEmpty()) return null
            return Standings(leagueId, title, StandingsKind.TABLE, offered.values.toList(), groups, "Cricket Australia", now)
        }

        private const val DAY = 86_400_000L
        val formats = listOf(1, 2, 3, 4, 5)
        val leagues = listOf(
            League("ca/test", Sport.CRICKET, "Test Cricket", "TESTS", national = true, minutes = 7200),
            League("ca/odi", Sport.CRICKET, "ODIs", "ODI", guideWords = listOf("ODI Internationals"), national = true, minutes = 540),
            League("ca/t20i", Sport.CRICKET, "T20 Internationals", "T20I", national = true, minutes = 240),
            League("ca/shield", Sport.CRICKET, "Sheffield Shield", "SHIELD", regions = setOf("AU"), minutes = 5760),
            League("ca/one-day", Sport.CRICKET, "One-Day Cup", "ONE-DAY CUP", regions = setOf("AU"), minutes = 540)
        )
        fun interval(format: Int): Long = if (format in 1..3) 5 * 60_000L else 15 * 60_000L
        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
        private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
        private fun timestamp(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        fun parse(raw: String, checkedAt: Long): List<SportsEvent> {
            val root = Json.parseToJsonElement(raw).jsonObject
            val fixtures = root["fixtures"] as? JsonArray ?: throw IOException("Cricket Australia missing fixtures")
            return fixtures.mapNotNull { row ->
                val o = row as? JsonObject ?: return@mapNotNull null
                val format = o.number("gameTypeId") ?: return@mapNotNull null
                if (format !in formats || o.flag("isWomensMatch")) return@mapNotNull null
                val competition = (o["competition"] as? JsonObject)?.text("name").orEmpty()
                // Never import BBL or unrelated domestic competitions from this secondary source.
                if (format == 4 && !competition.contains("Sheffield Shield", true)) return@mapNotNull null
                if (format == 5 && !competition.contains("One-Day Cup", true)) return@mapNotNull null
                val league = leagues[format - 1]
                val id = o.text("id") ?: return@mapNotNull null
                val start = timestamp(o.text("startDateTime")) ?: return@mapNotNull null
                val status = o.text("gameStatusId").orEmpty()
                val state = when {
                    status in setOf("Abandoned", "Cancelled", "Postponed") -> GameState.OFF
                    o.flag("isCompleted") -> GameState.POST
                    status in setOf("Stumps", "RainDelay", "Delayed", "Suspended", "BadLight") -> GameState.OFF
                    status in setOf("InPlay", "Drinks", "Lunch", "Tea", "InningsBreak") -> GameState.LIVE
                    o.flag("isLive") -> GameState.LIVE
                    o.flag("isInProgress") -> GameState.OFF
                    else -> GameState.PRE
                }
                fun side(home: Boolean): Side? {
                    val t = o[if (home) "homeTeam" else "awayTeam"] as? JsonObject ?: return null
                    val teamId = t.text("id") ?: return null
                    val name = t.text("name")?.removeSuffix(" Men") ?: return null
                    val innings = (o["innings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        .filter { it.text("battingTeamId") == teamId }.sortedBy { it.number("inningNumber") }
                        .mapNotNull { inning -> inning.text("runsScored")?.let { runs ->
                            val wickets = inning.text("numberOfWicketsFallen") ?: "0"
                            "$runs/$wickets" + (if (inning.flag("isDeclared")) "d" else "") +
                                (inning.text("oversBowled")?.let { " ($it ov)" } ?: "")
                        } }
                    return Side(TeamInfo(league.id, teamId, name, name, t.text("shortName") ?: name,
                        null, null, null, null), innings.lastOrNull(), null, false, home, innings)
                }
                val home = side(true)
                val away = side(false)
                SportsEvent("ca:$id", league.id, start, true, state,
                    if (home != null && away != null) "${home.team.name} v ${away.team.name}" else o.text("name") ?: competition,
                    competition, o.text("gameStatus") ?: if (state == GameState.OFF) "In progress" else null,
                    away, home, situation = o.text("resultText"), source = EventSource.CRICKET_AUSTRALIA,
                    endMs = timestamp(o.text("endDateTime")), updatedAt = checkedAt,
                    caFixtureId = id.toIntOrNull(),
                    caCompetitionId = (o["competition"] as? JsonObject)?.number("id")?.takeIf { format in 4..5 && it > 0 },
                    venue = (o["venue"] as? JsonObject)?.let { listOfNotNull(it.text("name"), it.text("location")).distinct().joinToString(", ").ifBlank { null } },
                    toss = o.text("tossResult")?.takeIf { it.isNotBlank() },
                    note = o.number("matchDay")?.takeIf { it > 0 }?.let { "Day $it" })
            }
        }

        private fun team(name: String) = SportsTaxonomy.normalize(name).removeSuffix(" men")
            .replace(Regex(" (tests?|odis?|t20i?)$"), "")
        fun same(a: SportsEvent, b: SportsEvent): Boolean = a.sides.size == 2 && b.sides.size == 2 &&
            kotlin.math.abs(a.startMs - b.startMs) < 12 * 3_600_000L &&
            a.sides.map { team(it.team.fullName.ifBlank { it.team.name }) }.toSet() == b.sides.map { team(it.team.fullName.ifBlank { it.team.name }) }.toSet()

        /** Retain existing IDs, team identities and channel matches when enriching another provider. */
        fun merge(existing: List<SportsEvent>, secondary: List<SportsEvent>): List<SportsEvent> {
            val used = mutableSetOf<String>()
            val merged = existing.mapNotNull { old ->
                if (SportsCatalog.league(old.leagueId)?.sport != Sport.CRICKET || old.source == EventSource.GUIDE) return@mapNotNull old
                val ca = secondary.singleOrNull { same(old, it) } ?: return@mapNotNull old
                if (!used.add(ca.id)) return@mapNotNull null
                fun score(side: Side?): Side? = side?.let { s -> ca.sides.firstOrNull { team(it.team.fullName.ifBlank { it.team.name }) == team(s.team.fullName.ifBlank { s.team.name }) }
                    ?.let { s.copy(score = it.score, periods = it.periods) } ?: s }
                old.copy(state = ca.state, status = ca.status, endMs = ca.endMs, situation = ca.situation, awaitingUpdate = false, caCompetitionId = ca.caCompetitionId,
                    caFixtureId = ca.caFixtureId, venue = ca.venue ?: old.venue, toss = ca.toss ?: old.toss,
                    home = score(old.home), away = score(old.away), updatedAt = ca.updatedAt, note = ca.note)
            }
            return merged + secondary.filterNot { it.id in used }
        }
    }
}
