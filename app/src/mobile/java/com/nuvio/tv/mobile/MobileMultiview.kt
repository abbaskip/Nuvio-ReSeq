// Multiview touch selection, resizing and picker follow the mobile reference. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.*
import com.nuvio.tv.livetv.multiview.*
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.livetv.ui.KeepScreenAwake

@Composable
internal fun MobileMultiview(back: () -> Unit, vm: MultiviewViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val limit = remember { MultiviewDevicePolicy.read(context) }
    if (limit == 0) { Column { BrowseHeading("Multiview", back); Label("No spare video decoder is available on this device.") }; return }
    val active by vm.activeProfileId.collectAsStateWithLifecycle()
    val initial = remember { active }
    val latestBack by rememberUpdatedState(back)
    val owner = remember {
        MultiviewRecovery.opened(context)
        val layout = MultiviewLayoutMemory.load(context)?.takeIf { it.paneCount <= limit }
            ?: if (limit >= 2) MultiviewLayout.TWO else MultiviewLayout.ONE
        MultiviewLifecycle(releaseSingle = vm.playback::releaseNow, create = {
            MultiviewPlaybackSession(context, initialLayout = layout, maxPanes = limit,
                mayPlayChannel = { channel -> vm.activeProfileId.value == initial && vm.allowedChannels.value.orEmpty().any { it == channel } },
                autoReconnect = { vm.autoReconnect.value }, findBackup = { _, failed, tried -> vm.backupFor(failed, tried) },
                startFailure = vm::startFailure)
        }, foreground = MultiviewPlaybackSession::onForeground, background = MultiviewPlaybackSession::onBackground, release = MultiviewPlaybackSession::release)
    }
    val session = owner.session
    DisposableEffect(owner) { onDispose { owner.close(); MultiviewRecovery.left(context) } }
    LifecycleResumeEffect(owner) { owner.onForeground(); onPauseOrDispose { owner.onBackground() } }
    val allowed by vm.allowedChannels.collectAsStateWithLifecycle()
    val groups by vm.pickerGroups.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<Int?>(null) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(active) { if (active != initial) { owner.close(); latestBack() } }
    LaunchedEffect(allowed) { session.validateAllowedChannels(allowed.orEmpty()) }
    KeepScreenAwake(state.isForeground && state.panes.any { it.channelKey != null })
    BackHandler(expanded != null) { expanded = null }
    Column(Modifier.fillMaxSize()) {
        if (expanded == null) {
            BrowseHeading("Multiview", back)
            LazyRow { items(MultiviewLayout.entries.filter { it.paneCount <= limit }) { layout ->
                Action(layout.name.lowercase().replace('_', ' '), selected = state.layout == layout) { session.setLayout(layout); MultiviewLayoutMemory.save(context, layout) }
            } }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            val w = maxWidth
            val h = maxHeight
            val rects = multiviewPaneBounds(state.layout)
            state.panes.filter { expanded == null || expanded == it.id }.forEach { pane ->
                key(pane.id) {
                    val r = if (expanded == pane.id) MultiviewPaneBounds(pane.id, 0f, 0f, 1f, 1f) else rects.first { it.id == pane.id }
                    Box(Modifier.offset(w * r.left, h * r.top).width(w * (r.right - r.left)).height(h * (r.bottom - r.top))
                        .padding(2.dp).border(1.dp, if (state.focusedPane == pane.id) MaterialTheme.colorScheme.primary else Color.DarkGray)) {
                        LivePlayerSurface(pane.player, Modifier.fillMaxSize(), useSurfaceView = pane.isProtected)
                        Box(Modifier.fillMaxSize().combinedClickable(onClick = {
                            session.focusPane(pane.id); if (pane.channelKey == null) picker = pane.id
                        }, onDoubleClick = { session.focusPane(pane.id); expanded = if (expanded == pane.id) null else pane.id },
                            onLongClick = { picker = pane.id }))
                        Label(pane.error ?: pane.channelName ?: "Choose channel", Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .7f)).padding(4.dp), small = true)
                    }
                }
            }
        }
        LazyRow {
            item { Action("Choose channel") { picker = state.focusedPane } }
            item { Action(if (expanded == null) "Expand" else "All panes") { expanded = if (expanded == null) state.focusedPane else null } }
            item { Action("Clear pane") { session.clearPane(state.focusedPane) } }
            if (state.panes.getOrNull(state.focusedPane)?.error != null) item { Action("Retry") { session.retryPane(state.focusedPane) } }
        }
    }
    picker?.let { pane ->
        var group by remember { mutableStateOf<String?>(null) }
        var query by remember { mutableStateOf("") }
        val channels by produceState<List<MultiviewChannelOption>>(emptyList(), group, allowed) { value = vm.channelsIn(group) }
        val matches by produceState(emptyList<MultiviewChannelOption>(), channels, query) { value = searchMultiviewChannels(channels, query) }
        TouchSheet({ picker = null }) {
            MobileTextInput("Search channels", query) { query = it }
            LazyRow { item { Action("All", selected = group == null) { group = null } }; items(groups) { g -> Action(g.title, selected = group == g.id) { group = g.id } } }
            LazyColumn { items(matches, key = { it.key }) { choice -> Action(choice.title, Modifier.fillMaxWidth()) {
                val permitted = vm.allowedChannels.value.orEmpty().firstOrNull { it.key == choice.key }
                if (permitted != null && vm.activeProfileId.value == initial) { session.assignChannel(pane, permitted); session.focusPane(pane); picker = null }
            } } }
        }
    }
}
