package com.nuvio.tv.livetv.sports

import org.json.JSONArray
import org.json.JSONObject

internal object SportsPlayerStats {
    private fun JSONArray?.rows() = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
    private fun JSONObject.text(key: String) = optString(key, "").takeUnless { it.isBlank() || it == "null" }
    private fun JSONObject.number(key: String) = text(key)?.toIntOrNull()

    private data class PlayerLine(val performer: Performer, val rank: Int, val tie: Int)

    fun basketball(root: JSONObject): List<Performer> {
        val teams = root.optJSONObject("boxscore")?.optJSONArray("players").rows().map { team ->
            val abbr = team.optJSONObject("team")?.text("abbreviation")
            team.optJSONArray("statistics").rows().flatMap { group ->
                val labels = group.optJSONArray("labels") ?: return@flatMap emptyList()
                val columns = (0 until labels.length()).associateBy { labels.optString(it) }
                group.optJSONArray("athletes").rows().mapNotNull { entry ->
                    if (entry.optBoolean("didNotPlay")) return@mapNotNull null
                    val name = entry.optJSONObject("athlete")?.text("displayName") ?: return@mapNotNull null
                    val stats = entry.optJSONArray("stats") ?: return@mapNotNull null
                    fun stat(label: String) = columns[label]?.let { stats.optString(it).toIntOrNull() }
                    val pts = stat("PTS") ?: return@mapNotNull null
                    val reb = stat("REB") ?: return@mapNotNull null
                    val ast = stat("AST") ?: return@mapNotNull null
                    if (pts + reb + ast == 0) return@mapNotNull null
                    val line = listOfNotNull(abbr, "$pts PTS", "$reb REB", "$ast AST").joinToString("  ·  ")
                    PlayerLine(Performer("Box score", name, line, null), pts + reb + ast, pts)
                }
            }.distinctBy { it.performer.name }.sortedWith(compareByDescending<PlayerLine> { it.rank }.thenByDescending { it.tie })
        }
        val starters = teams.mapNotNull { it.firstOrNull() }.sortedByDescending { it.rank }
        return (starters + teams.flatMap { it.drop(1) }.sortedByDescending { it.rank }).take(4).map { it.performer }
    }

    private data class Innings(val name: String, val runs: Int, val balls: Int?, val notOut: Boolean, val active: Boolean) {
        fun line(category: String) = Performer(category, name, "$runs" + (if (notOut) "*" else "") +
            (balls?.let { " ($it balls)" } ?: "") + (if (category == "Batting") "  ·  batting" else ""), null)
    }
    private data class Bowling(val name: String, val wickets: Int, val runs: Int) {
        fun line() = Performer("Bowling", name, "$wickets/$runs  ·  bowling", null)
    }
    private fun leaders(bat: List<Innings>, bowl: List<Bowling>): List<Performer> {
        val batting = bat.sortedByDescending { it.runs }.distinctBy { it.name }.take(2).map { it.line("Batting") }
        val bowling = bowl.filter { it.wickets > 0 }.sortedWith(compareByDescending<Bowling> { it.wickets }.thenBy { it.runs })
            .distinctBy { it.name }.take(2).map { it.line() }
        return (0..1).flatMap { listOfNotNull(batting.getOrNull(it), bowling.getOrNull(it)) }
    }

    fun cricketAustralia(root: JSONObject, matchId: Int): List<Performer> {
        val fixture = root.optJSONObject("fixture") ?: return emptyList()
        if (fixture.number("id") != matchId) return emptyList()
        val players = root.optJSONArray("players").rows().associate { it.optString("id") to it.text("name") }
        val innings = fixture.optJSONArray("innings").rows().sortedBy { it.optInt("inningNumber") }
        fun batting(i: JSONObject) = i.optJSONArray("batsmen").rows().mapNotNull { b ->
            val name = players[b.optString("playerId")] ?: return@mapNotNull null
            Innings(name, b.number("runsScored") ?: return@mapNotNull null, b.number("ballsFaced"),
                b.text("dismissalTypeId").equals("NotOut", true), b.optBoolean("isBatting") && !b.optBoolean("isOut"))
        }
        if (!fixture.optBoolean("isCompleted")) {
            val current = innings.lastOrNull() ?: return emptyList()
            if (current.optBoolean("isDeclared") || current.optInt("numberOfWicketsFallen") >= 10 ||
                fixture.text("gameStatusId") == "InningsBreak") return emptyList()
            return batting(current).filter { it.active && it.notOut }.take(2).map { it.line("At the crease") }
        }
        val bowling = innings.flatMap { i -> i.optJSONArray("bowlers").rows().mapNotNull { b ->
            Bowling(players[b.optString("playerId")] ?: return@mapNotNull null,
                b.number("wicketsTaken") ?: return@mapNotNull null, b.number("runsConceded") ?: return@mapNotNull null)
        } }
        return leaders(innings.flatMap(::batting), bowling)
    }

    fun espnCricket(root: JSONObject, state: GameState): List<Performer> {
        if (state == GameState.PRE) return emptyList()
        val rosters = root.optJSONArray("rosters").rows().flatMap { it.optJSONArray("roster").rows() }
        if (state == GameState.POST) {
            // Matchcards show only one innings. Each player's roster carries all their innings.
            val bats = mutableListOf<Innings>()
            val bowl = mutableListOf<Bowling>()
            for (player in rosters) {
                val name = player.optJSONObject("athlete")?.text("displayName") ?: continue
                for (inning in player.optJSONArray("linescores").rows()) {
                    val stats = inning.optJSONArray("linescores").rows().flatMap { line ->
                        line.optJSONObject("statistics")?.optJSONArray("categories").rows().flatMap { it.optJSONArray("stats").rows() }
                    }.associate { it.optString("name") to it.number("value") }
                    val runs = stats["runs"]
                    if (stats["batted"] == 1 && runs != null) bats += Innings(name, runs, stats["ballsFaced"], stats["notouts"] == 1, false)
                    val wickets = stats["wickets"]
                    if (wickets != null && stats["conceded"] != null) bowl += Bowling(name, wickets, stats.getValue("conceded")!!)
                }
            }
            return leaders(bats, bowl)
        }
        val header = root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.optJSONObject("status")
        val period = header?.number("period") ?: return emptyList()
        val card = root.optJSONArray("matchcards").rows().find { it.number("inningsNumber") == period && it.text("headline") == "Batting" }
        return card?.optJSONArray("playerDetails").rows().filter { it.text("dismissal").equals("not out", true) }.mapNotNull {
            val name = it.text("playerName") ?: return@mapNotNull null
            Innings(name, it.number("runs") ?: return@mapNotNull null, it.number("ballsFaced"), true, true).line("At the crease")
        }.take(2)
    }
}
