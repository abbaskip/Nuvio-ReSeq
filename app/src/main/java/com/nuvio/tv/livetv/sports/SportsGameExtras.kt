package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The bottom of a game's page: form, earlier meetings, match info and the table before it
 * starts; team stats, top performers (or line-ups) and win probability once it's on. A panel only
 * shows when ESPN has something for it.
 */

/** Rapid remote movement cancels the preview before it starts a summary request. */
@Composable
internal fun rememberGameExtras(ev: SportsEvent, viewModel: SportsViewModel, browsingList: Boolean = false): GameExtras? {
    var extras by remember(ev.id) { mutableStateOf<GameExtras?>(null) }
    val latest = rememberUpdatedState(ev)
    LaunchedEffect(ev.id, ev.state, ev.away?.score, ev.home?.score) {
        if (browsingList) delay(300)
        viewModel.repo.browsingAllowed.collectLatest { allowed ->
            if (!allowed) return@collectLatest
            while (true) {
                sportsCatching { viewModel.extras(latest.value) }.getOrNull()?.let { extras = it }
                if (latest.value.state != GameState.LIVE && !latest.value.awaitingUpdate &&
                    !(latest.value.state == GameState.PRE && latest.value.startMs <= System.currentTimeMillis())) break
                delay(30_000)
            }
        }
    }
    return extras
}

@Composable
internal fun rememberCricketPlayers(ev: SportsEvent, vm: SportsViewModel, hidden: Boolean): List<Performer> {
    if (hidden || ev.awaitingUpdate || ev.state == GameState.PRE) return emptyList()
    val id = ev.caFixtureId ?: ev.id.takeIf { it.startsWith("ca:") }?.substringAfter(':')?.toIntOrNull() ?: return emptyList()
    var players by remember(id, ev.state) { mutableStateOf(emptyList<Performer>()) }
    LaunchedEffect(id, ev.state) {
        vm.repo.browsingAllowed.collectLatest { allowed ->
            if (!allowed) return@collectLatest
            do {
                players = sportsCatching { vm.repo.cricketPlayers(id, ev.state == GameState.POST) }.getOrDefault(emptyList())
                if (ev.state == GameState.POST) break
                delay(if (ev.state == GameState.LIVE) 30_000L else 90_000L)
            } while (true)
        }
    }
    return players
}

@Composable
internal fun rememberCricketTable(ev: SportsEvent, extras: GameExtras?, vm: SportsViewModel, hidden: Boolean, preview: Boolean = false): Standings? {
    if (hidden || SportsCatalog.league(ev.leagueId)?.sport != Sport.CRICKET) return null
    val league = if (ev.caCompetitionId != null) ev.leagueId else extras?.cricketTableLeague ?: return null
    var table by remember(ev.id, league, ev.caCompetitionId) { mutableStateOf<Standings?>(null) }
    LaunchedEffect(ev.id, league, ev.caCompetitionId) {
        if (preview) delay(300)
        vm.repo.browsingAllowed.collectLatest { allowed ->
            if (allowed) table = sportsCatching { vm.repo.standings(league, caCompetitionId = ev.caCompetitionId) }.getOrNull()
        }
    }
    return table
}

private class MatchPage(val title: String, val content: @Composable () -> Unit)

/** Both entry points use the same sections and the same cached game summary. */
@Composable
internal fun MatchCentre(
    ev: SportsEvent,
    x: GameExtras?,
    cricketTable: Standings?,
    leagueFixtures: List<SportsEvent>,
    hideScores: Boolean,
    use24h: Boolean,
    us: Boolean,
    modifier: Modifier = Modifier
) {
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val awayColor = teamColor(ev.away, Color(0xFF5B8DEF))
    val homeColor = teamColor(ev.home, Color(0xFFE5484D))
    val gameNumbers = !hideScores && !ev.awaitingUpdate && ev.state != GameState.PRE
    val nearby = remember(ev.id, leagueFixtures) {
        leagueFixtures.asSequence().filter { it.id != ev.id && it.leagueId == ev.leagueId }
            .sortedBy { kotlin.math.abs(it.startMs - ev.startMs) }.take(5).sortedBy { it.startMs }.toList()
    }
    val pages = buildList {
        if (gameNumbers) {
            if (x != null && x.scoring.isNotEmpty()) add(MatchPage("Latest scoring") {
                x.scoring.takeLast(16).asReversed().forEach { PlayRow(it, sport) }
            })
            if (x != null && x.stats.isNotEmpty()) add(MatchPage("Team comparison") {
                StatsPanel(x, sides, awayColor, homeColor)
            })
            if (x != null && x.performers.isNotEmpty()) add(MatchPage("Player leaders") {
                LeadersPanel(x.performers, ev, awayColor, homeColor)
            })
            if (ev.hasScore && sides.size == 2 && (sport == Sport.CRICKET || sides.any { it.periods.isNotEmpty() })) {
                add(MatchPage(periodHeading(sport)) {
                    if (sport == Sport.CRICKET) CricketInningsTable(sides) else PeriodTable(sides)
                })
            }
        }
        if (!hideScores && ev.standings.isNotEmpty()) {
            val title = when (sport) {
                Sport.TENNIS -> "Matches"
                Sport.FIGHTING -> if (ev.state == GameState.PRE) "Fight card" else "Results"
                Sport.GOLF -> "Leaderboard"
                else -> if (ev.state == GameState.LIVE) "Running order" else "Event field"
            }
            add(MatchPage(title) { ev.standings.forEach { StandingRow(it) } })
        }
        if (!hideScores && cricketTable != null) {
            add(MatchPage("Competition table") { TablePanel(cricketSections(ev, cricketTable)) })
        }
        if (!hideScores && x != null) {
            if (x.form.isNotEmpty() || x.meetings.isNotEmpty()) add(MatchPage("Form & meetings") { FormPanel(x) })
            if (cricketTable == null && x.table.isNotEmpty()) add(MatchPage("League positions") { TablePanel(x.table) })
            if (x.lineups.isNotEmpty()) add(MatchPage("Starting line-ups") { LineupsPanel(x) })
            if (x.winHistory.size >= 2 || x.hasOdds) add(MatchPage("Win probability") { ChancePanel(ev, x, awayColor, homeColor) })
        }
        if (!hideScores && sides.size == 2 && sides.any { !it.record.isNullOrBlank() } && !gameNumbers) {
            add(MatchPage("Season records") {
                sides.forEach { side ->
                    LiveText(side.team.name, size = 13.sp, weight = FontWeight.Bold)
                    LiveText(side.record ?: "—", color = SportsLook.Dim, size = 18.sp)
                }
            })
        }
        if (nearby.isNotEmpty()) add(MatchPage("Around the league") {
            nearby.forEach { other ->
                val score = if (!hideScores && other.hasScore) other.ordered(sport).joinToString(" – ") { it.score.orEmpty() } else null
                val label = when (other.state) {
                    GameState.POST -> other.finishedLabel
                    GameState.LIVE -> other.liveLabel
                    else -> startLabel(other, use24h)
                }
                LiveText(other.title, size = 14.sp, weight = FontWeight.Bold, maxLines = 2)
                LiveText(listOfNotNull(label, score).joinToString("   "), color = SportsLook.Dim, size = 11.sp)
                Spacer(Modifier.height(5.dp))
            }
        })
        add(MatchPage("Fixture details") {
            LiveText(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(ev.startMs)) +
                " · " + startLabel(ev, use24h), size = 15.sp, weight = FontWeight.Bold)
            if (hideScores) LiveText("Scores hidden", color = SportsLook.Dim, size = 12.sp)
            if (!hideScores) {
                ev.toss?.let { LiveText(it, size = 12.sp, maxLines = 2) }
                ev.situation?.let { LiveText(it, size = 13.sp, weight = FontWeight.Bold) }
                listOfNotNull(ev.note, ev.subtitle, ev.about).distinct().forEach {
                    LiveText(it, size = 12.sp, maxLines = 8)
                }
            }
            if (x?.venue == null) ev.venue?.let { LiveText(it, size = 13.sp) }
            if (x?.hasMatchInfo == true) InfoPanel(x, us)
            if (ev.broadcasts.isNotEmpty()) LiveText("On TV  ·  " + ev.broadcasts.joinToString(), size = 12.sp, maxLines = 4)
            if (ev.broadcastCoverage.isNotEmpty()) LiveText("Reported coverage  ·  " +
                ev.broadcastCoverage.joinToString { it.name + (it.country?.let { c -> " ($c)" } ?: "") },
                color = SportsLook.Dim, size = 12.sp, maxLines = 6)
            if (ev.source == EventSource.THESPORTSDB) LiveText("TheSportsDB · limited free coverage" +
                (ev.updatedAt?.let { " · checked " + clock(it, use24h) } ?: ""), color = SportsLook.Dim, size = 11.sp)
        })
    }
    var selected by remember(ev.id) { mutableStateOf<String?>(null) }
    val index = pages.indexOfFirst { it.title == selected }.coerceAtLeast(0)
    val page = pages[index]
    val scroll = key(ev.id, page.title) { rememberScrollState() }
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 68.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    Column(modifier.sportsFrame(focused, accent = NuvioTheme.colors.Secondary)
        .onFocusChanged { focused = it.hasFocus }
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val distance = when {
                e.key == Key.DirectionDown && scroll.canScrollForward -> step
                e.key == Key.DirectionUp && scroll.canScrollBackward -> -step
                else -> return@onPreviewKeyEvent false
            }
            scope.launch { scroll.scrollBy(distance) }
            true
        }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            selected = pages[(index + 1) % pages.size].title
        }
        .padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText(league?.name ?: "Match centre", color = SportsLook.Dim, size = 11.sp, weight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            if (pages.size > 1) LiveText("${index + 1} / ${pages.size}", color = SportsLook.Dim, size = 11.sp)
        }
        LiveText(page.title, size = 19.sp, weight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            page.content()
        }
        val hint = listOfNotNull("OK · Next section".takeIf { pages.size > 1 },
            "↑ ↓ Scroll".takeIf { scroll.canScrollForward || scroll.canScrollBackward }).joinToString("     ")
        if (hint.isNotEmpty()) LiveText(hint, color = NuvioTheme.colors.Secondary, size = 10.sp,
            weight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun teamColor(side: Side?, fallback: Color): Color =
    side?.team?.let { if (stateCricketColor(it) != null || SportsCricketColors.color(it) != null) teamBandColor(it) else it.color?.let { color -> bandColor(Color(color)) } }?.takeIf { it.alpha > 0f } ?: fallback

@Composable
private fun PanelTitle(text: String) {
    LiveText(text, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
    Spacer(Modifier.height(5.dp))
}

// ------------------------------------------------------------------ before it starts

private val meetingDate = SimpleDateFormat("MMM yyyy", Locale.getDefault())

@Composable
private fun FormPanel(x: GameExtras) {
    if (x.form.isNotEmpty()) {
        PanelTitle("FORM · LAST FIVE")
        x.form.forEach { f ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(f.team, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold, modifier = Modifier.width(92.dp))
                f.results.forEach { r ->
                    Box(
                        Modifier.padding(end = 4.dp).size(18.dp).clip(RoundedCornerShape(4.dp)).background(
                            when (r) { "W" -> Color(0xFF2E9E5B); "L" -> Color(0xFFC0392B); else -> Color(0xFF5A5F6E) }
                        ),
                        contentAlignment = Alignment.Center
                    ) { LiveText(r, color = Color.White, size = 9.sp, weight = FontWeight.Black) }
                }
            }
        }
    }
    if (x.meetings.isNotEmpty()) {
        if (x.form.isNotEmpty()) Spacer(Modifier.height(8.dp))
        PanelTitle("LAST MEETINGS")
        x.meetings.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                LiveText(m.dateMs?.let { synchronized(meetingDate) { meetingDate.format(Date(it)) } } ?: "", color = SportsLook.Dim, size = 11.sp, modifier = Modifier.width(70.dp))
                LiveText(m.text, color = Color.White, size = 11.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoPanel(x: GameExtras, us: Boolean) {
    if (x.hasMatchInfo) {
        PanelTitle("MATCH INFO")
        val lines = listOfNotNull(
            listOfNotNull(x.venue, x.city).joinToString(", ").ifBlank { null },
            x.tempF?.let { f ->
                val t = if (us) "$f°F" else "${Math.round((f - 32) / 1.8)}°C"
                listOfNotNull(x.conditions, t).joinToString(" · ")
            },
            x.referee?.let { "Referee: $it" },
            x.attendance?.let { "Attendance: %,d".format(it) }
        )
        lines.forEachIndexed { i, l ->
            LiveText(l, color = if (i == 0) Color.White else Color(0xFFC9CCD6), size = 12.sp, weight = if (i == 0) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** "58% · 24% · 18%" as one bar in the teams' colours (home team first for football). */
@Composable
private fun ChanceBar(ev: SportsEvent, x: GameExtras, awayColor: Color, homeColor: Color) {
    val homeFirst = (SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER).homeFirst
    val parts = buildList {
        val h = Triple(x.homeWin ?: 0.0, homeColor, ev.home?.team?.abbr ?: "HOME")
        val a = Triple(x.awayWin ?: 0.0, awayColor, ev.away?.team?.abbr ?: "AWAY")
        val d = x.draw?.let { Triple(it, Color(0xFF5A5F6E), "DRAW") }
        if (homeFirst) { add(h); d?.let { add(it) }; add(a) } else { add(a); d?.let { add(it) }; add(h) }
    }.filter { it.first > 0.5 }
    Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp))) {
        parts.forEach { (p, c, _) ->
            Box(Modifier.weight(p.toFloat()).fillMaxHeight().background(c), contentAlignment = Alignment.Center) {
                if (p >= 12) LiveText("${Math.round(p)}%", color = Color.White, size = 9.sp, weight = FontWeight.Black)
            }
        }
    }
    Spacer(Modifier.height(3.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        parts.forEach { (_, _, label) -> LiveText(label.uppercase(Locale.ROOT), color = SportsLook.Dim, size = 8.sp, weight = FontWeight.Black) }
    }
}

private fun tableColumnWidth(label: String?) = when (label) {
    "PCT", "PTS", "NRR" -> 43.dp
    "M", "W", "D", "L" -> 24.dp
    else -> 32.dp
}

@Composable
private fun TablePanel(sections: List<TeamTableSection>) {
    sections.forEachIndexed { sectionIndex, section ->
        if (sectionIndex > 0) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(SportsLook.Dim))
            Spacer(Modifier.height(10.dp))
        }
        LiveText(section.heading, color = Color.White, size = 13.sp, weight = FontWeight.Bold, maxLines = 2)
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp)) {
            LiveText("Rank", color = SportsLook.Dim, size = 10.sp, modifier = Modifier.weight(1f))
            section.columns.forEach {
                LiveText(it, color = SportsLook.Dim, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.width(tableColumnWidth(it)))
            }
        }
        section.rows.forEach { row ->
            val ink = if (row.highlighted) Color.White else SportsLook.Dim
            val weight = if (row.highlighted) FontWeight.ExtraBold else FontWeight.Normal
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                .background(if (row.highlighted) NuvioTheme.colors.Secondary.copy(alpha = 0.20f) else Color.Transparent)
                .padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(row.pos, color = ink, size = 12.sp, weight = weight, modifier = Modifier.width(27.dp))
                LiveText(row.team, color = ink, size = 12.sp, weight = weight, modifier = Modifier.weight(1f), maxLines = 2)
                row.values.forEachIndexed { i, value -> LiveText(value, color = ink, size = 12.sp, weight = weight,
                    modifier = Modifier.width(tableColumnWidth(section.columns.getOrNull(i)))) }
            }
        }
    }
}

// ------------------------------------------------------------------ while it's on, and after

@Composable
private fun StatsPanel(x: GameExtras, sides: List<Side>, awayColor: Color, homeColor: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        sides.forEach { LiveText(it.team.name, color = Color.White, size = 12.sp, weight = FontWeight.Bold) }
    }
    // In the order the page shows the teams (home first for football).
    val homeFirst = sides.firstOrNull()?.home == true
    x.stats.forEach { s ->
        val (lv, rv) = if (homeFirst) s.home to s.away else s.away to s.home
        val (ln, rn) = if (homeFirst) s.homeValue to s.awayValue else s.awayValue to s.homeValue
        val (lc, rc) = if (homeFirst) homeColor to awayColor else awayColor to homeColor
        Column(Modifier.padding(vertical = 3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LiveText(lv, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
                LiveText(s.label, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 1,
                    align = androidx.compose.ui.text.style.TextAlign.Center)
                LiveText(rv, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(2.dp))
            val total = (ln ?: 0.0) + (rn ?: 0.0)
            Row(Modifier.fillMaxWidth().height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val lw = if (total > 0) ((ln ?: 0.0) / total).toFloat().coerceIn(0.03f, 0.97f) else 0.5f
                Box(Modifier.weight(lw).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(lc))
                Box(Modifier.weight(1f - lw).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(rc))
            }
        }
    }
}

@Composable
internal fun BasketballGamePanels(ev: SportsEvent, extras: GameExtras, modifier: Modifier = Modifier) {
    val awayColor = teamColor(ev.away, Color(0xFF5B8DEF))
    val homeColor = teamColor(ev.home, Color(0xFFE5484D))
    val leaders = extras.performers.ifEmpty { extras.playerLines }
    key(ev.id) {
        Row(modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (extras.stats.isNotEmpty()) {
                BasketballPanel("TEAM STATS", Modifier.weight(1f)) {
                    StatsPanel(extras, ev.ordered(Sport.BASKETBALL), awayColor, homeColor)
                }
            }
            if (leaders.isNotEmpty()) {
                BasketballPanel("TOP PERFORMERS", Modifier.weight(1f)) {
                    LeadersPanel(leaders, ev, awayColor, homeColor)
                }
            }
        }
    }
}

@Composable
private fun BasketballPanel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxHeight().onFocusChanged { focused = it.hasFocus }
        .sportsFrame(focused, accent = NuvioTheme.colors.Secondary).padding(8.dp)) {
        PanelTitle(title)
        Column(Modifier.verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun LeadersPanel(leaders: List<Performer>, ev: SportsEvent, awayColor: Color, homeColor: Color) {
    leaders.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveText(p.category, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, modifier = Modifier.width(76.dp), maxLines = 1)
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (p.teamId == ev.home?.team?.id) homeColor else awayColor))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                LiveText(p.name, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                LiveText(p.line, color = Color(0xFFC9CCD6), size = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LineupsPanel(x: GameExtras) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        x.lineups.forEach { l ->
            Column(Modifier.weight(1f)) {
                LiveText(listOfNotNull(l.team, l.formation).joinToString(" · "), color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                l.players.take(11).forEach { LiveText(it, color = Color(0xFFC9CCD6), size = 10.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ChancePanel(ev: SportsEvent, x: GameExtras, awayColor: Color, homeColor: Color) {
    val home = x.homeWin
    val away = x.awayWin
    if (home != null && away != null) {
        val homeAhead = home >= away
        val name = (if (homeAhead) ev.home else ev.away)?.team?.name ?: ""
        LiveText("$name ${Math.round(maxOf(home, away))}%", color = Color.White, size = 16.sp, weight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
    }
    if (x.winHistory.size >= 2) {
        // The home team's chance through the game: up is home, down is away, the middle is even.
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val mid = size.height / 2
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val path = Path()
            x.winHistory.forEachIndexed { i, p ->
                val px = size.width * i / (x.winHistory.size - 1)
                val py = size.height * (1f - p.coerceIn(0f, 1f))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, if ((x.winHistory.last()) >= 0.5f) homeColor else awayColor, style = Stroke(width = 4f))
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LiveText("START", color = SportsLook.Dim, size = 8.sp, weight = FontWeight.Black)
            LiveText(if (ev.state == GameState.LIVE) "NOW" else "END", color = SportsLook.Dim, size = 8.sp, weight = FontWeight.Black)
        }
    } else if (home != null && away != null) {
        ChanceBar(ev, x, awayColor, homeColor)
    }
    x.attendance?.let {
        Spacer(Modifier.height(6.dp))
        LiveText(listOfNotNull(x.venue, "attendance %,d".format(it)).joinToString(" · "), color = Color(0xFFC9CCD6), size = 11.sp, maxLines = 1)
    }
}
