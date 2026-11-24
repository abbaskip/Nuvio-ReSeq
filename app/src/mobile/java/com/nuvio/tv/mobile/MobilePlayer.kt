// Channel switching and panel placement adapted from NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.livetv.ui.PauseLiveTvInBackground
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.player.LiveTvTimeshift
import androidx.media3.common.MimeTypes
import com.nuvio.tv.livetv.ui.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

internal fun LiveChannel.toCastSource(streamUrl: String, replayTitle: String?, mime: String?) =
    CastSource(streamUrl, replayTitle ?: name, headers, live = replayTitle == null, iptv = true,
        mime = mime, fallbackUserAgent = LiveTvRepository.DEFAULT_UA)

@Composable
internal fun MobilePlayer(vm: LiveTvViewModel, onClose: () -> Unit) {
    val state by vm.playbackState.collectAsStateWithLifecycle()
    val cast = LocalCasting.current
    val castState by cast.state.collectAsStateWithLifecycle()
    val channel = vm.playback.playingChannel
    val streamUrl = vm.playback.playingUrl
    val leaveAfterCast by rememberUpdatedState(onClose)
    LaunchedEffect(castState.error) {
        if (castState.error != null && channel != null && !state.isPlaying) vm.playback.retry()
    }
    LaunchedEffect(castState.device, state.channelKey, streamUrl) {
        if (castState.device != null && channel != null && channel.drm == null && streamUrl != null && receiverUrl(streamUrl)) {
            val replay = state.catchupTitle
            val position = if (replay != null) vm.playback.playerFlow.value?.currentPosition else 0L
            val player = vm.playback.playerFlow.value
            val upstreamItem = player?.currentMediaItem?.localConfiguration?.takeIf { it.uri.toString() == streamUrl }
            val mime = when (vm.playback.upstreamBufferKind) {
                LiveTvTimeshift.Kind.TS -> MimeTypes.VIDEO_MP2T
                LiveTvTimeshift.Kind.HLS -> MimeTypes.APPLICATION_M3U8
                null -> upstreamItem?.mimeType ?: com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory.inferMimeType(streamUrl, null)
                    ?: player?.videoFormat?.containerMimeType?.takeIf { upstreamItem != null && player.currentManifest == null }
            }
            vm.playback.handOverForRecording()
            cast.load(channel.toCastSource(streamUrl, replay, mime), position) {
                leaveAfterCast()
            }
        }
    }
    val player by vm.playback.playerFlow.collectAsStateWithLifecycle()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(false) }
    var groupsOpen by rememberSaveable { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var guideMode by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var detail by remember { mutableStateOf<EpgProgram?>(null) }
    var controls by rememberSaveable { mutableStateOf(true) }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(controls, panel, tools, state.isPlaying, state.channelKey) {
        if (controls && !panel && !tools && state.isPlaying) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(6000, containsControls = true) ?: 6000)
            controls = false
        }
    }
    com.nuvio.tv.livetv.ui.KeepScreenAwake(true)
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
                PlayerIcon("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = onClose)
                Label(vm.playback.playingChannel?.name.orEmpty(), Modifier.background(Color.Black.copy(alpha = .7f)).padding(6.dp))
                if (channel?.drm == null && receiverUrl(streamUrl)) MobileCastButton()
            }
            Row(Modifier.align(Alignment.BottomEnd)) {
                PlayerIcon(if (state.userPaused) "Resume" else "Pause", if (state.userPaused) Icons.Default.PlayArrow else Icons.Default.Pause) { vm.playback.togglePause() }
                PlayerIcon("Options", Icons.Default.Tune) { tools = true }
                PlayerIcon("Channels", Icons.AutoMirrored.Filled.ViewList) { panel = true }
            }
        }
        state.error?.let { Label(it, Modifier.align(Alignment.Center).background(Color.Black).padding(8.dp)) }
        if (panel) {
            val tablet = minOf(maxWidth, maxHeight) >= 600.dp
            val sideways = maxWidth > maxHeight && !tablet
            val guide = guideMode ?: tablet
            val bounds = if (sideways) Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(if (guide) .84f else .50f)
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (guide) .60f else .48f)
            Column(bounds.background(Color.Black.copy(alpha = .58f))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { guideMode = false }) {
                        Icon(Icons.AutoMirrored.Filled.ViewList, "List", tint = if (!guide) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    IconButton(onClick = { guideMode = true }) {
                        Icon(Icons.Default.ViewWeek, "Guide", tint = if (guide) MaterialTheme.colorScheme.primary else Color.White)
                    }
                    val group = ui.groups.firstOrNull { it.id == ui.selectedGroupId }?.title ?: "All channels"
                    Row(Modifier.weight(1f).clickable { groupsOpen = true }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.FilterList, null, Modifier.size(18.dp), tint = Color.White)
                        Label("Group: $group", Modifier.padding(horizontal = 4.dp), small = true, lines = 1)
                    }
                    IconButton(onClick = { panel = false }) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
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
                    8, settings.use24HourClock, list, rememberScrollState(), switch, onProgram = { channel, programme ->
                    if (programme.isLive(now)) switch(channel) else detail = programme
                }, playingKey = state.channelKey, overlay = true)
                else LazyColumn(state = list) {
                    items(ui.channels, key = { it.key }) { channel ->
                        val current = programs[channel.key]?.firstOrNull { it.isLive(now) }
                        Row(Modifier.fillMaxWidth().background(if (channel.key == state.channelKey) guideHighlight(true, true) else Color.Transparent)
                            .clickable { switch(channel) }.heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChannelIdentity(channel, Modifier.width(104.dp))
                            current?.let { Label(it.title, Modifier.weight(1f).padding(horizontal = 6.dp), small = true, lines = 2) }
                        }
                    }
                }
            }
        }
    }
    if (groupsOpen) TouchSheet({ groupsOpen = false }) {
        BrowseHeading("Channel groups") { groupsOpen = false }
        LazyColumn {
            items(ui.groups.filterNot { it.locked || it.id == com.nuvio.tv.livetv.model.ChannelGroup.SEARCH }, key = { it.id }) { group ->
                Action(group.title, Modifier.fillMaxWidth(), selected = ui.selectedGroupId == group.id) {
                    vm.selectGroup(group.id); groupsOpen = false
                }
            }
        }
    }
    if (tools) TouchSheet({ tools = false }) {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item { BrowseHeading(state.catchupTitle ?: "Live player", { tools = false }) }
            if (state.catchupTitle != null || vm.playback.shiftCanSeek()) item {
                Row {
                    PlayerIcon("Rewind 30 seconds", Icons.Default.Replay30) { vm.playback.seekBy(-30_000) }
                    PlayerIcon("Forward 30 seconds", Icons.Default.Forward30) { vm.playback.seekBy(30_000) }
                    vm.playback.playingChannel?.let { channel -> Action("Live") { vm.preview(channel); tools = false } }
                }
            }
            item { SectionTitle("Audio") }
            items(vm.playback.audioTracks()) { track -> Action(track.label, selected = track.selected) { vm.playback.pickAudio(track); tools = false } }
            item { SectionTitle("Subtitles"); Action("Off", selected = !vm.playback.captionsOn()) { vm.playback.pickSubtitle(null); tools = false } }
            items(vm.playback.subtitleTracks() + vm.playback.moreCaptionTracks()) { track ->
                Action(track.label, selected = track.selected) { vm.playback.pickSubtitle(track); tools = false }
            }
            item { Action("Reconnect") { vm.playback.retry(); tools = false } }
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
