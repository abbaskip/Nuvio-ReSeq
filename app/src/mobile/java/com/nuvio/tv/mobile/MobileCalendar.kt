// Week strip, month grid and selected-day agenda adapted from NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

@Composable
internal fun MobileCalendar(onWatch: (LiveChannel) -> Unit, vm: CalendarViewModel = hiltViewModel()) {
    val profile = vm.profile.collectAsStateWithLifecycle(initialValue = null).value ?: return
    key(profile) { CalendarAgenda(vm, profile, onWatch) }
}

@Composable
private fun CalendarAgenda(vm: CalendarViewModel, profile: Int, onWatch: (LiveChannel) -> Unit) {
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
    val status = listOf(releases.status, games.status)
    val move: (Long) -> Unit = { step ->
        chosen = when (pref.mode) {
            CalendarMode.MONTH -> date.plusMonths(step)
            CalendarMode.WEEK -> date.plusWeeks(step)
            CalendarMode.DAY -> date.plusDays(step)
        }.toEpochDay()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.nav_calendar)) }
            Action(stringResource(R.string.calendar_refresh)) { vm.refresh() }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            CalendarMode.entries.forEach {
                Action(stringResource(it.label), Modifier.weight(1f), it == pref.mode) { vm.preferences.setMode(profile, it) }
            }
        } }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(CalendarFilter.entries) { Action(stringResource(it.label), selected = filter == it && sport == null) { vm.preferences.setFilter(profile, it) } }
            items(shortcuts.orEmpty().toList()) { Action(stringResource(it.labelRes), selected = sport == it) { vm.preferences.toggleSport(profile, it) } }
        } }
        if (filter == CalendarFilter.SPORTS) item {
            Action(stringResource(if (pref.allSports) R.string.calendar_all_enabled else R.string.calendar_my_sports)) {
                vm.preferences.setAllSports(profile, !pref.allSports)
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Action("Previous", Modifier.weight(1f)) { move(-1) }
            Action(stringResource(R.string.calendar_today), Modifier.weight(1f)) { chosen = null }
            Action("Next", Modifier.weight(1f)) { move(1) }
        } }
        item { Label(heading.format(date), Modifier.padding(6.dp), strong = true) }
        if (pref.mode != CalendarMode.DAY) item {
            val first = CalendarWindow.week(LocalDate.ofEpochDay(window.first), locale).first
            val weeks = ((window.last - first) / 7 + 1).toInt()
            Column {
                Row {
                    repeat(7) { i ->
                        Label(LocalDate.ofEpochDay(first + i).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale),
                            Modifier.weight(1f), small = true)
                    }
                }
                repeat(weeks) { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        repeat(7) { i ->
                            val d = first + week * 7 + i
                            if (d !in window) Spacer(Modifier.weight(1f))
                            else {
                                val number = LocalDate.ofEpochDay(d).dayOfMonth.toString()
                                Action(if (grouped[d].isNullOrEmpty()) number else "$number·", Modifier.weight(1f),
                                    d == day) { chosen = d }
                            }
                        }
                    }
                }
            }
        }
        if (CalendarStatus.RETRY in status) item { Label(stringResource(R.string.calendar_retry)) }
        if (CalendarStatus.OPENING in status || CalendarStatus.UPDATING in status) item { Label(stringResource(R.string.calendar_loading)) }
        else if (rows.isEmpty()) item { Label(stringResource(R.string.calendar_empty), Modifier.padding(8.dp)) }
        items(rows, key = { it.key }) { entry ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable {
                if (entry.fixture != null) vm.selectFixture(entry)
                detail = entry
            }
                .heightIn(min = 56.dp).padding(10.dp)) {
                Label(entry.title, strong = true)
                Label(listOfNotNull(entry.time?.let { time.format(Date(it)) }, entry.leagueName, entry.detail.takeIf(String::isNotBlank))
                    .joinToString(" · "), small = true)
            }
        }
    }
    val fixture = detail?.fixture
    if (fixture != null) CalendarFixtureSheet(fixture, onWatch, { detail = null })
    else detail?.let { entry -> TouchSheet({ detail = null }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionTitle(entry.title)
            Label(heading.format(LocalDate.ofEpochDay(entry.day)))
            entry.time?.let { Label(time.format(Date(it))) }
            entry.leagueName?.let { Label(it) }
            if (entry.detail.isNotBlank()) Label(entry.detail)
            entry.matchDay?.let { Label(stringResource(R.string.calendar_match_day, it)) }
            if (entry.channelCount > 0) Label(stringResource(R.string.calendar_channel_count, entry.channelCount))
        }
    } }
}
