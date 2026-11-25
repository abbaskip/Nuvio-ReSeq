package com.nuvio.tv.livetv.ui

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private const val PROGRAMME_RESULT_LIMIT = 100

@Composable
internal fun GuideSearchDialog(
    initial: String,
    onDismiss: () -> Unit,
    onChannels: (String) -> Unit,
    onProgrammes: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    val programmesFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LiveDialog(onDismiss = onDismiss) {
        LiveText("Search live TV", size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LiveTextField(
            value = text,
            onValueChange = { text = it },
            hint = "Programme title, channel name or number",
            focusRequester = fieldFocus,
            onDone = {
                keyboard?.hide()
                programmesFocus.requestFocus()
            }
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveFocusRow(modifier = Modifier.focusRequester(programmesFocus), onClick = {
                if (text.isBlank()) fieldFocus.requestFocus() else onProgrammes(text.trim())
            }) { focused -> LiveText("Search programmes", color = focusedTextColor(focused)) }
            LiveFocusRow(onClick = { onChannels(text.trim()) }) { focused ->
                LiveText("Search channels", color = focusedTextColor(focused))
            }
            LiveFocusRow(onClick = onDismiss) { focused -> LiveText("Cancel", color = focusedTextColor(focused)) }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { fieldFocus.requestFocus() } }
}

@Composable
internal fun GuideProgrammeResults(
    query: String,
    visible: Boolean,
    channels: List<LiveChannel>,
    playlistNames: Map<String, String>,
    guideGeneration: Int,
    settings: LiveTvSettings,
    guideLoading: Boolean,
    now: Long,
    viewModel: LiveTvViewModel,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onPick: (LiveChannel, EpgProgram) -> Unit
) {
    val offset = settings.epgOffsetMinutes
    val days = settings.epgFutureDays
    var result by remember(query, channels, guideGeneration, offset, days) {
        mutableStateOf<Result<List<Pair<LiveChannel, EpgProgram>>>?>(null)
    }
    LaunchedEffect(query, channels, guideGeneration, offset, days, guideLoading) {
        result = try {
            Result.success(viewModel.searchGuideProgrammes(query, channels, PROGRAMME_RESULT_LIMIT + 1))
        } catch (error: SQLiteException) {
            Result.failure(error)
        }
    }
    val listState = rememberLazyListState()
    var selectedIndex by remember { mutableIntStateOf(0) }
    if (!visible) return

    val hits = result?.getOrNull().orEmpty()
    val shown = remember(hits) { hits.take(PROGRAMME_RESULT_LIMIT) }
    val focusIndex = selectedIndex.coerceAtMost(shown.lastIndex)
    val resultFocus = remember { FocusRequester() }
    val editFocus = remember { FocusRequester() }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LiveDialog(onDismiss = onDismiss, width = 640.dp) {
        LiveText("Programme airings for “$query”", size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        val message = when {
            result?.isFailure == true -> "Couldn’t read the TV guide. Try searching again."
            result == null || (shown.isEmpty() && guideLoading) -> "Searching the TV guide…"
            shown.isEmpty() -> "No current or upcoming airings found."
            hits.size > PROGRAMME_RESULT_LIMIT -> "Showing the first $PROGRAMME_RESULT_LIMIT airings. Refine your search for fewer results."
            else -> null
        }
        message?.let { LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 14.sp, maxLines = 2) }
        MenuItem("Edit search", Modifier.focusRequester(editFocus), onEdit)
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 350.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(shown, key = { _, (channel, programme) -> "${channel.key}|${programme.startMs}" }) { index, (channel, programme) ->
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (index == focusIndex) Modifier.focusRequester(resultFocus) else Modifier),
                    onClick = {
                        selectedIndex = index
                        onPick(channel, programme)
                    }
                ) { focused ->
                    val date = dateFormat.format(Date(programme.startMs))
                    val time = formatRange(programme.startMs, programme.stopMs, settings.use24HourClock)
                    val airing = if (programme.isLive(now)) "On now · " else ""
                    Column {
                        LiveText(programme.title, color = focusedTextColor(focused), weight = FontWeight.Bold, maxLines = 2)
                        LiveText(
                            listOfNotNull(channel.name, playlistNames[channel.sourceId].takeIf { playlistNames.size > 1 })
                                .joinToString(" · "),
                            color = focusedTextColor(focused),
                            size = 14.sp,
                            maxLines = 1
                        )
                        LiveText("$airing$date · $time", color = focusedTextColor(focused), size = 14.sp)
                    }
                }
            }
        }
    }
    LaunchedEffect(shown.isNotEmpty()) {
        delay(80)
        if (focusIndex >= 0) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == focusIndex }) {
                listState.scrollToItem(focusIndex)
            }
            runCatching { resultFocus.requestFocus() }
        } else {
            runCatching { editFocus.requestFocus() }
        }
    }
}
