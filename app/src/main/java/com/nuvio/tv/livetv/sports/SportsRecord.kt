package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.record.RecordingSchedule
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recording a game from Sports: the channel you pick, from a few minutes before the start, for
 * as long as you choose (overtime, extra time), using Live TV's own recordings.
 */
internal object SportsRecordings {
    /** Sports start a little early: 5 minutes before kick-off at least. */
    const val EARLY_MS = 5 * 60_000L
    val LENGTHS = (2..12).map { it * 30 } // 1:00 to 6:00

    private val _scheduled = MutableStateFlow<List<RecordingSchedule>>(emptyList())
    val scheduled: StateFlow<List<RecordingSchedule>> = _scheduled.asStateFlow()
    fun update(list: List<RecordingSchedule>) { _scheduled.value = list }

    /** How recordings name a show (the same clean-up Live TV's recordings use). */
    fun titleKey(title: String): String = com.nuvio.tv.livetv.ui.titleMarks(title).title.trim()

    /** The recording set for [ev] (by its title, starting up to the early padding before it). */
    fun forEvent(ev: SportsEvent, list: List<RecordingSchedule> = _scheduled.value): RecordingSchedule? {
        val key = titleKey(ev.title)
        return list.firstOrNull { it.title == key && it.startMs in (ev.startMs - 15 * 60_000L)..(ev.startMs + 60_000L) }
    }

    /** The usual length for the sport, rounded up to the half hour (football 2:00, NFL 3:30). */
    fun defaultLength(ev: SportsEvent): Int {
        val m = SportsCatalog.league(ev.leagueId)?.minutes ?: 180
        val rounded = ((m + 29) / 30) * 30
        return rounded.coerceIn(LENGTHS.first(), LENGTHS.last())
    }

    fun label(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)
}

/** "● REC" on a game card when it's set to record. */
@Composable
internal fun isRecording(ev: SportsEvent): Boolean {
    val list by SportsRecordings.scheduled.collectAsState()
    if (ev.state == GameState.POST || !ev.hasTime) return false
    return remember(list, ev.id, ev.startMs) { SportsRecordings.forEvent(ev, list) != null }
}

/** Record this game: which channel, and for how long. */
@Composable
internal fun RecordDialog(
    ev: SportsEvent,
    matches: List<ChannelMatch>,
    padding: Pair<Int, Int>,
    use24h: Boolean,
    hideScores: Boolean,
    onRecord: (ChannelMatch, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val channels = remember(matches) { matches.take(6) }
    var pick by remember(ev.id) { mutableIntStateOf(0) }
    var minutes by remember(ev.id) { mutableIntStateOf(SportsRecordings.defaultLength(ev)) }
    val first = remember { FocusRequester() }
    val (preMin, postMin) = padding
    val early = maxOf(SportsRecordings.EARLY_MS, preMin * 60_000L)
    val live = ev.state == GameState.LIVE
    val startAt = if (live) System.currentTimeMillis() else ev.startMs - early
    val endAt = ev.startMs + minutes * 60_000L + postMin * 60_000L
    SportsDialog(onDismiss = onDismiss, width = 560.dp, header = { GameHeaderBands(ev, hideScores) }) {
        LiveText("Record", color = Color.White, size = 18.sp, weight = FontWeight.Black)
        Spacer(Modifier.height(10.dp))
        LiveText("CHANNEL", color = SportsLook.Dim, size = 10.sp, weight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.heightIn(max = 210.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(channels, key = { _, m -> m.channel.key }) { i, m ->
                val src = m.channel.sourceName.takeIf { it.isNotBlank() }
                SportsMenuRow(
                    (if (i == pick) "✓  " else "     ") + m.channel.name,
                    if (i == 0) Modifier.focusRequester(first) else Modifier,
                    detail = listOfNotNull(src, if (m.sure) "SHOWING IT" else "MAYBE").joinToString(" · ")
                ) { pick = i }
            }
        }
        Spacer(Modifier.height(10.dp))
        LengthPicker(minutes) { minutes = it }
        Spacer(Modifier.height(8.dp))
        LiveText(
            (if (live) "Starts now" else "Starts ${formatClock(startAt, use24h)} (${early / 60_000} min early)") +
                "  ·  Ends ${formatClock(endAt, use24h)}" + (if (postMin > 0) " (with $postMin min extra)" else ""),
            color = SportsLook.Dim, size = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SportsAction("●  Record") { channels.getOrNull(pick)?.let { onRecord(it, minutes) } }
            SportsAction("Cancel") { onDismiss() }
        }
    }
    LaunchedEffect(ev.id) { delay(80); runCatching { first.requestFocus() } }
}

/** "Record for  ◂ 2:00 ▸": left and right change it. */
@Composable
private fun LengthPicker(minutes: Int, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val steps = SportsRecordings.LENGTHS
    Row(
        Modifier
            .fillMaxWidth()
            .clip(SportsLook.SmallShape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(if (focused) NuvioTheme.focusRing.border(2.dp) else androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent), SportsLook.SmallShape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val i = steps.indexOf(minutes).coerceAtLeast(0)
                when (e.key) {
                    // At either end it stays put (the highlight doesn't wander off the row).
                    Key.DirectionLeft -> { onChange(steps[(i - 1).coerceAtLeast(0)]); true }
                    Key.DirectionRight -> { onChange(steps[(i + 1).coerceAtMost(steps.size - 1)]); true }
                    else -> false
                }
            }
            .focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onChange(steps[(steps.indexOf(minutes) + 1) % steps.size])
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val ink = Color.White
        LiveText("Record for", color = ink, size = 15.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        LiveText("◂", color = if (focused) ink else SportsLook.Dim, size = 15.sp, weight = FontWeight.Black)
        Spacer(Modifier.width(10.dp))
        LiveText(SportsRecordings.label(minutes), color = ink, size = 16.sp, weight = FontWeight.Black)
        Spacer(Modifier.width(10.dp))
        LiveText("▸", color = if (focused) ink else SportsLook.Dim, size = 15.sp, weight = FontWeight.Black)
    }
}

/** The red "● REC" tag. */
@Composable
internal fun RecBadge() = SportsBadge("● REC", Color(0xFFE53935), Color.White)
