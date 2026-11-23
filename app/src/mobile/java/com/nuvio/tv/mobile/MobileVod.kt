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
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.*
import com.nuvio.tv.ui.screens.stream.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MobileSources(video: MobileVideo, download: Boolean, fromBeginning: Boolean, back: () -> Unit, play: (String) -> Unit,
    vm: StreamScreenViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val p2p by vm.p2pEnabled.collectAsStateWithLifecycle(initialValue = false)
    val shared by vm.sharedStream.collectAsStateWithLifecycle()
    val preference by vm.playerPreference.collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Stream?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<StreamPlaybackInfo?>(null) }
    var consent by remember { mutableStateOf<StreamPlaybackInfo?>(null) }
    suspend fun use(info: StreamPlaybackInfo?, save: Boolean, replace: Boolean, override: PlayerPreference? = null, allowedTorrent: Boolean = false) {
        if (info == null) return
        val prepared = vm.attachTvdbContext(info)
        val target = video.copy(tvdb = prepared.tvdbContextJson ?: video.tvdb)
        if (save) {
            MobileDownloadService.enqueue(context, target, prepared, replace)
            vm.handoffToDownload(prepared.url)
            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
            back()
        } else {
            if (prepared.isTorrent && !p2p && !allowedTorrent) { consent = prepared; return }
            val url = prepared.url ?: prepared.infoHash?.takeIf { prepared.isTorrent }?.let { "torrent://$it" }
            require(!url.isNullOrBlank()) { "This source did not return a playable file." }
            if (prepared.isExternal) {
                val uri = android.net.Uri.parse(url)
                require(uri.scheme in setOf("https", "http")) { "This source returned an unsupported external link." }
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addCategory(android.content.Intent.CATEGORY_BROWSABLE))
                return
            }
            when (if (prepared.isServerStream) PlayerPreference.INTERNAL else override ?: preference) {
                PlayerPreference.EXTERNAL -> vm.launchExternalPlayer(prepared, url, startFromBeginning = fromBeginning, context = context)
                PlayerPreference.ASK_EVERY_TIME -> choice = prepared
                PlayerPreference.INTERNAL -> {
                    vm.onInternalPlayerLaunching(url)
                    play(target.player(url, prepared, fromBeginning = fromBeginning))
                }
                null -> message = "Player preferences are loading. Try this source again."
            }
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
    LaunchedEffect(state.autoPlayStream, state.autoPlayPlaybackInfo, preference) {
        if (preference != null && (state.autoPlayStream != null || state.autoPlayPlaybackInfo != null))
            resolve(state.autoPlayStream, state.autoPlayPlaybackInfo, true, download)
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Action("Back", onClick = back); SectionTitle(if (download) "Download" else "Sources"); Label(video.label) }
        if (busy || state.isLoading) item { Label(state.directAutoPlayMessage ?: "Finding sources…") }
        (message ?: state.playbackErrorMessage ?: state.error)?.let { item { Label(it) } }
        shared?.let { item { Action("Play party’s shared source") { resolve(it.stream, null, false, false) } } }
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
    fun continueWith(info: StreamPlaybackInfo, mode: PlayerPreference?, allow: Boolean) {
        scope.launch {
            try { use(info, false, false, mode, allow) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Could not open this player." }
        }
    }
    choice?.let { info -> TouchSheet({ choice = null }) {
        SectionTitle("Choose player")
        Action("ReSeq player") { choice = null; continueWith(info, PlayerPreference.INTERNAL, false) }
        Action("External player") { choice = null; continueWith(info, PlayerPreference.EXTERNAL, false) }
    } }
    consent?.let { info -> TouchSheet({ consent = null }) {
        SectionTitle("Peer-to-peer playback")
        Label("This source connects directly to other peers, who can see your IP address.")
        Action("Enable and play") { consent = null; vm.enableP2p(); continueWith(info, null, true) }
        Action("Cancel") { consent = null }
    } }
}

@Composable
internal fun MobileVodPlayer(back: () -> Unit, openParty: (com.nuvio.tv.core.party.PartyMedia) -> Unit,
    mixCompleted: ((Boolean, String?) -> Unit)? = null, vm: PlayerViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val timeline by vm.playbackTimeline.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var controls by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf<String?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val engine = vm.exoPlayer
    val complete by rememberUpdatedState(mixCompleted)
    var handledEnd by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(state.playbackEnded, state.error) {
        if (state.playbackEnded && state.error == null && !handledEnd) complete?.let { finished ->
            handledEnd = true
            val group = vm.controller.currentStreamBingeGroup
            vm.stopAndRelease()
            finished(state.streamAutoPlayNextEpisodeEnabled, group)
        }
    }
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
            AndroidView({ view }, Modifier.fillMaxSize(), update = { it.applyAspectMode(state.aspectMode); it.applySubtitleStyle(state.effectiveSubtitleStyle) })
            DisposableEffect(view) { vm.attachMpvView(view); onDispose { vm.attachMpvView(null) } }
        } else AndroidView(factory = { PlayerView(it).apply { useController = false; keepScreenOn = true } },
            modifier = Modifier.fillMaxSize(), update = { view ->
                view.player = engine
                view.resizeMode = state.resizeMode
                view.applySubtitleStyleIfNeeded(state.effectiveSubtitleStyle, 0f)
            }, onRelease = { it.player = null })
        MobileVideoGestures(timeline.currentPosition, timeline.duration, state.playbackSpeed, vm::onEvent) { controls = !controls }
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
            LazyRow {
                items(listOf("Audio", "Subtitles", "Speed", "Sources", "Chapters", "Episodes", "Aspect", "Watch Party")) { tool ->
                    Action(tool) {
                        when (tool) {
                            "Aspect" -> vm.onEvent(PlayerEvent.OnToggleAspectRatio)
                            "Episodes" -> vm.onEvent(PlayerEvent.OnShowEpisodesPanel)
                            "Sources" -> vm.onEvent(PlayerEvent.OnShowSourcesPanel)
                            else -> tracks = tool
                        }
                    }
                }
            }
            }
        }
        state.activeSkipInterval?.takeUnless { state.skipIntervalDismissed }?.let { skip ->
            Box(Modifier.align(Alignment.CenterEnd)) { Action("Skip " + skip.type) { vm.onEvent(PlayerEvent.OnSkipIntro) } }
        }
        if (mixCompleted == null && state.nextEpisode != null && (state.playbackEnded || state.postPlayMode != null)) {
            Column(Modifier.align(Alignment.CenterEnd).background(Color.Black)) {
                if (state.postPlayMode is PostPlayMode.StillWatching) Action("Continue watching") { vm.onEvent(PlayerEvent.OnStillWatchingContinue) }
                else Action("Next episode") { vm.onEvent(PlayerEvent.OnPlayNextEpisode) }
                Action("Back", onClick = back)
            }
        }
        state.error?.let {
            Column(Modifier.align(Alignment.Center).background(Color.Black).padding(8.dp)) {
                Label(it)
                Action("Retry") { vm.onEvent(PlayerEvent.OnRetry) }
                Action("Change source") { vm.onEvent(PlayerEvent.OnShowSourcesPanel) }
            }
        }
    }
    if (tracks == "Watch Party") TouchSheet({ tracks = null }) {
        MobileWatchParty(vm.party, vm.partyMedia, openParty) { tracks = null }
    } else tracks?.let { section -> MobilePlayerOptions(section, state, vm::onEvent) { tracks = null } }
    if (state.showSourcesPanel || state.showEpisodesPanel) MobilePlayerSources(state, vm::onEvent)
}
