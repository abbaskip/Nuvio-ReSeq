package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun StandingsRow.matches(team: TeamInfo): Boolean {
    val names = setOf(team.fullName, team.name, team.abbr).filter { it.isNotBlank() }
    return names.any { candidate -> listOf(name, abbr, shortName).any { it.equals(candidate, ignoreCase = true) } }
}

internal fun cricketSections(ev: SportsEvent, table: Standings): List<TeamTableSection> {
    // Keep the columns readable on a TV. Percentage decides WTC places, net run rate breaks limited-overs ties.
    val columns = table.columns.filter { it.key in setOf("gamesPlayed", "wins", "losses", "draws", "points", "pointsPct", "netRunRate") }
    return table.groups.map { group ->
        TeamTableSection(group.name, columns.map { it.label }, group.rows.map { row ->
            TableRow(row.pos.toString(), row.name, columns.map { row.values[it.key] ?: "—" },
                ev.sides.any { row.matches(it.team) })
        })
    }
}

internal data class PinnedStat(val label: String, val value: String, val second: String? = null)
internal data class PinnedSummary(val title: String, val rows: List<PinnedStat>, val teams: List<String> = emptyList())

internal fun pinnedSummary(ev: SportsEvent, x: GameExtras?, table: Standings?, hidden: Boolean, use24h: Boolean): PinnedSummary {
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    if (!hidden && !ev.awaitingUpdate) {
        if (ev.state != GameState.PRE) {
            if (x != null && x.playerLines.isNotEmpty()) {
                val heading = when (x.playerLines.first().category) {
                    "Box score" -> "Standout performers"
                    "At the crease" -> "At the crease"
                    else -> "Highest innings & best bowling"
                }
                return PinnedSummary(heading, x.playerLines.map { PinnedStat(it.name, it.line) })
            }
            if (sport == Sport.CRICKET) {
                val match = listOfNotNull(ev.situation?.let { PinnedStat("Match", it) }, ev.toss?.let { PinnedStat("Toss", it) },
                    (ev.venue ?: x?.venue)?.let { PinnedStat("Venue", it) })
                if (match.isNotEmpty()) return PinnedSummary("Match details", match)
            }
            if (sides.size == 2 && x != null && x.stats.isNotEmpty()) {
                val rows = x.stats.take(4).map { stat ->
                    if (sides.first().home) PinnedStat(stat.label, stat.home, stat.away)
                    else PinnedStat(stat.label, stat.away, stat.home)
                }
                return PinnedSummary("Key stats", rows, sides.map { it.team.abbr.ifBlank { it.team.name } })
            }
            val leaders = x?.performers.orEmpty().take(3).map { PinnedStat(it.category, "${it.name}  ·  ${it.line}") }
            if (leaders.isNotEmpty()) return PinnedSummary("Player leaders", leaders)
            val scoring = x?.scoring.orEmpty().takeLast(3).asReversed().map { PinnedStat(it.clock, it.text) }
            if (scoring.isNotEmpty()) return PinnedSummary("Latest scoring", scoring)
            val periods = sides.filter { it.periods.isNotEmpty() }.map { PinnedStat(it.team.name, it.periods.joinToString("   ·   ")) }
            if (sport != Sport.CRICKET && periods.isNotEmpty()) return PinnedSummary(periodHeading(sport), periods)
            if (ev.standings.isNotEmpty()) return PinnedSummary("Event leaders", ev.standings.take(4).map {
                PinnedStat(it.pos, listOfNotNull(it.name, it.detail).joinToString("  ·  "))
            })
        }
        if (table != null) {
            val rows = sides.mapNotNull { side ->
                val row = table.groups.firstNotNullOfOrNull { group -> group.rows.find { it.matches(side.team) } }
                row?.let { PinnedStat(side.team.name, listOfNotNull(Standings.ordinal(it.pos),
                    it.values["points"]?.let { pts -> "$pts pts" }, it.values["pointsPct"]?.let { pct -> "$pct%" }).joinToString("   ·   ")) }
            }
            if (rows.isNotEmpty()) return PinnedSummary("Competition positions", rows)
        }
        val form = x?.form.orEmpty().filter { it.results.isNotEmpty() }.map { PinnedStat(it.team, it.results.takeLast(5).joinToString("  ")) }
        if (form.isNotEmpty()) return PinnedSummary("Recent form", form)
        val records = sides.mapNotNull { side -> (side.record ?: side.team.record)?.takeIf { it.isNotBlank() }?.let { PinnedStat(side.team.name, it) } }
        if (records.isNotEmpty()) return PinnedSummary("Season records", records)
        val standings = x?.table.orEmpty().flatMap { section ->
            section.rows.filter { it.highlighted }.map { row -> PinnedStat(row.team, row.pos.toIntOrNull()?.let(Standings::ordinal) ?: row.pos) }
        }
        if (standings.isNotEmpty()) return PinnedSummary("League positions", standings)
    }
    val date = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ev.startMs))
    val facts = listOfNotNull((x?.venue ?: ev.venue)?.let { PinnedStat("Venue", it) },
        PinnedStat("Scheduled", listOfNotNull(date, startLabel(ev, use24h).takeIf { ev.hasTime }).joinToString("  ·  ")),
        league?.let { PinnedStat("Competition", it.name) })
    return PinnedSummary("Event details", facts)
}

@Composable
internal fun PinnedMatchStats(ev: SportsEvent, extras: GameExtras?, table: Standings?, hidden: Boolean, use24h: Boolean, modifier: Modifier) {
    if (SportsCatalog.league(ev.leagueId)?.sport == Sport.BASKETBALL && !hidden && !ev.awaitingUpdate &&
        ev.state != GameState.PRE && extras != null &&
        (extras.stats.isNotEmpty() || extras.performers.isNotEmpty() || extras.playerLines.isNotEmpty())
    ) {
        BasketballGamePanels(ev, extras, modifier)
        return
    }
    val summary = remember(ev, extras, table, hidden, use24h) { pinnedSummary(ev, extras, table, hidden, use24h) }
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = 9.dp)) {
        var available = maxHeight - 29.dp
        val visible = summary.rows.mapNotNull { row ->
            val height = if (row.second == null && row.value.length > 44) 40.dp else 24.dp
            if (available < height) null else (row to height).also { available -= height }
        }
        if (visible.isEmpty()) return@BoxWithConstraints
        Column(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary)
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().height(17.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(summary.title.uppercase(Locale.ROOT), color = SportsLook.Dim, weight = FontWeight.Bold, size = 10.sp, modifier = Modifier.weight(1f))
                summary.teams.forEach { LiveText(it, color = SportsLook.Dim, weight = FontWeight.Bold, size = 10.sp, modifier = Modifier.width(65.dp), align = TextAlign.End) }
            }
            visible.forEach { (row, height) ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(SportsLook.Border))
                Row(Modifier.fillMaxWidth().height(height - 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveText(row.label, color = SportsLook.Dim, size = 12.sp, maxLines = 1,
                        modifier = if (row.second == null) Modifier.widthIn(max = 128.dp).padding(end = 10.dp) else Modifier.weight(1f))
                    LiveText(row.value, size = 13.sp, weight = FontWeight.Bold, maxLines = if (height > 24.dp) 2 else 1, align = TextAlign.End,
                        modifier = if (row.second == null) Modifier.weight(1f) else Modifier.width(65.dp))
                    row.second?.let { LiveText(it, size = 13.sp, weight = FontWeight.Bold, modifier = Modifier.width(65.dp), align = TextAlign.End) }
                }
            }
        }
    }
}
