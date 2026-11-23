package com.nuvio.tv.ui.components

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal fun watchedInstant(date: LocalDate, time: LocalTime?, zone: ZoneId): Long? {
    if (time == null) return date.atStartOfDay(zone).toInstant().toEpochMilli()
    val local = date.atTime(time)
    val offset = zone.rules.getValidOffsets(local).firstOrNull() ?: return null
    return local.toInstant(offset).toEpochMilli()
}

private enum class WatchDateStep { CHOICE, DATE, TIME }

/** The callback belongs to the screen that opened the picker and is discarded on dismissal. */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun rememberWatchedDatePicker(): ((Long) -> Unit) -> Unit {
    var pending by remember { mutableStateOf<((Long) -> Unit)?>(null) }
    pending?.let { action ->
        val initial = remember { ZonedDateTime.now() }
        var step by remember { mutableStateOf(WatchDateStep.CHOICE) }
        var includeTime by remember { mutableStateOf(false) }
        var date by remember { mutableStateOf(initial.toLocalDate()) }
        var time by remember { mutableStateOf(initial.toLocalTime().withSecond(0).withNano(0)) }
        fun finish(at: Long) {
            pending = null
            action(at)
        }
        NuvioDialog(title = stringResource(R.string.when_watched), onDismiss = { pending = null }) {
            when (step) {
                WatchDateStep.CHOICE -> {
                    val first = remember { FocusRequester() }
                    LaunchedEffect(Unit) { first.requestFocus() }
                    Button(modifier = Modifier.focusRequester(first), onClick = { finish(System.currentTimeMillis()) }) { Text(stringResource(R.string.watched_now)) }
                    Button(onClick = { includeTime = false; step = WatchDateStep.DATE }) { Text(stringResource(R.string.choose_watch_date)) }
                    Button(onClick = { includeTime = true; step = WatchDateStep.DATE }) { Text(stringResource(R.string.choose_watch_date_time)) }
                }
                WatchDateStep.DATE -> {
                    AndroidView(factory = { context ->
                        (LayoutInflater.from(context).inflate(R.layout.watched_date_spinner, null) as DatePicker).apply {
                            maxDate = System.currentTimeMillis()
                            init(date.year, date.monthValue - 1, date.dayOfMonth) { _, year, month, day ->
                                date = LocalDate.of(year, month + 1, day)
                            }
                        }
                    })
                    Button(onClick = {
                        if (includeTime) step = WatchDateStep.TIME
                        else watchedInstant(date, null, initial.zone)?.let(::finish)
                    }) { Text(stringResource(if (includeTime) R.string.choose_watch_time else R.string.action_save)) }
                }
                WatchDateStep.TIME -> {
                    AndroidView(factory = { context ->
                        (LayoutInflater.from(context).inflate(R.layout.watched_time_spinner, null) as TimePicker).apply {
                            setIs24HourView(DateFormat.is24HourFormat(context))
                            hour = time.hour
                            minute = time.minute
                            setOnTimeChangedListener { _, h, m -> time = LocalTime.of(h, m) }
                        }
                    })
                    val selected = watchedInstant(date, time, initial.zone)
                    val valid = selected != null && selected <= System.currentTimeMillis()
                    if (!valid) Text(stringResource(R.string.choose_past_watch_time))
                    Button(enabled = valid, onClick = { selected?.let(::finish) }) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
    return remember { { action -> pending = action } }
}
