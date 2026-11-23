// Touch guide adapted from homelessbrian/NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.ChannelLogo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.tv.livetv.model.*
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.livetv.ui.formatClock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val HALF_HOUR = 1_800_000L
private val slotWidth = 112.dp

@Composable
internal fun MobileGuide(vm: LiveTvViewModel, onTune: (LiveChannel) -> Unit, onReplay: () -> Unit, onSetup: () -> Unit) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val scroll = rememberScrollState()
    val currentSlot = now / HALF_HOUR * HALF_HOUR
    val earliest = currentSlot - settings.epgPastHours.coerceAtLeast(0) * 2L * HALF_HOUR
    val latest = currentSlot + (settings.epgFutureDays.coerceAtLeast(1) * 48L - 1) * HALF_HOUR
    var anchor by rememberSaveable { mutableStateOf<Long?>(null) }
    var dates by rememberSaveable { mutableStateOf(false) }
    val start = (anchor ?: currentSlot).coerceIn(earliest, latest)
    val end = start + 12 * HALF_HOUR
    var selected by remember { mutableStateOf<Pair<LiveChannel, EpgProgram>?>(null) }
    var locked by remember { mutableStateOf<ChannelGroup?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LiveChannel?>(null) }
    var groupOptions by remember { mutableStateOf(false) }
    var pickingGroup by rememberSaveable { mutableStateOf(false) }
    val playback by vm.playbackState.collectAsStateWithLifecycle()
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.maintainVisibleGuide() }
    }
    LaunchedEffect(vm, list, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                .distinctUntilChanged().collect { if (it.isNotEmpty()) vm.loadVisibleGuideChannels(it) }
        }
    }
    LaunchedEffect(vm, start, end) { vm.ensureGuideRange(start, end) }
    val scope = rememberCoroutineScope()
    fun movePage(target: Long?) {
        anchor = target?.coerceIn(earliest, latest)
        scope.launch { scroll.scrollTo(0) }
    }
    Column(Modifier.fillMaxSize()) {
        val colors = MaterialTheme.colorScheme
        val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 520
        val groupName = ui.groups.firstOrNull { it.id == ui.selectedGroupId }?.title
            ?: if (ui.selectedGroupId == ChannelGroup.SEARCH) "Search results" else "All channels"
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val groupWidth = if (wide) Modifier.widthIn(max = 300.dp) else Modifier.weight(1f)
            Row(groupWidth.mediaPanel().clickable(role = Role.Button) { pickingGroup = true }
                .heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.FilterList, null, Modifier.size(18.dp), tint = colors.primary)
                Text("Group: $groupName", Modifier.weight(1f), fontFamily = condensed, color = colors.onSurface,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
            }
            MediaIconAction("Search", Icons.Default.Search, showLabel = wide, selected = searching) {
                searching = !searching
                if (searching) vm.setSearchQuery("") else vm.selectGroup(ChannelGroup.ALL)
            }
            MediaIconAction("Sources", Icons.Default.Dns, showLabel = wide, onClick = onSetup)
        }
        if (!ui.hasSources || ui.channels.isEmpty() && ui.groups.isEmpty()) {
            Label(status.error ?: status.message ?: if (status.loading) "Loading channels…" else "Add a playlist to see channels here.",
                Modifier.padding(12.dp))
            return@Column
        }
        if (searching) MobileTextInput("Channel or programme", ui.searchQuery, vm::setSearchQuery)
        if (ui.channels.isEmpty()) Label("No channels in this group.", Modifier.padding(8.dp))
        else ChannelTimeline(ui.channels, programs, now, start, 12, settings.use24HourClock, list, scroll,
            onTune = onTune, onProgram = { channel, program -> selected = channel to program }, onChannelHold = { editing = it },
            playingKey = playback.channelKey, onTime = { dates = true })
    }
    locked?.let { group -> MobilePinPrompt(group.title, { locked = null }) { pin ->
        vm.unlockGroup(group, pin).also { if (it) { locked = null; searching = false; vm.selectGroup(group.id) } }
    } }
    editing?.let { channel -> MobileChannelEditor(channel, vm, { editing = null }) }
    if (pickingGroup) TouchSheet({ pickingGroup = false }) {
        BrowseHeading("Channel groups") { pickingGroup = false }
        LazyColumn(contentPadding = PaddingValues(horizontal = 10.dp)) {
            item { MediaIconAction("Manage groups", Icons.Default.Tune, showLabel = true) { pickingGroup = false; groupOptions = true } }
            items(ui.groups.filterNot { it.id == ChannelGroup.SEARCH }, key = { it.id }) { group ->
                Action(group.title, Modifier.fillMaxWidth(), selected = ui.selectedGroupId == group.id) {
                    pickingGroup = false
                    if (group.locked) locked = group else { searching = false; vm.selectGroup(group.id) }
                }
            }
        }
    }
    if (groupOptions) MobileGuideGroups(vm) { groupOptions = false }
    if (dates) TouchSheet({ dates = false }) {
        val zone = java.time.ZoneId.systemDefault()
        fun localDate(time: Long) = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        val first = localDate(earliest)
        val count = (localDate(latest).toEpochDay() - first.toEpochDay() + 1).toInt()
        val formatter = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)
        SectionTitle("Jump to guide time")
        Row {
            if (start > earliest) Action("Previous 6 hours") { movePage(start - 12 * HALF_HOUR); dates = false }
            Action("Jump to live") { movePage(null); dates = false }
            if (end <= latest) Action("Next 6 hours") { movePage(end); dates = false }
        }
        LazyColumn {
            items(count) { offset ->
                val date = first.plusDays(offset.toLong())
                Action(formatter.format(date), Modifier.fillMaxWidth(), selected = date == localDate(start)) {
                    movePage(date.atStartOfDay(zone).toInstant().toEpochMilli())
                    dates = false
                }
            }
        }
    }
    selected?.let { (channel, program) ->
        val full by produceState(program, channel.key, program) { value = vm.details(channel.key, program) }
        TouchSheet({ selected = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                SectionTitle(full.title)
                Label(channel.name + " · " + formatClock(full.startMs, settings.use24HourClock) + " – " +
                    formatClock(full.stopMs, settings.use24HourClock))
                full.description?.let { Label(it, Modifier.padding(vertical = 8.dp)) }
                Action("Watch channel") { selected = null; onTune(channel) }
                val user by vm.userState.collectAsStateWithLifecycle()
                var result by remember { mutableStateOf<String?>(null) }
                if (channel.catchup != null && full.startMs <= now) Action("Watch from start") {
                    if (vm.playCatchup(channel, full)) { selected = null; onReplay() } else result = "This programme is outside the catch-up window."
                }
                if (full.startMs > now) Action(if (user.reminders.any { it.channelKey == channel.key && it.startMs == full.startMs }) "Cancel reminder" else "Remind me") {
                    vm.toggleReminder(channel, full)
                }
                Action(if (vm.scheduler.isScheduled(channel.key, full)) "Cancel recording" else "Record programme") { result = vm.toggleScheduled(channel, full) }
                Action(if (vm.isSeries(channel, full)) "Cancel series recording" else "Record series") { result = vm.toggleSeries(channel, full) }
                result?.let { Label(it) }
                Action(if (channel.key in user.favorites) "Remove from favourites" else "Add to favourites") {
                    vm.toggleFavorite(channel)
                }
            }
        }
    }
}

@Composable
internal fun guideHighlight(overlay: Boolean, tuned: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    val neutral = lerp(Color.Black, colors.surfaceVariant, .35f)
    return lerp(neutral, colors.primary, if (tuned) .29f else .17f)
        .copy(alpha = if (overlay) .55f else 1f)
}

@Composable
internal fun ChannelIdentity(channel: LiveChannel, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        if (!channel.logo.isNullOrBlank()) ChannelLogo(channel.logo, 19.dp)
        Text(channel.name, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, fontFamily = condensed, color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
    }
}

@Composable
internal fun ChannelTimeline(channels: List<LiveChannel>, programs: Map<String, List<EpgProgram>>,
    now: Long, start: Long, slots: Int, use24h: Boolean, list: LazyListState, scroll: ScrollState,
    onTune: (LiveChannel) -> Unit, onProgram: (LiveChannel, EpgProgram) -> Unit, onChannelHold: ((LiveChannel) -> Unit)? = null,
    playingKey: String? = null, overlay: Boolean = false, onTime: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val base = if (overlay) Color.Black.copy(alpha = .20f) else colors.surface
    val liveFill = guideHighlight(overlay, tuned = false)
    val tunedFill = guideHighlight(overlay, tuned = true)
    val rowHeight = (22.dp + 28.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale).coerceAtLeast(50.dp)
    val end = start + HALF_HOUR * slots
    Column {
        val channelWidth = 108.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(channelWidth).then(if (onTime == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onTime))
                .heightIn(min = 25.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (onTime != null) Icon(Icons.Default.Schedule, "Guide time", Modifier.size(15.dp).padding(end = 2.dp))
                Label("Channel", small = true)
            }
            Row(Modifier.horizontalScroll(scroll)) {
                repeat(slots) { i -> Label(formatClock(start + HALF_HOUR * i, use24h),
                    Modifier.width(slotWidth).padding(4.dp), small = true) }
            }
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(channels, key = { it.key }) { channel ->
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    ChannelIdentity(channel, Modifier.width(channelWidth).fillMaxHeight()
                        .background(if (channel.key == playingKey) tunedFill else base)
                        .combinedClickable(onClick = { onTune(channel) }, onLongClick = onChannelHold?.let { hold -> { hold(channel) } }))
                    val rows = programs[channel.key].orEmpty()
                    val visible = remember(rows, start) { rows.filter { it.stopMs > start && it.startMs < end } }
                    Box(Modifier.horizontalScroll(scroll)) {
                        Box(Modifier.width(slotWidth * slots).fillMaxHeight().clipToBounds()) {
                            visible.forEach { program ->
                                val from = maxOf(start, program.startMs)
                                val to = minOf(end, program.stopMs)
                                if (to > from) {
                                    Box(Modifier.offset(x = slotWidth * ((from - start).toFloat() / HALF_HOUR))
                                        .width(slotWidth * ((to - from).toFloat() / HALF_HOUR)).fillMaxHeight()
                                        .padding(start = 1.dp).background(when {
                                            !program.isLive(now) -> base
                                            channel.key == playingKey -> tunedFill
                                            else -> liveFill
                                        })
                                        .clickable { onProgram(channel, program) }.padding(4.dp), contentAlignment = Alignment.CenterStart) {
                                        Label(program.title, small = true, lines = 2)
                                    }
                                }
                            }
                            if (visible.isEmpty()) Label("No listings", Modifier.padding(8.dp), small = true)
                            if (now in start until end) Spacer(Modifier.offset(x = slotWidth * ((now - start).toFloat() / HALF_HOUR))
                                .width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
    }
}
