package com.nuvio.tv.livetv.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalOpenSidebar
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.R
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** A TV week strip and daily agenda, following NuvioPlusIPTV's Calendar. */
@Composable
internal fun CalendarScreen(onDetail: (String, String) -> Unit, onSports: (String) -> Unit, onGuide: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle(initialValue = null)
    val active = profile ?: return
    val locale = LocalConfiguration.current.locales[0]
    val region = locale.country.ifBlank { CalendarRepository.region() }
    val zone = ZoneId.systemDefault()
    key(active, region, zone) {
        var today by remember { mutableStateOf(LocalDate.now(zone)) }
        val window = CalendarWindow.around(today, locale)
        var picked by remember { mutableLongStateOf(today.toEpochDay()) }
        val selected = picked.coerceIn(window.first, window.last)
        val week = window.first + (selected - window.first) / 7 * 7
        val source = remember(viewModel, active, region, zone, week) { viewModel.display(active, region, zone, week) }
        val state = key(source) { source.collectAsStateWithLifecycle(initialValue = CalendarDisplay(), minActiveState = Lifecycle.State.RESUMED).value }
        val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE", locale) }
        val shortDate = remember(locale) { DateTimeFormatter.ofPattern("MMM d", locale) }
        val longDate = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
        val context = LocalContext.current
        val clock = remember(context, locale) { android.text.format.DateFormat.getTimeFormat(context) }
        val forDay = remember(state.items, selected) { state.items.filter { it.day == selected } }
        val contentFocus = LocalContentFocusRequester.current
        val initialFocus = remember { FocusRequester() }
        val sidebarOpen by rememberUpdatedState(LocalSidebarExpanded.current)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle, zone) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                today = LocalDate.now(zone)
                picked = today.toEpochDay()
                snapshotFlow { sidebarOpen }.first { !it }
                withFrameNanos { }
                if (!sidebarOpen) initialFocus.requestFocus()
                while (true) {
                    val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    delay((midnight - System.currentTimeMillis()).coerceAtLeast(1))
                    val next = LocalDate.now(zone)
                    if (picked == today.toEpochDay()) picked = next.toEpochDay()
                    today = next
                }
            }
        }
        val openSidebar = LocalOpenSidebar.current
        val leftToSidebar = Modifier.onPreviewKeyEvent { event ->
            if (event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) { openSidebar(); true } else false
        }

        Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveText(stringResource(R.string.nav_calendar), Modifier.weight(1f), size = 28.sp, weight = FontWeight.Bold)
                if (state.status == CalendarStatus.READY || state.status == CalendarStatus.RETRY)
                    CalendarButton(stringResource(R.string.calendar_refresh), onClick = viewModel::refresh)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (week > window.first) CalendarButton(stringResource(R.string.calendar_previous_week),
                    modifier = leftToSidebar) { picked = selected - 7 }
                CalendarButton(stringResource(R.string.calendar_today),
                    modifier = if (week == window.first) leftToSidebar else Modifier) { picked = today.toEpochDay() }
                if (week + 7 <= window.last) CalendarButton(stringResource(R.string.calendar_next_week)) { picked = selected + 7 }
                Spacer(Modifier.weight(1f))
                LiveText(shortDate.format(LocalDate.ofEpochDay(week)) + " – " + shortDate.format(LocalDate.ofEpochDay(week + 6)),
                    size = 16.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (offset in 0..6) {
                    val day = LocalDate.ofEpochDay(week + offset)
                    val epoch = day.toEpochDay()
                    LiveFocusRow(modifier = Modifier.weight(1f)
                        .then(if (epoch == selected) Modifier.focusRequester(contentFocus).focusRequester(initialFocus) else Modifier)
                        .then(if (offset == 0) leftToSidebar else Modifier),
                        horizontalPadding = 6.dp, selected = epoch == selected, onClick = { picked = epoch }) { focused ->
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            LiveText(if (day == today) stringResource(R.string.calendar_today) else dayFormat.format(day),
                                color = focusedTextColor(focused), size = 13.sp)
                            LiveText(day.dayOfMonth.toString(), color = focusedTextColor(focused), size = 20.sp, weight = FontWeight.Bold)
                        }
                    }
                }
            }
            LiveText(longDate.format(LocalDate.ofEpochDay(selected)), size = 17.sp, weight = FontWeight.SemiBold)
            val message = when (state.status) {
                CalendarStatus.OPENING -> stringResource(R.string.calendar_opening)
                CalendarStatus.UPDATING -> stringResource(R.string.calendar_loading)
                CalendarStatus.PLAYBACK -> stringResource(R.string.calendar_playback)
                CalendarStatus.RETRY -> stringResource(R.string.calendar_retry)
                else -> null
            }
            if (message != null) LiveText(message, size = 13.sp)
            if (forDay.isEmpty() && state.status != CalendarStatus.OPENING && state.status != CalendarStatus.UPDATING) {
                LiveText(stringResource(R.string.calendar_empty), size = 16.sp)
                if (state.items.isEmpty() && state.status == CalendarStatus.READY) LiveText(stringResource(R.string.calendar_start), size = 14.sp)
            }
            key(selected) {
                val labelled = key(forDay) {
                    produceState(forDay) { value = viewModel.labelDay(active, forDay) }.value
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(labelled, key = { it.key }) { item ->
                        LiveFocusRow(Modifier.fillMaxWidth().then(leftToSidebar),
                            unfocusedBorderColor = if (item.followedTeam != null) NuvioTheme.colors.Secondary.copy(alpha = .65f) else null,
                            onClick = {
                            val id = item.contentId
                            when (item.kind) {
                                CalendarKind.GUIDE -> onGuide()
                                CalendarKind.SPORT -> if (id != null) onSports(id)
                                else -> if (id != null) onDetail(id, if (item.kind == CalendarKind.EPISODE) "series" else "movie")
                            }
                        }) { focused ->
                            val color = focusedTextColor(focused)
                            Column(Modifier.weight(1f)) {
                                LiveText(item.title, color = color, size = 17.sp,
                                    weight = if (item.followedTeam != null) FontWeight.Bold else FontWeight.Medium)
                                if (item.detail.isNotBlank()) LiveText(item.detail, color = color, size = 14.sp)
                            }
                            if (item.followedTeam != null) {
                                LiveText(stringResource(R.string.calendar_followed_team, item.followedTeam),
                                    color = if (focused) color else NuvioTheme.colors.Secondary, size = 14.sp, weight = FontWeight.Bold)
                            } else LiveText(stringResource(item.kind.label()),
                                color = color, size = 13.sp)
                            item.time?.let { LiveText(clock.format(Date(it)), color = color, size = 15.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LiveFocusRow(modifier, selected = true, onClick = onClick) { focused ->
        LiveText(label, color = focusedTextColor(focused), size = 14.sp)
    }
}

private fun CalendarKind.label(): Int = when (this) {
    CalendarKind.EPISODE -> R.string.calendar_episode
    CalendarKind.CINEMA -> R.string.calendar_cinema
    CalendarKind.DIGITAL -> R.string.calendar_digital
    CalendarKind.DISC -> R.string.calendar_disc
    CalendarKind.SPORT -> R.string.calendar_sport
    CalendarKind.GUIDE -> R.string.calendar_guide
}
