// Touch guide adapted from homelessbrian/NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
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
internal fun MobileGuide(vm: LiveTvViewModel, onTune: (LiveChannel) -> Unit, onSetup: () -> Unit) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val scroll = rememberScrollState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    val start = (now / HALF_HOUR + page * 12L) * HALF_HOUR
    val end = start + 12 * HALF_HOUR
    var selected by remember { mutableStateOf<Pair<LiveChannel, EpgProgram>?>(null) }
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
    fun movePage(target: Int) {
        page = target
        scope.launch { scroll.scrollTo(0) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("Live guide") }
            Action("Sources", onClick = onSetup)
        }
        if (!ui.hasSources || ui.channels.isEmpty() && ui.groups.isEmpty()) {
            Label(status.error ?: status.message ?: if (status.loading) "Loading channels…" else "Add a playlist to see channels here.",
                Modifier.padding(12.dp))
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(ui.groups.filterNot { it.locked || it.id == ChannelGroup.SEARCH }, key = { it.id }) { group ->
                Action(group.title, selected = ui.selectedGroupId == group.id) { vm.selectGroup(group.id) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if ((page - 1) * 6 >= -settings.epgPastHours) Action("Earlier") { movePage(page - 1) }
            Action("Now") { movePage(0) }
            if (page < 3) Action("Later") { movePage(page + 1) }
            Label(java.text.DateFormat.getDateInstance().format(java.util.Date(start)), Modifier.padding(start = 8.dp), small = true)
        }
        if (ui.channels.isEmpty()) Label("No channels in this group.", Modifier.padding(8.dp))
        else ChannelTimeline(ui.channels, programs, now, start, 12, settings.use24HourClock, list, scroll,
            onTune = onTune, onProgram = { channel, program -> selected = channel to program })
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
                Action(if (channel.key in user.favorites) "Remove from favourites" else "Add to favourites") {
                    vm.toggleFavorite(channel)
                }
            }
        }
    }
}

@Composable
internal fun ChannelTimeline(channels: List<LiveChannel>, programs: Map<String, List<EpgProgram>>,
    now: Long, start: Long, slots: Int, use24h: Boolean, list: LazyListState, scroll: ScrollState,
    onTune: (LiveChannel) -> Unit, onProgram: (LiveChannel, EpgProgram) -> Unit) {
    val end = start + HALF_HOUR * slots
    Column {
        val channelWidth = 108.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Channel", Modifier.width(channelWidth).padding(start = 8.dp), small = true)
            Row(Modifier.horizontalScroll(scroll)) {
                repeat(slots) { i -> Label(formatClock(start + HALF_HOUR * i, use24h),
                    Modifier.width(slotWidth).padding(4.dp), small = true) }
            }
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(channels, key = { it.key }) { channel ->
                Row(Modifier.fillMaxWidth().height(58.dp)) {
                    Box(Modifier.width(channelWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
                        .clickable { onTune(channel) }.padding(6.dp), contentAlignment = Alignment.CenterStart) {
                        Label(channel.name, small = true, strong = true, lines = 2)
                    }
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
                                        .padding(start = 1.dp).background(if (program.isLive(now)) MaterialTheme.colorScheme.surfaceVariant
                                            else MaterialTheme.colorScheme.surface)
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
