package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.sports.*

@Composable
internal fun MobileSportsTools(section: String, close: () -> Unit, initialLeague: String? = null, vm: SportsViewModel = hiltViewModel()) {
    val follows by vm.follows.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val allowed by vm.repo.browsingAllowed.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleStartEffect(vm) {
        vm.repo.setViewing(true)
        onStopOrDispose { vm.repo.setViewing(false) }
    }
    val extra by SportsCatalog.extraFlow.collectAsStateWithLifecycle()
    var chosen by rememberSaveable(section, initialLeague) { mutableStateOf(initialLeague) }
    var query by rememberSaveable { mutableStateOf("") }
    var teamSearch by rememberSaveable(chosen) { mutableStateOf("") }
    val leagues = remember(follows, settings, extra, section, initialLeague) {
        if (section == "Tables") (listOfNotNull(initialLeague?.let(SportsCatalog::league)) + vm.repo.tableLeagues(follows)).distinctBy { it.id }
        else SportsCatalog.browseOrdered(settings)
    }
    val league = leagues.find { it.id == chosen } ?: if (section == "Tables") leagues.firstOrNull() else null
    val teams by produceState<List<TeamInfo>?>(null, league, section, allowed) {
        value = if (section == "Teams" && league != null && allowed) sportsCatching { vm.repo.teams(league) }.getOrNull().orEmpty() else null
    }
    var attempt by remember { mutableIntStateOf(0) }
    val tables by vm.repo.standings.collectAsStateWithLifecycle()
    var loading by remember(league) { mutableStateOf(false) }
    LaunchedEffect(league, section, attempt, allowed) {
        if (section == "Tables" && league != null && allowed) {
            loading = true
            try { vm.repo.standings(league.id, force = attempt > 0) } finally { loading = false }
        }
    }
    val table = tables[league?.id]
    var view by rememberSaveable(league?.id) { mutableStateOf(StandingsView.LEAGUE) }
    var conference by rememberSaveable(league?.id) { mutableStateOf<String?>(null) }
    var team by remember { mutableStateOf<TeamInfo?>(null) }
    var word by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    TouchSheet(close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { BrowseHeading(section, close) }
            if (section == "Alerts") {
                item { Action("Hide scores", selected = settings.hideScores) { vm.repo.updateSettings { it.copy(hideScores = !it.hideScores) } } }
                item { Action("Score alerts", selected = settings.scoringAlerts) { vm.repo.updateSettings { it.copy(scoringAlerts = !it.scoringAlerts) } } }
                item { Action("Remind me about my teams", selected = settings.remindMyTeams) { vm.repo.updateSettings { it.copy(remindMyTeams = !it.remindMyTeams) } } }
                item { Action("Switch to a game at kickoff", selected = settings.autoSwitch) { vm.repo.updateSettings { it.copy(autoSwitch = !it.autoSwitch) } } }
                item { Label("Reminder time"); LazyRow { items(listOf(0, 5, 10, 15, 30, 60)) { minutes ->
                    Action(if (minutes == 0) "At start" else "$minutes min", selected = settings.remindMinutes == minutes) { vm.repo.updateSettings { it.copy(remindMinutes = minutes) } }
                } } }
                items(follows.reminders, key = { it.eventId }) { reminder -> Action("Remove reminder · " + reminder.title) { vm.repo.dropReminder(reminder.eventId) } }
            } else {
                item { MobileTextInput("Find competition", query) { query = it } }
                item { LazyRow { items(leagues.filter { it.name.contains(query, true) || it.sport.label.contains(query, true) }, key = { it.id }) { l ->
                    Action(l.name, selected = league?.id == l.id) { chosen = l.id }
                } } }
                if (section == "Teams") {
                    item { SectionTitle("Followed teams") }
                    items(follows.teams) { t -> Action("Unfollow " + t.name + " · " + (SportsCatalog.league(t.leagueId)?.name ?: t.leagueId)) {
                        vm.repo.updateFollows { it.copy(teams = it.teams - t) }
                    } }
                    item { Label("Follow a name in the guide"); MobileTextInput("Name", word) { word = it }; Action("Add name") { if (word.isNotBlank()) { vm.repo.addWord(word); word = "" } } }
                    items(follows.words) { w -> Action("Remove " + w) { vm.repo.removeWord(w) } }
                    league?.let { l ->
                        item { SectionTitle(l.name); Action("Follow league", selected = l.id in follows.leagues) { vm.repo.toggleLeague(l.id) } }
                        item { Action("Pin to Sports Hub", selected = l.id in follows.pins) { vm.repo.togglePin(l.id) } }
                        item { MobileTextInput("Find team", teamSearch) { teamSearch = it } }
                        if (teams == null && allowed) item { Label("Loading teams…") }
                        items(teams.orEmpty().filter { it.name.contains(teamSearch, true) }) { t -> Action(t.name, selected = follows.followsTeam(t)) { vm.repo.toggleTeam(t) } }
                    }
                } else if (table != null && league != null) {
                    item { Label(listOfNotNull(league.name, table.season).joinToString(" · "), strong = true) }
                    if (table.conferences.isNotEmpty()) item { LazyRow {
                        items(StandingsView.entries.toList()) { v -> Action(v.name.lowercase().replaceFirstChar(Char::uppercase), selected = view == v) { view = v } }
                        items(listOf<String?>(null) + table.conferences) { c -> Action(c ?: "All", selected = c == conference) { conference = c } }
                    } }
                    table.view(view, conference).forEach { group ->
                        item { Label(group.name, strong = true) }
                        item { Row(Modifier.horizontalScroll(scroll)) {
                            Label("Team", Modifier.width(185.dp)); table.columns.forEach { Label(it.label, Modifier.width(50.dp), small = true) }
                        } }
                        items(group.rows) { row -> Row(Modifier.horizontalScroll(scroll).clickable { team = vm.repo.teamFor(league.id, row) }.heightIn(min = 48.dp)) {
                            Label("${row.pos}  ${row.shortName}", Modifier.width(185.dp)); table.columns.forEach { Label(row.values[it.key].orEmpty(), Modifier.width(50.dp)) }
                        } }
                    }
                    item { Action("Refresh table") { attempt++ } }
                } else item { Label(if (loading) "Loading standings…" else "No standings available."); if (!loading) Action("Retry") { attempt++ } }
            }
        }
    }
    team?.let { t ->
        val games by produceState(emptyList<SportsEvent>(), t, allowed) { if (allowed) value = sportsCatching { vm.repo.teamGames(t) }.getOrNull().orEmpty() }
        TouchSheet({ team = null }) { LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item { BrowseHeading(t.name) { team = null }; Action("Follow team", selected = follows.followsTeam(t)) { vm.repo.toggleTeam(t) } }
            items(games) { game -> Label(game.title + " · " + java.text.DateFormat.getDateInstance().format(java.util.Date(game.startMs)), Modifier.padding(4.dp)) }
        } }
    }
}
