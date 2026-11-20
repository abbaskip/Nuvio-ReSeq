package com.nuvio.tv.livetv.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalOpenSidebar
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.livetv.sports.FilterIcon
import com.nuvio.tv.livetv.sports.ChooseSportsDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
internal fun CalendarScreen(onDetail: (String, String, String, String?) -> Unit, onSports: (String) -> Unit,
    onFollowTeams: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()) {
    val active = viewModel.profile.collectAsStateWithLifecycle(initialValue = null).value ?: return
    val locale = LocalConfiguration.current.locales[0]
    val region = locale.country.ifBlank { CalendarRepository.region() }
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    val timeFormat = remember(context, locale) { android.text.format.DateFormat.getTimeFormat(context) }
    val clock: (Long) -> String = { timeFormat.format(Date(it)) }
    val selection = key(active) {
        val preferenceFlow = remember(viewModel) { viewModel.preferences.selection(active) }
        preferenceFlow.collectAsStateWithLifecycle(initialValue = null).value
    } ?: return
    key(active, region, zone) {
        val mode = selection.mode
        val followsFlow = remember(active) { viewModel.followedSports(active) }
        val followed by followsFlow.collectAsStateWithLifecycle(initialValue = null)
        val shortcuts = followed.orEmpty()
        val quickSport = selection.quickSport?.takeIf { followed == null || it in shortcuts }
        LaunchedEffect(selection.quickSport, followed) {
            selection.quickSport?.takeIf { followed != null && it !in shortcuts }?.let { viewModel.preferences.clearSport(active, it) }
        }
        val filter = if (quickSport == null) selection.filter else CalendarFilter.SPORTS
        var today by remember { mutableStateOf(LocalDate.now(zone)) }
        var picked by remember { mutableStateOf(today) }
        val selected = picked.toEpochDay()
        val week = CalendarWindow.week(picked, locale)
        val window = if (mode == CalendarMode.MONTH) CalendarWindow.month(picked) else week
        // Keep an already open release feed while switching sports. A saved sport opens without starting that feed.
        var openedReleases by remember { mutableStateOf(false) }
        val needsReleases = filter != CalendarFilter.SPORTS
        LaunchedEffect(needsReleases) { if (needsReleases) openedReleases = true }
        val releases = if (needsReleases || (openedReleases && quickSport != null && selection.filter != CalendarFilter.SPORTS)) {
            val flow = remember(active, region, zone, window) { viewModel.releases(active, region, zone, window) }
            key(flow) { flow.collectAsStateWithLifecycle(initialValue = CalendarDisplay(), minActiveState = Lifecycle.State.RESUMED).value }
        } else CalendarDisplay(status = CalendarStatus.READY)
        val sportDay = remember(window) { kotlinx.coroutines.flow.MutableStateFlow(selected) }
        LaunchedEffect(sportDay, selected) { sportDay.value = selected }
        val overview = mode != CalendarMode.DAY
        val sports = if (filter == CalendarFilter.ALL || filter == CalendarFilter.SPORTS) {
            val flow = remember(active, zone, window, sportDay, overview, selection.allSports) {
                viewModel.sports(active, zone, window, sportDay, overview, selection.allSports)
            }
            key(flow) { flow.collectAsStateWithLifecycle(initialValue = CalendarDisplay(), minActiveState = Lifecycle.State.RESUMED).value }
        } else CalendarDisplay(status = CalendarStatus.READY)
        val grouped = remember(releases.items, sports.items, window, filter, quickSport, today) {
            CalendarPolicy.displayed((releases.items + sports.items).filter { filter.includes(it.kind) }, window, today.toEpochDay(), quickSport).groupBy { it.day }
        }
        val rawDay = grouped[selected].orEmpty()
        val rows = key(rawDay) { produceState(rawDay) { value = viewModel.labelDay(active, rawDay) }.value }
        val focus = remember { FocusRequester() }
        val todayControl = remember { FocusRequester() }
        val sportsFilter = remember { FocusRequester() }
        val firstShortcut = remember { FocusRequester() }
        val dayMode = remember { FocusRequester() }
        var todayControlFocused by remember { mutableStateOf(false) }
        var sportsFilterFocused by remember { mutableStateOf(false) }
        var choosingSports by rememberSaveable { mutableStateOf(false) }
        val contentFocus = LocalContentFocusRequester.current
        val openSidebar = LocalOpenSidebar.current
        val sidebarOpen by rememberUpdatedState(LocalSidebarExpanded.current)
        BackHandler(enabled = !sidebarOpen && !choosingSports) {
            if (todayControlFocused) openSidebar() else todayControl.requestFocus()
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle, zone) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                today = LocalDate.now(zone)
                picked = today
                snapshotFlow { sidebarOpen }.first { !it }
                withFrameNanos { }
                if (!sidebarOpen) focus.requestFocus()
                while (true) {
                    delay((today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1))
                    val next = LocalDate.now(zone)
                    if (picked == today) picked = next
                    today = next
                }
            }
        }
        val selectedFocus = Modifier.focusRequester(contentFocus).focusRequester(focus)
        val leftEdge = Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) { openSidebar(); true } else false
        }
        val open: (CalendarItem) -> Unit = { item ->
            item.contentId?.let { id ->
                if (item.kind == CalendarKind.SPORT) { viewModel.selectFixture(item); onSports(id) }
                else onDetail(id, if (item.kind == CalendarKind.EPISODE) "series" else "movie", item.title, item.artwork)
            }
        }
        val changeDay: (Long) -> Unit = { picked = LocalDate.ofEpochDay(it) }
        var focusOpenedDay by remember { mutableStateOf(false) }
        val openDay: (Long) -> Unit = {
            changeDay(it)
            focusOpenedDay = true
            viewModel.preferences.setMode(active, CalendarMode.DAY)
        }
        LaunchedEffect(mode, focusOpenedDay) {
            if (focusOpenedDay && mode == CalendarMode.DAY) {
                withFrameNanos { }
                focus.requestFocus()
                focusOpenedDay = false
            }
        }
        val changing = listOfNotNull(releases.status.takeUnless { filter == CalendarFilter.SPORTS },
            sports.status.takeIf { filter == CalendarFilter.SPORTS || filter == CalendarFilter.ALL })
        val loading = changing.any { it == CalendarStatus.OPENING || it == CalendarStatus.UPDATING }
        val retry = CalendarStatus.RETRY in changing
        val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
        val short = remember(locale) { DateTimeFormatter.ofPattern("EEE d", locale) }
        val monthName = remember(locale) { DateTimeFormatter.ofPattern("MMMM yyyy", locale) }
        val dateShort = remember(locale) { DateTimeFormatter.ofPattern("MMM d", locale) }
        val period = if (mode == CalendarMode.MONTH) monthName.format(picked)
            else dateShort.format(LocalDate.ofEpochDay(week.first)) + " – " + dateShort.format(LocalDate.ofEpochDay(week.last))
        val theme = NuvioTheme.colors
        Column(Modifier.fillMaxSize().background(theme.Background).padding(horizontal = 23.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                LiveText(stringResource(R.string.nav_calendar), size = 27.sp, weight = FontWeight.ExtraBold)
                LiveText(stringResource(R.string.calendar_timezone, zone.id.substringAfterLast('/').replace('_', ' ')), size = 12.sp, color = theme.TextSecondary)
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(50)).background(theme.BackgroundElevated).padding(2.dp)) {
                    CalendarMode.entries.forEach { option ->
                        val navigation = if (option == CalendarMode.DAY) {
                            Modifier.focusRequester(dayMode).focusProperties { left = sportsFilter }
                        } else Modifier
                        CalendarPill(stringResource(option.label), option == mode, navigation) { viewModel.preferences.setMode(active, option) }
                    }
                }
                CalendarPill(stringResource(R.string.calendar_refresh)) { viewModel.refresh() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CalendarFilter.entries.forEach { choice ->
                    val navigation = when (choice) {
                        CalendarFilter.ALL -> leftEdge
                        CalendarFilter.SPORTS -> Modifier.focusRequester(sportsFilter).focusProperties { right = if (shortcuts.isEmpty()) dayMode else firstShortcut }
                            .onFocusChanged { sportsFilterFocused = it.isFocused }
                        else -> Modifier
                    }
                    CalendarPill(stringResource(choice.label), choice == filter, navigation,
                        onLongClick = if (choice == CalendarFilter.SPORTS) ({ choosingSports = true }) else null
                    ) { viewModel.preferences.setFilter(active, choice) }
                }
                if (sportsFilterFocused) LiveText(stringResource(R.string.calendar_hold_choose_sports),
                    Modifier.padding(start = 6.dp), size = 11.sp, color = theme.TextSecondary)
                if (shortcuts.isNotEmpty()) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        shortcuts.forEach { sport -> key(sport) {
                            var focused by remember { mutableStateOf(false) }
                            val chosen = sport == quickSport
                            val entry = if (sport == shortcuts.first()) Modifier.focusRequester(firstShortcut) else Modifier
                            FilterIcon(sport.icon, sport.label, chosen, entry.focusProperties {
                                if (sport == shortcuts.first()) left = sportsFilter
                                if (sport == shortcuts.last()) right = dayMode
                            }.onFocusChanged { focused = it.isFocused }.semantics {
                                contentDescription = sport.label
                                this.selected = chosen
                            }, withLabel = focused || chosen) { viewModel.preferences.toggleSport(active, sport) }
                        } }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LiveText(dateFormat.format(picked), Modifier.weight(1f), size = 19.sp, weight = FontWeight.Bold)
                CalendarPill(stringResource(if (mode == CalendarMode.MONTH) R.string.calendar_previous_month else R.string.calendar_previous_week),
                    modifier = Modifier.focusProperties { left = sportsFilter }) {
                    picked = if (mode == CalendarMode.MONTH) picked.minusMonths(1) else picked.minusWeeks(1)
                }
                CalendarPill(stringResource(R.string.calendar_today),
                    modifier = Modifier.focusRequester(todayControl).onFocusChanged { todayControlFocused = it.isFocused }) { picked = today }
                LiveText(period, size = 12.sp, weight = FontWeight.Bold)
                CalendarPill(stringResource(if (mode == CalendarMode.MONTH) R.string.calendar_next_month else R.string.calendar_next_week)) {
                    picked = if (mode == CalendarMode.MONTH) picked.plusMonths(1) else picked.plusWeeks(1)
                }
            }
            if (mode == CalendarMode.DAY) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (day in week.first..week.last) {
                    val date = LocalDate.ofEpochDay(day)
                    CalendarPill(if (date == today) stringResource(R.string.calendar_today_number, date.dayOfMonth) else short.format(date), selected == day,
                        Modifier.weight(1f).then(if (day == selected) selectedFocus else Modifier)
                            .then(if (day == week.first) leftEdge else Modifier)) { picked = date }
                }
            }
            when {
                CalendarStatus.PLAYBACK in changing -> LiveText(stringResource(R.string.calendar_playback), size = 11.sp, color = theme.TextSecondary)
                retry -> LiveText(stringResource(R.string.calendar_retry), size = 11.sp, color = theme.TextSecondary)
                loading -> LiveText(stringResource(R.string.calendar_loading), size = 11.sp, color = theme.TextSecondary)
                rows.isEmpty() && mode != CalendarMode.WEEK -> LiveText(
                    if (quickSport != null) stringResource(R.string.calendar_empty_sport_day, quickSport.label) else stringResource(when {
                        filter != CalendarFilter.SPORTS -> R.string.calendar_empty
                        selection.allSports -> R.string.calendar_empty_sports
                        else -> R.string.calendar_empty_my_sports
                    }),
                    size = 12.sp, color = theme.TextSecondary)
            }
            when (mode) {
                CalendarMode.DAY -> DayCards(rows, clock, open)
                CalendarMode.WEEK -> WeekView(week, selected, grouped, locale, selectedFocus, clock, changeDay, openDay, open)
                CalendarMode.MONTH -> MonthView(window, selected, today.toEpochDay(), grouped, rows, locale, selectedFocus, clock, changeDay, openDay, open)
            }
        }
        if (choosingSports) ChooseSportsDialog(viewModel = hiltViewModel(),
            calendarOptions = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveText(stringResource(R.string.calendar_sports_source), size = 14.sp)
                    CalendarPill(stringResource(R.string.calendar_my_sports), !selection.allSports) { viewModel.preferences.setAllSports(active, false) }
                    CalendarPill(stringResource(R.string.calendar_all_enabled), selection.allSports) { viewModel.preferences.setAllSports(active, true) }
                }
            },
            onTeams = { choosingSports = false; onFollowTeams() },
            onClose = { choosingSports = false })
    }
}
