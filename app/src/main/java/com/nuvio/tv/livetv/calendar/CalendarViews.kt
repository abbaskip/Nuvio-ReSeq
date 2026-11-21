package com.nuvio.tv.livetv.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.sports.*
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Adapted from NuvioPlusIPTV CalendarScreen's Segmented, GameHubCard, GameArt, WeekView and MonthView.
internal enum class CalendarMode(@StringRes val label: Int) { DAY(R.string.calendar_day_view), WEEK(R.string.calendar_week_view), MONTH(R.string.calendar_month_view) }
internal enum class CalendarFilter(@StringRes val label: Int) {
    ALL(R.string.calendar_all), SHOWS(R.string.calendar_shows), MOVIES(R.string.calendar_movies), SPORTS(R.string.calendar_sports);
    fun includes(kind: CalendarKind) = when (this) {
        ALL -> kind != CalendarKind.GUIDE
        SHOWS -> kind == CalendarKind.EPISODE
        SPORTS -> kind == CalendarKind.SPORT
        MOVIES -> kind == CalendarKind.CINEMA || kind == CalendarKind.DIGITAL || kind == CalendarKind.DISC
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CalendarPill(label: String, selected: Boolean? = null, modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val theme = NuvioTheme.colors
    val shape = RoundedCornerShape(50)
    val active = selected == true
    Box(modifier.clip(shape).background(if (active) theme.Secondary else theme.BackgroundElevated)
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused }
        .then(if (selected == null) Modifier else Modifier.semantics { this.selected = selected })
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
            onLongClickLabel = if (onLongClick != null) stringResource(R.string.calendar_choose_sports) else null,
            onLongClick = onLongClick, onClick = onClick)
        .padding(horizontal = 11.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        LiveText(label, size = 13.sp, weight = if (active || focused) FontWeight.Bold else FontWeight.Medium,
            color = if (active) theme.OnSecondary else theme.TextPrimary)
    }
}

@Composable
private fun Tile(modifier: Modifier, marked: Boolean = false, selected: Boolean = false, onFocus: () -> Unit = {},
    onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val theme = NuvioTheme.colors
    Box(modifier.clip(shape).background(theme.BackgroundCard)
        .border(if (focused || selected) 2.dp else 1.dp, when {
            focused -> Color.White
            selected -> theme.Secondary
            marked -> theme.Secondary.copy(alpha = .8f)
            else -> Color.Transparent
        }, shape)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick), content = content)
}

@Composable
private fun GameArt(row: CalendarItem, clock: (Long) -> String, modifier: Modifier) {
    val event = row.fixture ?: return
    val sides = row.guideSides.ifEmpty { SportsCatalog.league(event.leagueId)?.let { event.ordered(it.sport) } ?: event.sides }
    if (sides.size != 2) {
        Box(modifier.background(leagueColor(SportsCatalog.league(event.leagueId))), contentAlignment = Alignment.Center) {
            LeagueMark(SportsCatalog.league(event.leagueId), 22.dp)
        }
        return
    }
    Box(modifier) {
        Row(Modifier.fillMaxSize()) {
            for ((i, side) in sides.withIndex()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(teamBandColor(side.team))
                    .padding(start = if (i == 1) 12.dp else 0.dp, end = if (i == 0) 12.dp else 0.dp), contentAlignment = Alignment.Center) {
                    TeamLogo(side.team, 22.dp)
                }
            }
        }
        LiveText(row.time?.let(clock) ?: "v", Modifier.align(Alignment.Center), color = Color.White, size = 10.sp,
            weight = FontWeight.ExtraBold, shadow = true)
    }
}

@Composable
private fun EntryCard(row: CalendarItem, clock: (Long) -> String, modifier: Modifier, onOpen: (CalendarItem) -> Unit) {
    val event = row.fixture
    val detail = if (row.kind == CalendarKind.SPORT) {
        val channel = when {
            row.channelCount > 1 -> stringResource(R.string.calendar_channel_count, row.channelCount)
            row.detail.isBlank() -> stringResource(R.string.calendar_channel_tbc)
            else -> row.detail
        }
        listOfNotNull(row.matchDay?.let { stringResource(R.string.calendar_match_day, it) }, channel).joinToString(" · ")
    } else row.detail
    val sides = row.guideSides.ifEmpty { if (event == null) emptyList() else SportsCatalog.league(event.leagueId)?.let { event.ordered(it.sport) } ?: event.sides }
    val star = row.followedTeam != null
    Tile(modifier.semantics { contentDescription = row.title }, marked = star, onClick = { onOpen(row) }) {
        if (sides.size == 2) {
            Column(Modifier.fillMaxSize().background(SportsLook.Card)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveText(row.leagueName.orEmpty(), Modifier.weight(1f), size = 10.sp, color = SportsLook.Dim, weight = FontWeight.Bold)
                    if (star) LiveText("★", size = 13.sp, color = NuvioTheme.colors.Secondary)
                }
                sides.forEach { side ->
                    val ink = teamBandColor(side.team)
                    val followedSide = side.team.key in row.followedSides
                    Row(Modifier.fillMaxWidth().weight(1f)
                        .background(Brush.horizontalGradient(listOf(ink, ink.copy(alpha = .50f), SportsLook.Card)))
                        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        TeamLogo(side.team, 21.dp)
                        LiveText((if (followedSide) "★ " else "") + side.team.name, size = 13.sp, weight = if (followedSide) FontWeight.ExtraBold else FontWeight.Bold, color = Color.White)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.time?.let { LiveText(clock(it), size = 10.sp, weight = FontWeight.Bold, color = Color.White); Spacer(Modifier.width(5.dp)) }
                    LiveText(detail, Modifier.weight(1f), size = 10.sp, color = SportsLook.Dim)
                }
            }
        } else {
            if (row.artwork != null) AsyncImage(row.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (event != null) Box(Modifier.fillMaxSize().background(leagueColor(SportsCatalog.league(event.leagueId))), contentAlignment = Alignment.CenterEnd) {
                LeagueMark(SportsCatalog.league(event.leagueId), 42.dp)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .87f)))))
            row.time?.let { Box(Modifier.align(Alignment.TopStart).padding(5.dp)) { SportsBadge(clock(it), Color.Black.copy(alpha = .8f), Color.White) } }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(7.dp)) {
                LiveText((if (star) "★ " else "") + row.title, size = 15.sp, maxLines = 2, color = Color.White, weight = FontWeight.Bold, shadow = true)
                val caption = listOfNotNull(if (row.kind == CalendarKind.SPORT) row.leagueName else kindLabel(row.kind),
                    detail.takeIf(String::isNotBlank)).joinToString(" · ")
                if (caption.isNotBlank()) LiveText(caption, size = 11.sp, color = Color.White, shadow = true)
            }
        }
    }
}

@Composable
private fun kindLabel(kind: CalendarKind) = when (kind) {
    CalendarKind.EPISODE -> stringResource(R.string.calendar_episode)
    CalendarKind.CINEMA -> stringResource(R.string.calendar_cinema)
    CalendarKind.DIGITAL -> stringResource(R.string.calendar_digital)
    CalendarKind.DISC -> stringResource(R.string.calendar_disc)
    else -> null
}

@Composable
internal fun DayCards(rows: List<CalendarItem>, clock: (Long) -> String, onOpen: (CalendarItem) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(rows, key = { it.key }) { row -> EntryCard(row, clock, Modifier.fillMaxWidth().height(112.dp), onOpen) }
    }
}

@Composable
internal fun WeekView(window: CalendarWindow, selected: Long, grouped: Map<Long, List<CalendarItem>>, locale: Locale,
    focus: Modifier, clock: (Long) -> String, select: (Long) -> Unit, openDay: (Long) -> Unit, onOpen: (CalendarItem) -> Unit) {
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("EEE d", locale) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (day in window.first..window.last) key(day) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Tile(Modifier.fillMaxWidth().height(29.dp).then(if (day == selected) focus else Modifier),
                    selected = day == selected, onFocus = { select(day) }, onClick = { openDay(day) }) {
                    LiveText(formatter.format(LocalDate.ofEpochDay(day)), Modifier.align(Alignment.Center), size = 13.sp, weight = FontWeight.Bold)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(grouped[day].orEmpty(), key = { it.key }) { row ->
                        EntryCard(row, clock, Modifier.fillMaxWidth().height(94.dp), onOpen)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MonthView(month: CalendarWindow, selected: Long, today: Long, grouped: Map<Long, List<CalendarItem>>, selectedRows: List<CalendarItem>,
    locale: Locale, focus: Modifier, clock: (Long) -> String, select: (Long) -> Unit, openDay: (Long) -> Unit,
    onOpen: (CalendarItem) -> Unit) {
    val first = CalendarWindow.week(LocalDate.ofEpochDay(month.first), locale).first
    val weeks = ((month.last - first) / 7 + 1).toInt()
    val dayName = remember(locale) { DateTimeFormatter.ofPattern("EEE", locale) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(91.dp)) {
            val width = (maxWidth - 28.dp) / 5
            LazyRow(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(selectedRows, key = { it.key }) { row -> EntryCard(row, clock, Modifier.width(width).fillMaxHeight(), onOpen) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0..6) LiveText(dayName.format(LocalDate.ofEpochDay(first + i)).uppercase(locale), Modifier.weight(1f).padding(start = 3.dp),
                size = 10.sp, color = NuvioTheme.colors.TextSecondary, weight = FontWeight.Bold)
        }
        repeat(weeks) { w ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0..6) {
                    val day = first + w * 7 + i
                    val entries = grouped[day].orEmpty()
                    val entry = entries.firstOrNull()
                    val inMonth = day in month
                    val number = LocalDate.ofEpochDay(day).dayOfMonth.toString()
                    Tile(Modifier.weight(1f).fillMaxHeight().alpha(if (inMonth) 1f else .42f)
                        .then(if (day == selected) focus else Modifier), selected = day == today,
                        onFocus = { if (inMonth) select(day) }, onClick = { openDay(day) }) {
                        if (entry?.fixture != null) GameArt(entry, clock, Modifier.fillMaxSize())
                        else if (entry?.artwork != null) {
                            AsyncImage(entry.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)))
                        }
                        LiveText(number, Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 1.dp), size = 11.sp,
                            color = Color.White, weight = FontWeight.ExtraBold, shadow = true)
                        if (entry != null && entry.guideSides.isEmpty() && (entry.fixture == null || entry.fixture.sides.size != 2))
                            LiveText(entry.title, Modifier.align(Alignment.BottomStart).padding(start = 4.dp, end = 18.dp, bottom = 2.dp),
                                size = 10.sp, color = Color.White, weight = FontWeight.Bold, shadow = true)
                        if (entries.size > 1) Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)) {
                            SportsBadge("+" + (entries.size - 1), Color.Black.copy(alpha = .82f), Color.White)
                        }
                    }
                }
            }
        }
    }
}
