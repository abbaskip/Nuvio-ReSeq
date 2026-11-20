package com.nuvio.tv.livetv.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
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
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalOpenSidebar
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
        val source = remember(viewModel, active, region, zone) { viewModel.display(active, region, zone) }
        val state by source.collectAsStateWithLifecycle(initialValue = CalendarDisplay(), minActiveState = Lifecycle.State.RESUMED)
        val today = LocalDate.now(zone)
        val window = CalendarWindow.around(today, locale)
        var picked by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
        val selected = picked.coerceIn(window.first, window.last)
        val week = window.first + (selected - window.first) / 7 * 7
        val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE", locale) }
        val shortDate = remember(locale) { DateTimeFormatter.ofPattern("MMM d", locale) }
        val longDate = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
        val context = LocalContext.current
        val clock = remember(context, locale) { android.text.format.DateFormat.getTimeFormat(context) }
        val forDay = remember(state.items, selected) { state.items.filter { it.day == selected } }
        val contentFocus = LocalContentFocusRequester.current
        val openSidebar = LocalOpenSidebar.current
        val leftToSidebar = Modifier.onPreviewKeyEvent { event ->
            if (event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) { openSidebar(); true } else false
        }

        Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveText(stringResource(R.string.nav_calendar), Modifier.weight(1f), size = 28.sp, weight = FontWeight.Bold)
                val action = if (state.remaining > 0 && state.status != CalendarStatus.RETRY)
                    stringResource(R.string.calendar_more, state.remaining) else stringResource(R.string.calendar_refresh)
                if (state.status == CalendarStatus.READY || state.status == CalendarStatus.RETRY)
                    CalendarButton(action, onClick = viewModel::refresh)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (week > window.first) CalendarButton(stringResource(R.string.calendar_previous_week),
                    modifier = leftToSidebar) { picked = selected - 7 }
                CalendarButton(stringResource(R.string.calendar_today), modifier = Modifier.focusRequester(contentFocus)
                    .then(if (week == window.first) leftToSidebar else Modifier)) { picked = today.toEpochDay() }
                if (week + 7 <= window.last) CalendarButton(stringResource(R.string.calendar_next_week)) { picked = selected + 7 }
                Spacer(Modifier.weight(1f))
                LiveText(shortDate.format(LocalDate.ofEpochDay(week)) + " – " + shortDate.format(LocalDate.ofEpochDay(week + 6)),
                    size = 16.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (offset in 0..6) {
                    val day = LocalDate.ofEpochDay(week + offset)
                    val epoch = day.toEpochDay()
                    LiveFocusRow(modifier = Modifier.weight(1f).then(if (offset == 0) leftToSidebar else Modifier),
                        horizontalPadding = 6.dp, selected = epoch == selected, onClick = { picked = epoch }) { focused ->
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            LiveText(dayFormat.format(day), color = focusedTextColor(focused), size = 13.sp)
                            LiveText(day.dayOfMonth.toString(), color = focusedTextColor(focused), size = 20.sp, weight = FontWeight.Bold)
                        }
                    }
                }
            }
            LiveText(longDate.format(LocalDate.ofEpochDay(selected)), size = 17.sp, weight = FontWeight.SemiBold)
            val message = when (state.status) {
                CalendarStatus.UPDATING -> R.string.calendar_loading
                CalendarStatus.PLAYBACK -> R.string.calendar_playback
                CalendarStatus.RETRY -> R.string.calendar_retry
                else -> null
            }
            if (message != null) LiveText(stringResource(message), size = 13.sp)
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
                        LiveFocusRow(Modifier.fillMaxWidth().then(leftToSidebar), onClick = {
                            val id = item.contentId
                            when (item.kind) {
                                CalendarKind.GUIDE -> onGuide()
                                CalendarKind.SPORT -> if (id != null) onSports(id)
                                else -> if (id != null) onDetail(id, if (item.kind == CalendarKind.EPISODE) "series" else "movie")
                            }
                        }) { focused ->
                            val color = focusedTextColor(focused)
                            Column(Modifier.weight(1f)) {
                                LiveText(item.title, color = color, size = 17.sp, weight = FontWeight.SemiBold)
                                if (item.detail.isNotBlank()) LiveText(item.detail, color = color, size = 14.sp)
                            }
                            LiveText(stringResource(item.kind.label()), color = color, size = 13.sp)
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
