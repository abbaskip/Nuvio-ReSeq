package com.nuvio.tv.livetv.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "Schedule a Recording": a channel, a day, a start time and a length, for channels with no
 * listings (PPV events, a game that isn't in the guide). ◀ ▶ change the highlighted value.
 */
@Composable
internal fun ManualRecordingDialog(
    channel: LiveChannel,
    use24h: Boolean,
    onSchedule: (title: String, startMs: Long, stopMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val now = remember { System.currentTimeMillis() }
    // Start: the next quarter hour, today.
    val startCal = remember {
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            val m = get(Calendar.MINUTE)
            add(Calendar.MINUTE, 15 - m % 15)
        }
    }
    var dayOffset by remember { mutableIntStateOf(0) }
    var minuteOfDay by remember {
        mutableIntStateOf(startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE))
    }
    // The next quarter hour can be after midnight.
    LaunchedEffect(Unit) {
        val today = Calendar.getInstance().apply { timeInMillis = now }
        if (startCal.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)) dayOffset = 1
    }
    var lengthMin by remember { mutableIntStateOf(120) }
    var customName by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun startMs(): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val timeFmt = remember(use24h) { SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    fun dayLabel(offset: Int): String = when (offset) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> dayFmt.format(Date(now + offset * 86_400_000L))
    }
    fun lengthLabel(m: Int): String = when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
    val defaultName = "${channel.name} — ${SimpleDateFormat("EEE", Locale.getDefault()).format(Date(startMs()))} ${timeFmt.format(Date(startMs()))}"
    val name = customName?.takeIf { it.isNotBlank() } ?: defaultName

    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 520.dp) {
        LiveText("Schedule a Recording", size = 20.sp, weight = FontWeight.Bold)
        LiveText("◀ ▶ change the highlighted setting", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ValueRow("Channel", "${channel.number}  ${channel.name}", onChange = null, onClick = {})
            ValueRow(
                "Day", dayLabel(dayOffset),
                modifier = Modifier.focusRequester(first),
                onChange = { d -> dayOffset = (dayOffset + d).coerceIn(0, 7); problem = null },
                onClick = { dayOffset = (dayOffset + 1) % 8; problem = null }
            )
            ValueRow(
                "Start time", timeFmt.format(Date(startMs())),
                onChange = { d -> minuteOfDay = Math.floorMod(minuteOfDay + d * 5, 24 * 60); problem = null },
                onClick = { editing = "time" }
            )
            ValueRow(
                "Length", lengthLabel(lengthMin),
                onChange = { d -> lengthMin = (lengthMin + d * 30).coerceIn(30, 8 * 60) },
                onClick = { lengthMin = if (lengthMin >= 8 * 60) 30 else lengthMin + 30 }
            )
            ValueRow("Name", name, onChange = null, onClick = { editing = "name" })
        }
        Spacer(Modifier.height(6.dp))
        LiveText(
            "Ends at ${timeFmt.format(Date(startMs() + lengthMin * 60_000L))}. Your Start Early and Keep Recording After times are added.",
            color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 2
        )
        problem?.let {
            Spacer(Modifier.height(4.dp))
            LiveText(it, color = NuvioTheme.colors.Error, size = 13.sp, maxLines = 2)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveFocusRow(onClick = {
                val start = startMs()
                val stop = start + lengthMin * 60_000L
                if (stop <= System.currentTimeMillis()) {
                    problem = "That time has already passed. Pick a later day or time."
                } else onSchedule(name, start, stop)
            }) { f -> LiveText("Schedule", color = focusedTextColor(f)) }
            LiveFocusRow(onClick = onDismiss) { f -> LiveText("Cancel", color = focusedTextColor(f)) }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }

    when (editing) {
        "time" -> TextInputDialog(
            title = "Start time",
            initial = timeFmt.format(Date(startMs())),
            hint = if (use24h) "20:30" else "8:30 PM",
            confirmLabel = "Set",
            onDismiss = { editing = null },
            onConfirm = { text ->
                parseTime(text)?.let { minuteOfDay = it; problem = null }
                    ?: run { problem = "Couldn't read \"$text\" as a time. Try 8:30 PM or 20:30." }
                editing = null
            }
        )
        "name" -> TextInputDialog(
            title = "Recording name",
            initial = customName ?: "",
            hint = defaultName,
            confirmLabel = "Set",
            onDismiss = { editing = null },
            onConfirm = { text -> customName = text.trim().ifBlank { null }; editing = null }
        )
    }
}

/** "8:30 PM", "8.30pm", "20:30", "2030", "8pm" → minutes after midnight. */
private fun parseTime(raw: String): Int? {
    val t = raw.trim().lowercase(Locale.US).replace(".", ":").replace(" ", "")
    val pm = t.endsWith("pm") || t.endsWith("p")
    val am = t.endsWith("am") || t.endsWith("a")
    val digits = t.removeSuffix("pm").removeSuffix("am").removeSuffix("p").removeSuffix("a")
    val (h, m) = when {
        ':' in digits -> digits.substringBefore(':').toIntOrNull() to digits.substringAfter(':').toIntOrNull()
        digits.length in 3..4 -> digits.dropLast(2).toIntOrNull() to digits.takeLast(2).toIntOrNull()
        else -> digits.toIntOrNull() to 0
    }
    var hour = h ?: return null
    val minute = m ?: return null
    if (minute !in 0..59) return null
    if (pm || am) {
        if (hour !in 1..12) return null
        if (hour == 12) hour = 0
        if (pm) hour += 12
    } else if (hour !in 0..23) return null
    return hour * 60 + minute
}

/** One setting: its name on the left, the value on the right; ◀ ▶ change it. */
@Composable
private fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: ((Int) -> Unit)?,
    onClick: () -> Unit
) {
    LiveFocusRow(
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { e ->
                if (onChange == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onChange(-1); true }
                    Key.DirectionRight -> { onChange(1); true }
                    else -> false
                }
            },
        onClick = onClick
    ) { f ->
        LiveText(label, color = focusedTextColor(f).copy(alpha = 0.75f), size = 15.sp, modifier = Modifier.width(120.dp))
        LiveText(
            (if (onChange != null && f) "◀  " else "") + value + (if (onChange != null && f) "  ▶" else ""),
            color = focusedTextColor(f), size = 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Picking the channel for a new recording from the Recordings screen: your favorites, then
 * channels you watched recently. Any other channel: long-press it in the guide.
 */
@Composable
internal fun ManualRecordingChannelPicker(
    channels: List<LiveChannel>,
    onPick: (LiveChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 520.dp) {
        LiveText("New Recording", size = 20.sp, weight = FontWeight.Bold)
        LiveText(
            "Your favorites and recent channels. For any other channel, long-press it in the guide and choose Schedule a Recording.",
            color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 3
        )
        Spacer(Modifier.height(12.dp))
        if (channels.isEmpty()) {
            LiveText("No favorites or recent channels yet.", color = NuvioTheme.colors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            MenuItem("Close", Modifier.focusRequester(first), onDismiss)
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(channels, key = { it.key }) { ch ->
                    MenuItem(
                        "${ch.number}  ${ch.name}",
                        if (ch.key == channels.first().key) Modifier.focusRequester(first) else Modifier
                    ) { onPick(ch) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}
