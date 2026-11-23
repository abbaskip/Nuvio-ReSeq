package com.nuvio.tv.mobile

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Date-only marks use the beginning of that calendar day in the device's time zone. */
internal fun selectedWatchInstant(day: LocalDate, minute: Int?, zone: ZoneId, now: Instant): Instant? {
    val stamp = if (minute == null) day.atStartOfDay(zone).toInstant() else {
        val local = day.atTime(LocalTime.ofSecondOfDay(minute * 60L))
        val offset = zone.rules.getValidOffsets(local).firstOrNull() ?: return null
        local.toInstant(offset)
    }
    return stamp.takeIf { it.isAfter(Instant.EPOCH) && !it.isAfter(now) }
}

@Composable
internal fun MarkWatchedAction(label: String, subject: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    mark: (Long) -> Unit) {
    var stage by rememberSaveable(subject) { mutableStateOf<String?>(null) }
    var epochDay by rememberSaveable(subject) { mutableLongStateOf(0L) }
    var minute by rememberSaveable(subject) { mutableStateOf<Int?>(null) }
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    MediaIconAction(label, Icons.Default.DoneAll, modifier, showLabel = true, enabled = enabled) {
        epochDay = LocalDate.now(zone).toEpochDay()
        minute = null
        stage = "choice"
    }
    fun finish(at: Instant) {
        stage = null
        if (enabled) mark(at.toEpochMilli())
    }
    val day = LocalDate.ofEpochDay(epochDay)
    if (stage == "choice" || stage == "review") {
        val review = stage == "review"
        AlertDialog(onDismissRequest = { stage = null }, title = { Label(label, strong = true) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label(subject, strong = true)
                    if (review) {
                        MediaIconAction(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)), Icons.Default.DateRange,
                            Modifier.fillMaxWidth(), showLabel = true) { stage = "editDate" }
                        val time = minute?.let { LocalTime.ofSecondOfDay(it * 60L).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }
                        MediaIconAction(time ?: "Add time", Icons.Default.Schedule, Modifier.fillMaxWidth(), showLabel = true) { stage = "time" }
                        if (minute != null) TextButton(onClick = { minute = null }) { Text("Use date only") }
                        else Label("Date only uses the start of the day in your time zone.", small = true)
                        if (selectedWatchInstant(day, minute, zone, Instant.now()) == null)
                            Label("Choose a past date and time after the start of 1970.", small = true)
                    } else {
                        MediaIconAction("Watched now", Icons.Default.Check, Modifier.fillMaxWidth(), showLabel = true) { finish(Instant.now()) }
                        MediaIconAction("Choose date", Icons.Default.DateRange, Modifier.fillMaxWidth(), showLabel = true) { stage = "date" }
                    }
                }
            }, confirmButton = {
                if (review) TextButton(enabled = enabled && selectedWatchInstant(day, minute, zone, Instant.now()) != null,
                    onClick = { selectedWatchInstant(day, minute, zone, Instant.now())?.let(::finish) }) { Text("Save watch") }
            }, dismissButton = { TextButton(onClick = { stage = null }) { Text("Cancel") } })
    }
    if (stage == "date" || stage == "editDate") {
        DisposableEffect(stage, context) {
            val cancelTo = if (stage == "editDate") "review" else "choice"
            val dialog = DatePickerDialog(context, { _, year, month, date ->
                epochDay = LocalDate.of(year, month + 1, date).toEpochDay()
                stage = "review"
            }, day.year, day.monthValue - 1, day.dayOfMonth)
            dialog.datePicker.minDate = 1L
            dialog.datePicker.maxDate = System.currentTimeMillis()
            dialog.setOnCancelListener { stage = cancelTo }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }
    if (stage == "time") {
        DisposableEffect(context) {
            val initial = minute?.let { LocalTime.ofSecondOfDay(it * 60L) } ?: LocalTime.now(zone)
            val dialog = TimePickerDialog(context, { _, hour, minutes ->
                minute = hour * 60 + minutes
                stage = "review"
            }, initial.hour, initial.minute, DateFormat.is24HourFormat(context))
            dialog.setOnCancelListener { stage = "review" }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }
}
