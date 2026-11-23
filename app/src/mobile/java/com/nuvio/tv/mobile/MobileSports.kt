// Adapted from the My Sports rows and All Events day tabs in NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.*

import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal val LocalMobileReplay = staticCompositionLocalOf<(SportsLaunch.Catchup) -> Boolean> { { false } }

@Composable
internal fun MobileSports(onWatch: (LiveChannel) -> Unit, vm: SportsViewModel = hiltViewModel()) {
    var chip by rememberSaveable { mutableStateOf<String?>(null) }
    var day by rememberSaveable { mutableIntStateOf(0) }
    var eventId by rememberSaveable { mutableStateOf<String?>(null) }
    var tool by rememberSaveable { mutableStateOf<String?>(null) }
    val loading by vm.loading.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val use24h by vm.use24h.collectAsStateWithLifecycle()
    val request = SportsPageRequest(day, chip, eventId)
    val page = remember(vm, request) { vm.page(request) }.collectAsStateWithLifecycle(null).value?.takeIf { it.request == request }
    val follows by vm.follows.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val active = page?.filters?.chip(chip) ?: chip ?: if (follows.isEmpty) CHIP_ALL else CHIP_MINE
    val allowed by vm.repo.channelLookupAllowed.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        vm.repo.setViewing(true)
        onStopOrDispose { vm.repo.setViewing(false) }
    }
    var dayError by remember(day) { mutableStateOf<String?>(null) }
    LaunchedEffect(day, active, allowed) {
        if (allowed && day != 0 && active == CHIP_ALL) dayError = vm.repo.loadDay(day)
    }
    val fmt = DateTimeFormatter.ofPattern("EEE d", LocalConfiguration.current.locales[0])
    val open: (SportsEvent) -> Unit = { eventId = it.id }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileWidth = if (maxWidth < 520.dp) (maxWidth - 44.dp).coerceAtLeast(160.dp)
            else ((maxWidth - 44.dp) / 2).coerceIn(236.dp, 340.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("Sports Hub") }
                Action("Refresh") { vm.repo.refreshNow() }
                Action("Options") { tool = "Options" }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                items(listOf(CHIP_MINE to "My Sports", CHIP_ALL to "All events", CHIP_TV_NOW to "On TV now")) { (id, name) ->
                    SportFilter(name, selected = active == id) { chip = id }
                }
                items(page?.filters?.followedSports.orEmpty()) { sport ->
                    SportFilter(sport.label, selected = active == CHIP_SPORT + sport.name) { chip = CHIP_SPORT + sport.name }
                }
                item { Spacer(Modifier.padding(horizontal = 3.dp).size(1.dp, 20.dp).background(sportBorderColor())) }
                items(listOf("Teams", "Tables", "Alerts")) { pageName -> Action(pageName) { tool = pageName } }
            }
            if (active == CHIP_ALL) {
                val dates = rememberLazyListState(initialFirstVisibleItemIndex = SportsRepository.DAYS_BACK + day)
                LazyRow(state = dates, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(((-SportsRepository.DAYS_BACK)..SportsRepository.DAYS_AHEAD).toList()) { i ->
                        SportFilter(if (i == 0) "Today" else fmt.format(LocalDate.now().plusDays(i.toLong())), selected = day == i) { day = i }
                    }
                }
            }
            HorizontalDivider(color = sportBorderColor())
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (dayError ?: problem)?.let { item { Label(it) } }
                val teams = page?.teams
                if (teams != null) {
                    items(teams.rows, key = { it.key }) { row ->
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(3.dp, 15.dp).background(colors.primary))
                            Label(row.title, strong = true, lines = 2)
                            HorizontalDivider(Modifier.weight(1f), color = sportBorderColor())
                        }
                        LazyRow(state = rememberLazyListState(initialFirstVisibleItemIndex = row.startIndex.coerceAtLeast(0)),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            items(row.events, key = { it.id }) { ev ->
                                FixtureTile(ev, settings.hideScores, use24h, Modifier.width(tileWidth), page?.matches?.get(ev.id)?.firstOrNull()?.channel, open)
                            }
                        }
                    }
                    if (teams.rows.isEmpty()) item { Label("Follow a team from an event to see its games here.") }
                } else {
                    items(page?.shown.orEmpty(), key = { it.id }) { ev ->
                        FixtureTile(ev, settings.hideScores, use24h, Modifier.fillMaxWidth(), page?.matches?.get(ev.id)?.firstOrNull()?.channel, open)
                    }
                    if (page?.shown.isNullOrEmpty()) item { Label(if (loading || page == null) "Loading fixtures…" else "No fixtures for this selection.") }
                }
            }
        }
    }
    val event = page?.opened?.takeIf { it.id == eventId }
    tool?.let { if (it == "Options") MobileSportsPreferences(vm) { tool = null } else MobileSportsTools(it, { tool = null }, vm = vm) }
    if (event != null) {
        FixtureSheet(event, page.matches[event.id].orEmpty(), onWatch, { eventId = null }, vm)
    }
}

@Composable
internal fun CalendarFixtureSheet(fixture: SportsEvent, onWatch: (LiveChannel) -> Unit, onClose: () -> Unit,
    vm: SportsViewModel = hiltViewModel()) {
    val event = remember(vm, fixture) { vm.calendarFixture(fixture.id) ?: fixture }
    val general by vm.matches.collectAsStateWithLifecycle()
    val verified by remember(vm, event) { vm.repo.calendarChannels(event) }.collectAsStateWithLifecycle(emptyList())
    LifecycleStartEffect(vm) {
        vm.repo.setViewing(true)
        onStopOrDispose { vm.repo.setViewing(false) }
    }
    FixtureSheet(event, (verified + general[event.id].orEmpty()).distinctBy { it.channel.key }, onWatch, onClose, vm)
}

@Composable
private fun FixtureSheet(event: SportsEvent, channels: List<ChannelMatch>, onWatch: (LiveChannel) -> Unit,
    onClose: () -> Unit, vm: SportsViewModel) {
    val follows by vm.follows.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val use24h by vm.use24h.collectAsStateWithLifecycle()
    val allowed by vm.repo.channelLookupAllowed.collectAsStateWithLifecycle()
    DisposableEffect(vm, event.id) {
        vm.repo.focusEvent(event.id)
        onDispose { vm.repo.focusEvent(null) }
    }
    LaunchedEffect(event.id, allowed) { if (allowed) vm.repo.loadEventChannels(event) }
    val extras = rememberGameExtras(event, vm)
    val replay = LocalMobileReplay.current
    var table by rememberSaveable(event.id) { mutableStateOf(false) }
    var tool by rememberSaveable(event.id) { mutableStateOf<String?>(null) }
    var recording by rememberSaveable(event.id) { mutableStateOf<String?>(null) }
    var message by remember(event.id) { mutableStateOf("") }
    TouchSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (event.sides.size == 2) SectionTitle(event.title)
            FixtureTile(event, settings.hideScores, use24h, Modifier.fillMaxWidth())
            val reminded = follows.reminders.firstOrNull { it.eventId == event.id }
            if (event.startMs > System.currentTimeMillis()) {
                Action(if (reminded == null) "Remind me" else "Remove reminder", selected = reminded != null) { vm.repo.toggleReminder(event) }
                if (reminded != null) Action("Tune automatically", selected = reminded.autoSwitch ?: settings.autoSwitch) {
                    vm.repo.setAutoSwitch(event, !(reminded.autoSwitch ?: settings.autoSwitch))
                }
            }
            event.sides.forEach { side ->
                val team = side.team
                Action((if (follows.followsTeam(team)) "Unfollow " else "Follow ") + team.name) { vm.repo.toggleTeam(team) }
            }
            if (channels.isEmpty()) Label("No matching channels", Modifier.padding(8.dp))
            Action("Match channels") { tool = "channels" }
            Action("Gemini coverage lookup") { tool = "lookup" }
            channels.forEach { match -> Action(match.channel.sportsChannelLabel, Modifier.fillMaxWidth()) {
                onClose()
                onWatch(match.channel)
            }
                if (match.catchup && event.startMs <= System.currentTimeMillis()) Action("Replay from start · " + match.channel.sportsChannelLabel) {
                    val request = if (vm.watchFromStart(event, match)) SportsLaunch.takeCatchup() else null
                    if (request != null && replay(request)) onClose() else message = "Replay is unavailable on this channel."
                }
                if (event.state != GameState.POST) Action("Record on " + match.channel.sportsChannelLabel) { recording = match.channel.key }
                if (recording == match.channel.key) LazyRow { items(listOf(60, 90, 120, 180, 240)) { mins ->
                    Action("$mins min") { message = vm.record(event, match, mins); recording = null }
                } }
            }
            if (message.isNotBlank()) Label(message)
            if (SportsCatalog.league(event.leagueId)?.let(vm.repo::mayHaveTable) == true) Action("League table") { table = true }
            extras?.let { x ->
                listOfNotNull(x.venue, x.city, x.conditions, x.referee?.let { "Referee · $it" }, x.attendance?.let { "Attendance · $it" }).forEach { Label(it) }
                x.lineups.forEach { lineup -> SectionTitle(lineup.team); Label(listOfNotNull(lineup.formation, lineup.players.joinToString(", ")).joinToString(" · ")) }
                if (!settings.hideScores) {
                    x.stats.forEach { stat -> Label("${stat.away}  ·  ${stat.label}  ·  ${stat.home}") }
                    x.performers.forEach { p -> Label(p.name + " · " + p.category + " · " + p.line) }
                    x.form.forEach { f -> Label(f.team + " · " + f.results.joinToString(" ")) }
                    x.meetings.forEach { m -> Label(m.text) }
                }
            }
        }
    }
    if (table) MobileSportsTools("Tables", { table = false }, event.leagueId, vm)
    if (tool == "channels") MobileFixtureChannels(event, channels, vm) { tool = null }
    if (tool == "lookup") MobileSportsLookup(event, vm) { tool = null }
}
