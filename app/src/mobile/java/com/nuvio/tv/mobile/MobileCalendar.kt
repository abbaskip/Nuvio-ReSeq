// Week strip, month grid and selected-day agenda adapted from NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.livetv.calendar.*
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.SportsCatalog
import com.nuvio.tv.livetv.sports.TeamLogo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

@Composable
internal fun CalendarEventArt(entry: CalendarItem) {
    val fixtureSides = entry.fixture?.let { game ->
        SportsCatalog.league(game.leagueId)?.let { game.ordered(it.sport) }?.takeIf { it.size == 2 } ?: game.sides
    }.orEmpty()
    val sides = entry.guideSides.takeIf { it.size == 2 } ?: fixtureSides.ifEmpty { entry.guideSides }
    if (sides.size == 2) {
        Row(Modifier.width(70.dp).height(60.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            sides.forEach { side ->
                Box(Modifier.semantics { contentDescription = side.team.name }) { TeamLogo(side.team, 32.dp) }
            }
        }
    } else {
        val icon = when (entry.kind) {
            CalendarKind.SPORT -> Icons.Default.EmojiEvents
            CalendarKind.EPISODE, CalendarKind.GUIDE -> Icons.Default.LiveTv
            else -> Icons.Default.Movie
        }
        MediaArt(entry.artwork ?: sides.firstOrNull()?.team?.logo, icon, Modifier.size(54.dp, 62.dp).clip(mediaCorners),
            if (entry.fixture != null) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
    }
}

@Composable
internal fun MobileCalendar(onWatch: (LiveChannel) -> Unit, onTitle: (String, String, String) -> Unit, vm: CalendarViewModel = hiltViewModel()) {
    val profile = vm.profile.collectAsStateWithLifecycle(initialValue = null).value ?: return
    key(profile) { CalendarAgenda(vm, profile, onWatch, onTitle) }
}

@Composable
private fun CalendarAgenda(vm: CalendarViewModel, profile: Int, onWatch: (LiveChannel) -> Unit, onTitle: (String, String, String) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val region = locale.country.ifBlank { CalendarRepository.region() }
    val zone = ZoneId.systemDefault()
    val today by produceState(LocalDate.now(zone), zone) {
        while (true) {
            value = LocalDate.now(zone)
            delay((value.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1))
        }
    }
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    val date = chosen?.let(LocalDate::ofEpochDay) ?: today
    val day = date.toEpochDay()
    val pref = remember(vm, profile) { vm.preferences.selection(profile) }.collectAsStateWithLifecycle(null).value ?: return
    val shortcuts by remember(vm, profile) { vm.followedSports(profile) }.collectAsStateWithLifecycle(null)
    val sport = pref.quickSport?.takeIf { shortcuts == null || it in shortcuts.orEmpty() }
    val filter = if (sport != null) CalendarFilter.SPORTS else pref.filter
    val window = if (pref.mode == CalendarMode.MONTH) CalendarWindow.month(date) else CalendarWindow.week(date, locale)
    val cursor = remember(window) { MutableStateFlow(day) }
    LaunchedEffect(cursor, day) { cursor.value = day }
    val releases = if (filter == CalendarFilter.SPORTS) CalendarDisplay(status = CalendarStatus.READY)
        else key(profile, region, zone, window) {
            remember { vm.releases(profile, region, zone, window) }
                .collectAsStateWithLifecycle(remember { vm.cachedReleases(profile, region, zone, window) }).value
        }
    val games = if (filter == CalendarFilter.ALL || filter == CalendarFilter.SPORTS)
        key(profile, zone, window, pref.mode != CalendarMode.DAY, pref.allSports) {
            remember { vm.sports(profile, zone, window, cursor, pref.mode != CalendarMode.DAY, pref.allSports) }
                .collectAsStateWithLifecycle(CalendarDisplay()).value
        } else CalendarDisplay(status = CalendarStatus.READY)
    val grouped = remember(releases.items, games.items, window, filter, sport, today) {
        CalendarPolicy.displayed((releases.items + games.items).filter { filter.includes(it.kind) },
            window, today.toEpochDay(), sport).groupBy { it.day }
    }
    val raw = grouped[day].orEmpty()
    val rows = key(raw) { produceState(raw) { value = vm.labelDay(profile, raw) }.value }
    val heading = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    val time = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    var detail by remember { mutableStateOf<CalendarItem?>(null) }
    var teams by rememberSaveable { mutableStateOf(false) }
    val status = listOf(releases.status, games.status)
    val move: (Long) -> Unit = { step ->
        chosen = when (pref.mode) {
            CalendarMode.MONTH -> date.plusMonths(step)
            CalendarMode.WEEK -> date.plusWeeks(step)
            CalendarMode.DAY -> date.plusDays(step)
        }.toEpochDay()
    }
    var options by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().mediaPanel().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.nav_calendar)) }
                IconButton(onClick = { options = true }) { Icon(Icons.Default.Tune, "Calendar filters", tint = colors.primary) }
                IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, stringResource(R.string.calendar_refresh)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CalendarMode.entries.forEach { mode -> Action(stringResource(mode.label), Modifier.weight(1f), mode == pref.mode) { vm.preferences.setMode(profile, mode) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { move(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
                TextButton(onClick = { chosen = null }) { Label(stringResource(R.string.calendar_today), strong = true) }
                Label(date.format(DateTimeFormatter.ofPattern("MMM yyyy", locale)), strong = true)
                IconButton(onClick = { move(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
            }
            if (pref.mode != CalendarMode.DAY) {
                val first = CalendarWindow.week(LocalDate.ofEpochDay(window.first), locale).first
                Row { repeat(7) { i ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Label(LocalDate.ofEpochDay(first + i).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale), small = true)
                    }
                } }
                repeat(((window.last - first) / 7 + 1).toInt()) { week ->
                    Row {
                        repeat(7) { i ->
                            val d = first + week * 7 + i
                            val cell = Modifier.weight(1f).heightIn(min = 40.dp)
                            if (d !in window) Spacer(cell) else {
                                val hasEvents = !grouped[d].isNullOrEmpty()
                                Box(cell.clip(mediaCorners).background(if (d == day) colors.primary else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { chosen = d }.semantics { contentDescription = heading.format(LocalDate.ofEpochDay(d)); selected = d == day },
                                    contentAlignment = Alignment.Center) {
                                    Text(LocalDate.ofEpochDay(d).dayOfMonth.toString(), fontFamily = condensed,
                                        color = if (d == day) colors.onPrimary else colors.onSurface)
                                    if (hasEvents) Spacer(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp)
                                        .background(if (d == day) colors.onPrimary else colors.primary, androidx.compose.foundation.shape.CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val agenda: @Composable () -> Unit = {
        Column {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(heading.format(date)) }
                TextButton(onClick = { options = true }) { Label(sport?.let { stringResource(it.labelRes) } ?: stringResource(filter.label), small = true) }
            }
            key(profile, day, filter, sport) {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
                    if (CalendarStatus.RETRY in status) item { Action(stringResource(R.string.calendar_retry)) { vm.refresh() } }
                    if (CalendarStatus.OPENING in status || CalendarStatus.UPDATING in status) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else if (rows.isEmpty()) item { Label(stringResource(R.string.calendar_empty), Modifier.fillMaxWidth().mediaPanel().padding(16.dp)) }
                    items(rows, key = { it.key }) { entry ->
                        Row(Modifier.fillMaxWidth().mediaPanel().clickable {
                            if (entry.fixture != null) vm.selectFixture(entry)
                            detail = entry
                        }.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CalendarEventArt(entry)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                entry.time?.let { Text(time.format(Date(it)), fontFamily = condensed, color = androidx.compose.ui.graphics.lerp(colors.onSurface, colors.primary, .45f)) }
                                Label(entry.title, strong = true, lines = 2)
                                val notes = listOfNotNull(entry.leagueName, entry.detail.takeIf(String::isNotBlank)).joinToString(" · ")
                                if (notes.isNotBlank()) Label(notes, small = true, lines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
    CalendarPanes(controls, agenda)
    if (options) TouchSheet({ options = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BrowseHeading("Calendar filters") { options = false }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CalendarFilter.entries.forEach { Action(stringResource(it.label), selected = filter == it && sport == null) { vm.preferences.setFilter(profile, it) } }
            }
            if (shortcuts.orEmpty().isNotEmpty()) SectionTitle("Sport shortcuts")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                shortcuts.orEmpty().forEach { Action(stringResource(it.labelRes), selected = sport == it) { vm.preferences.toggleSport(profile, it) } }
            }
            if (filter == CalendarFilter.SPORTS) Action(stringResource(if (pref.allSports) R.string.calendar_all_enabled else R.string.calendar_my_sports)) {
                vm.preferences.setAllSports(profile, !pref.allSports)
            }
            Action("Teams") { options = false; teams = true }
        }
    }
    val fixture = detail?.fixture
    if (teams) MobileSportsTools("Teams", { teams = false })
    if (fixture != null) CalendarFixtureSheet(fixture, onWatch, { detail = null })
    else detail?.let { entry -> TouchSheet({ detail = null }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionTitle(entry.title)
            entry.contentId?.let { id -> Action("Title details") {
                detail = null
                onTitle(id, if (entry.kind == CalendarKind.EPISODE) "series" else "movie", entry.title)
            } }
            Label(heading.format(LocalDate.ofEpochDay(entry.day)))
            entry.time?.let { Label(time.format(Date(it))) }
            entry.leagueName?.let { Label(it) }
            if (entry.detail.isNotBlank()) Label(entry.detail)
            entry.matchDay?.let { Label(stringResource(R.string.calendar_match_day, it)) }
            if (entry.channelCount > 0) Label(stringResource(R.string.calendar_channel_count, entry.channelCount))
        }
    } }
}

@Composable
internal fun CalendarPanes(controls: @Composable () -> Unit, agenda: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        if (maxWidth > maxHeight && maxWidth >= 520.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(.43f).fillMaxHeight().verticalScroll(rememberScrollState())) { controls() }
            Box(Modifier.weight(.57f)) { agenda() }
        } else Column {
            Column(Modifier.heightIn(max = this@BoxWithConstraints.maxHeight * .64f).verticalScroll(rememberScrollState())) { controls() }
            Box(Modifier.weight(1f)) { agenda() }
        }
    }
}
