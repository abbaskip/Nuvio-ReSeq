package com.nuvio.tv.mobile

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.*
import com.nuvio.tv.ui.screens.stream.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MobileSources(video: MobileVideo, download: Boolean, back: () -> Unit, play: (String) -> Unit,
    vm: StreamScreenViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val p2p by vm.p2pEnabled.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Stream?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun use(info: StreamPlaybackInfo?, save: Boolean, replace: Boolean) {
        if (info == null) return
        val prepared = vm.attachTvdbContext(info)
        val target = video.copy(tvdb = prepared.tvdbContextJson ?: video.tvdb)
        if (save) {
            MobileDownloadService.enqueue(context, target, prepared, replace)
            vm.handoffToDownload(prepared.url)
            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
            back()
        } else {
            require(!prepared.isExternal) { "This source requires an external player." }
            require(!prepared.isTorrent || p2p) { "Choose a file source or enable peer-to-peer playback in ReSeq settings." }
            val url = prepared.url ?: prepared.infoHash?.takeIf { prepared.isTorrent }?.let { "torrent://$it" }
            require(!url.isNullOrBlank()) { "This source did not return a playable file." }
            vm.onInternalPlayerLaunching(url)
            play(target.player(url, prepared))
        }
    }
    fun resolve(stream: Stream?, cached: StreamPlaybackInfo?, automatic: Boolean, save: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val info = cached ?: stream?.let {
                    if (automatic) vm.resolveAutoPlayStreamForPlayback(it) else vm.resolveStreamForPlayback(it, saveLastLink = false)
                }
                use(info, save, replace = !automatic)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message?.takeIf { e is IllegalArgumentException } ?: "Could not open this source." }
            finally {
                if (automatic) vm.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                busy = false
            }
        }
    }
    LaunchedEffect(state.autoPlayStream, state.autoPlayPlaybackInfo) {
        if (state.autoPlayStream != null || state.autoPlayPlaybackInfo != null)
            resolve(state.autoPlayStream, state.autoPlayPlaybackInfo, true, download)
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Action("Back", onClick = back); SectionTitle(if (download) "Download" else "Sources"); Label(video.label) }
        if (busy || state.isLoading) item { Label(state.directAutoPlayMessage ?: "Finding sources…") }
        (message ?: state.playbackErrorMessage ?: state.error)?.let { item { Label(it) } }
        items(state.filteredStreams) { stream ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .combinedClickable(enabled = !busy, onClick = { selected = stream }, onLongClick = { selected = stream })
                .heightIn(min = 56.dp).padding(8.dp)) {
                Label(stream.getDisplayName(), strong = true)
                Label(stream.description ?: stream.title ?: stream.addonName, small = true, lines = 3)
            }
        }
        if (state.filteredStreams.size < state.allStreams.size) item { Action("More sources") { vm.expandFilteredStreamsIfNeeded() } }
        if (!state.isLoading && !busy && state.filteredStreams.isEmpty()) item { Action("Retry") { vm.onEvent(StreamScreenEvent.OnRetry) } }
    }
    selected?.let { stream -> TouchSheet({ selected = null }) {
        SectionTitle(stream.getDisplayName())
        Action("Play this file", Modifier.fillMaxWidth()) { selected = null; resolve(stream, null, false, false) }
        Action("Download this file", Modifier.fillMaxWidth()) { selected = null; resolve(stream, null, false, true) }
    } }
}

@Composable
internal fun MobileVodPlayer(back: () -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val timeline by vm.playbackTimeline.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var controls by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf<String?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val engine = vm.exoPlayer
    DisposableEffect(vm) {
        vm.attachHostActivity(context as? Activity)
        vm.startInitialPlaybackIfNeeded()
        onDispose { vm.stopAndRelease(); vm.attachHostActivity(null) }
    }
    LifecycleStartEffect(vm) {
        vm.resumeForLifecycle()
        onStopOrDispose { vm.pauseForLifecycle() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
            val view = remember { NuvioMpvSurfaceView(context) }
            AndroidView({ view }, Modifier.fillMaxSize())
            DisposableEffect(view) { vm.attachMpvView(view); onDispose { vm.attachMpvView(null) } }
        } else AndroidView(factory = { PlayerView(it).apply { useController = false; keepScreenOn = true } },
            modifier = Modifier.fillMaxSize(), update = { view -> view.player = engine }, onRelease = { it.player = null })
        Box(Modifier.fillMaxSize().clickable { controls = !controls })
        if (controls) {
            Row(Modifier.align(Alignment.TopStart)) {
                Action("Back", onClick = back)
                if (vm.getCurrentStreamUrl().startsWith("file:") && (state.isPlaying || timeline.duration > 0))
                    Label("Playing download", Modifier.background(Color.Black).padding(8.dp))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .8f)).padding(horizontal = 8.dp)) {
            if (timeline.duration > 0) Slider(scrub ?: (timeline.currentPosition.toFloat() / timeline.duration).coerceIn(0f, 1f),
                { scrub = it }, onValueChangeFinished = {
                    scrub?.let { vm.onEvent(PlayerEvent.OnSeekTo((it * timeline.duration).toLong())) }; scrub = null
                })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Action("−10 s") { vm.onEvent(PlayerEvent.OnSeekBy(-10_000)) }
                Action(if (state.isPlaying) "Pause" else "Play") { vm.onEvent(PlayerEvent.OnPlayPause) }
                Action("+10 s") { vm.onEvent(PlayerEvent.OnSeekBy(10_000)) }
                Label("${timeline.currentPosition / 60000}:${(timeline.currentPosition / 1000 % 60).toString().padStart(2, '0')}")
            }
            Row { Action("Audio") { tracks = "Audio" }; Action("Subtitles") { tracks = "Subtitles" } }
            }
        }
        state.error?.let { Label(it, Modifier.align(Alignment.Center).background(Color.Black).padding(8.dp)) }
    }
    tracks?.let { section -> TouchSheet({ tracks = null }) {
        SectionTitle(section)
        LazyColumn {
            if (section == "Subtitles") item { Action("Off") { vm.onEvent(PlayerEvent.OnDisableSubtitles); tracks = null } }
            items(if (section == "Audio") state.audioTracks else state.subtitleTracks) { track ->
                Action(track.name, selected = track.isSelected) {
                    vm.onEvent(if (section == "Audio") PlayerEvent.OnSelectAudioTrack(track.index) else PlayerEvent.OnSelectSubtitleTrack(track.index))
                    tracks = null
                }
            }
        }
    } }
}
