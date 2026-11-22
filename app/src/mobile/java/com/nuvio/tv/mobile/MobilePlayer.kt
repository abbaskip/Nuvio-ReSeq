// Channel switching and panel placement adapted from NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.livetv.ui.PauseLiveTvInBackground
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.ui.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

@Composable
internal fun MobilePlayer(vm: LiveTvViewModel, onClose: () -> Unit) {
    val state by vm.playbackState.collectAsStateWithLifecycle()
    val player by vm.playback.playerFlow.collectAsStateWithLifecycle()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(false) }
    var guideMode by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var detail by remember { mutableStateOf<EpgProgram?>(null) }
    var controls by rememberSaveable { mutableStateOf(true) }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(controls, panel, state.isPlaying, state.channelKey) {
        if (controls && !panel && state.isPlaying) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(6000, containsControls = true) ?: 6000)
            controls = false
        }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    BackHandler(panel) { panel = false }
    DisposableEffect(vm) {
        com.nuvio.tv.livetv.ui.LiveTvFullscreen.active.value = true
        vm.playback.attach()
        onDispose {
            vm.playback.detach()
            com.nuvio.tv.livetv.ui.LiveTvFullscreen.active.value = false
        }
    }
    PauseLiveTvInBackground(vm.playback)
    val tune = { direction: Int -> vm.zap(vm.playbackState.value.channelKey, direction)?.let(vm::preview) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        LivePlayerSurface(player, Modifier.fillMaxSize(), useSurfaceView = true, onAttached = vm.playback::onSurfaceAttached,
            pictureShape = vm.pictureShapeOf(state.channelKey))
        Box(Modifier.fillMaxSize().clickable { if (panel) panel = false else controls = !controls }
            .pointerInput(vm) {
                var distance = 0f
                detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { _, dy -> distance += dy },
                    onDragEnd = { if (abs(distance) >= 48.dp.toPx()) tune(if (distance < 0) 1 else -1) })
            })
        if (controls && !panel) {
            Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
                Action("Back", onClick = onClose)
                Label(vm.playback.playingChannel?.name.orEmpty(), Modifier.background(Color.Black.copy(alpha = .7f)).padding(6.dp))
            }
            Row(Modifier.align(Alignment.BottomEnd)) {
                Action(if (state.userPaused) "Resume" else "Pause") { vm.playback.togglePause() }
                Action("Channels") { panel = true }
            }
        }
        state.error?.let { Label(it, Modifier.align(Alignment.Center).background(Color.Black).padding(8.dp)) }
        if (panel) {
            val tablet = minOf(maxWidth, maxHeight) >= 600.dp
            val sideways = maxWidth > maxHeight && !tablet
            val guide = guideMode ?: tablet
            val bounds = if (sideways) Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.46f)
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.48f)
            Column(bounds.background(MaterialTheme.colorScheme.surface)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Action("List", selected = !guide) { guideMode = false }
                    Action("Guide", selected = guide) { guideMode = true }
                    Spacer(Modifier.weight(1f))
                    Action("Close") { panel = false }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(ui.groups.filterNot { it.locked || it.id == com.nuvio.tv.livetv.model.ChannelGroup.SEARCH }, key = { it.id }) {
                        Action(it.title, selected = ui.selectedGroupId == it.id) { vm.selectGroup(it.id) }
                    }
                }
                val list = key(ui.selectedGroupId) {
                    rememberLazyListState(initialFirstVisibleItemIndex = ui.channels.indexOfFirst { it.key == state.channelKey }.coerceAtLeast(0))
                }
                LaunchedEffect(list, now / 60_000L) {
                    snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }.collectLatest { keys ->
                        if (keys.isNotEmpty()) vm.refreshPlaybackListings(keys, now)
                    }
                }
                val switch: (LiveChannel) -> Unit = { channel -> vm.preview(channel); panel = false }
                if (guide) ChannelTimeline(ui.channels, programs, now, now / 1_800_000L * 1_800_000L,
                    8, settings.use24HourClock, list, rememberScrollState(), switch) { channel, programme ->
                    if (programme.isLive(now)) switch(channel) else detail = programme
                }
                else LazyColumn(state = list) {
                    items(ui.channels, key = { it.key }) { channel ->
                        val current = programs[channel.key]?.firstOrNull { it.isLive(now) }
                        Column(Modifier.fillMaxWidth().clickable { vm.preview(channel); panel = false }
                            .heightIn(min = 48.dp).padding(6.dp)) {
                            Label(channel.name, strong = channel.key == state.channelKey)
                            current?.let { Label(it.title, small = true) }
                        }
                    }
                }
            }
        }
    }
    detail?.let { programme -> TouchSheet({ detail = null }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            SectionTitle(programme.title)
            Label(formatClock(programme.startMs, settings.use24HourClock) + " – " + formatClock(programme.stopMs, settings.use24HourClock))
            programme.description?.let { Label(it) }
        }
    } }
}
