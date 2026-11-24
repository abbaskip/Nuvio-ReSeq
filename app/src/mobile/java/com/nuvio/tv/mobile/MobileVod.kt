package com.nuvio.tv.mobile

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.nuvio.tv.core.usenet.UsenetPlayback
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.sourceEpisode
import com.nuvio.tv.domain.model.sourceSeason
import com.nuvio.tv.domain.model.tvdbPlaybackContext
import com.nuvio.tv.ui.screens.player.*
import com.nuvio.tv.ui.screens.stream.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MobileSourceDestination(video: MobileVideo, media: MobileOfflineMedia, download: Boolean, manual: Boolean,
    fromBeginning: Boolean, back: () -> Unit, play: (String) -> Unit) {
    val revision by media.changes.collectAsStateWithLifecycle()
    var checked by remember(video.key, revision, fromBeginning) { mutableStateOf(false) }
    var local by remember(video.key, revision, fromBeginning) { mutableStateOf<String?>(null) }
    LaunchedEffect(video.key, revision, fromBeginning) {
        local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { media.localRoute(video, fromBeginning) }
        checked = true
    }
    when {
        !checked -> LinearProgressIndicator()
        local != null && !manual && !download -> LaunchedEffect(local) { local?.let(play) }
        else -> MobileSources(video, download, manual, local, fromBeginning, back, play)
    }
}

@Composable
private fun MobileSources(video: MobileVideo, download: Boolean, manual: Boolean, local: String?, fromBeginning: Boolean,
    back: () -> Unit, play: (String) -> Unit,
    vm: StreamScreenViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val cast = LocalCasting.current
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
            if (cast.state.value.device != null) {
                val torrent = prepared.infoHash?.takeIf { prepared.isTorrent && !receiverUrl(url) && !cast.isCurrentTorrent(url) }?.let {
                    CastTorrent(it, prepared.fileIdx, prepared.filename, prepared.sources.orEmpty())
                }
                require(!prepared.isExternal && (receiverUrl(url) || torrent != null || cast.isCurrentTorrent(url) || UsenetPlayback.hasSession(url))) { "Choose a network video for Chromecast, or disconnect to play this source on the phone." }
                cast.load(CastSource(url, target.label, prepared.headers.orEmpty(), target,
                    iptv = prepared.bingeGroup?.startsWith("iptv-playlist:") == true,
                    mime = PlayerMediaSourceFactory.inferMimeType(url, prepared.filename), torrent = torrent), if (fromBeginning) 0L else null, onAccepted = back)
                return
            }
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
        if (!manual && preference != null && (state.autoPlayStream != null || state.autoPlayPlaybackInfo != null))
            resolve(state.autoPlayStream, state.autoPlayPlaybackInfo, true, download)
    }
    val sourceList = rememberLazyListState()
    LaunchedEffect(state.selectedAddonFilter) { sourceList.scrollToItem(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerIcon("Back", androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, onClick = back)
            Column(Modifier.weight(1f)) {
                SectionTitle(if (download) "Download" else "Sources")
                Label(video.label, small = true)
            }
            if (!download) MobileCastButton()
            if (!video.poster.isNullOrBlank()) MediaArt(video.poster, androidx.compose.material.icons.Icons.Default.Movie,
                Modifier.size(38.dp, 54.dp))
        }
        MobileSourceTabs(state.availableAddons, state.selectedAddonFilter) { vm.onEvent(StreamScreenEvent.OnAddonFilterSelected(it)) }
        LazyColumn(Modifier.weight(1f), state = sourceList, contentPadding = PaddingValues(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (local != null && !download && state.selectedAddonFilter == null) item { Action("Play downloaded file", Modifier.fillMaxWidth()) { play(local) } }
            if (busy || state.isLoading) item { Label(state.directAutoPlayMessage ?: "Finding sources…") }
            (message ?: state.playbackErrorMessage ?: state.error)?.let { item { Label(it) } }
            shared?.takeIf { state.selectedAddonFilter == null || it.stream.addonName == state.selectedAddonFilter }?.let {
                item { Action("Play party’s shared source") { resolve(it.stream, null, false, false) } }
            }
            items(state.filteredStreams) { stream -> SourceTile(stream, !busy) { selected = stream } }
            val total = state.selectedAddonFilter?.let { name -> state.allStreams.count { it.addonName == name } } ?: state.allStreams.size
            if (state.filteredStreams.size < total) item { Action("More sources") { vm.expandFilteredStreamsIfNeeded() } }
            if (!state.isLoading && !busy && state.filteredStreams.isEmpty()) item { Action("Retry") { vm.onEvent(StreamScreenEvent.OnRetry) } }
        }
    }
    selected?.let { stream -> TouchSheet({ selected = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceText(stream)
            Action("Play this file", Modifier.fillMaxWidth()) { selected = null; resolve(stream, null, false, false) }
            DownloadAction("Download this file", Modifier.fillMaxWidth(), showLabel = true) { selected = null; resolve(stream, null, false, true) }
        }
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

private fun PlayerRuntimeController.downloadIdentity(episode: Video? = null): MobileVideo? {
    val titleId = contentId ?: return null
    val type = contentType ?: return null
    val selection = episode?.id ?: currentVideoId ?: titleId
    val tvdb = if (episode == null) currentTvdbContext() else episode.tvdbPlaybackContext()
    return MobileVideo(profileId, type, titleId, selection, contentName.orEmpty(),
        episode?.sourceSeason ?: currentSeason, episode?.sourceEpisode ?: currentEpisode,
        episode?.title ?: currentEpisodeTitle, tvdb?.encode())
}

@Composable
internal fun MobileVodPlayer(media: MobileOfflineMedia, back: () -> Unit, openParty: (com.nuvio.tv.core.party.PartyMedia) -> Unit,
    onMovieCompleted: (com.nuvio.tv.core.tracking.MovieRatingRequest) -> Unit,
    recommendation: (PostPlayRecommendation, VideoAction) -> Unit,
    mixCompleted: ((Boolean, String?) -> Unit)? = null, vm: PlayerViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val timeline by vm.playbackTimeline.collectAsStateWithLifecycle()
    val postPlay by vm.postPlayRecommendationUiState.collectAsStateWithLifecycle()
    val rateMovies by vm.promptToRateAfterWatching.collectAsStateWithLifecycle(false)
    val context = LocalContext.current
    var controls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf<String?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    LaunchedEffect(controls, lastInteraction, state.isPlaying, tracks, scrub != null, state.showSourcesPanel, state.showEpisodesPanel) {
        if (controls && state.isPlaying && tracks == null && scrub == null && !state.showSourcesPanel && !state.showEpisodesPanel) {
            kotlinx.coroutines.delay(accessibility?.calculateRecommendedTimeoutMillis(8000L, containsIcons = true, containsControls = true) ?: 8000L)
            controls = false
        }
    }
    val engine = vm.exoPlayer
    val cast = LocalCasting.current
    val castState by cast.state.collectAsStateWithLifecycle()
    val castUrl = vm.getCurrentStreamUrl().takeIf { it.isNotBlank() && !it.startsWith("torrent://") }
    val leaveAfterCast by rememberUpdatedState(back)
    LaunchedEffect(castState.device, castUrl) {
        if (castState.device != null && castUrl != null) {
            val controller = vm.controller
            val video = controller.downloadIdentity() ?: controller.cloudPlaybackContext?.let { cloud ->
                cloud.currentFile?.let { file -> MobileVideo(controller.profileId, "cloud", cloud.item.stableKey,
                    cloud.videoId(file), cloud.item.name, episodeName = file.name) }
            }
            val download = if (castUrl.startsWith("file:") && video != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                media.find(video)?.takeIf { android.net.Uri.fromFile(it).toString() == castUrl }
            } else null
            val subtitles = state.addonSubtitles.filter { receiverUrl(it.url) && it.url.substringBefore('?').endsWith(".vtt", true) }
                .mapIndexed { i, sub -> com.google.android.gms.cast.MediaTrack.Builder(i + 1L, com.google.android.gms.cast.MediaTrack.TYPE_TEXT)
                    .setContentId(sub.url).setContentType("text/vtt").setName(sub.getDisplayLanguage()).setLanguage(sub.lang).build() }
            cast.load(CastSource(castUrl, video?.label ?: state.title, controller.getCurrentHeaders(), video,
                iptv = controller.currentStreamBingeGroup?.startsWith("iptv-playlist:") == true || controller.portalPlayback != null ||
                    controller.currentAddonName == com.nuvio.tv.livetv.ondemand.OnDemandStreams.GROUP_NAME,
                download = download, mime = controller.currentStreamMimeType, subtitles = subtitles, cloudToken = controller.cloudSessionToken),
                timeline.currentPosition.takeIf { it > 0 }, autoplay = state.isPlaying || timeline.duration <= 0) {
                vm.stopAndRelease()
                leaveAfterCast()
            }
        }
    }
    val complete by rememberUpdatedState(mixCompleted)
    var handledEnd by remember(vm, state.currentVideoId) { mutableStateOf(false) }
    var reported by androidx.compose.runtime.saveable.rememberSaveable(state.currentVideoId) { mutableStateOf(false) }
    LaunchedEffect(state.playbackEnded, state.error, rateMovies, state.currentVideoId) {
        if (!reported && state.currentVideoId != null && com.nuvio.tv.core.tracking.shouldRequestMovieRating(
                rateMovies, state.contentType, state.playbackEnded, state.error != null)) {
            vm.completedMovieRatingRequest()?.let(onMovieCompleted)
            reported = true
        }
    }
    val autoNext = state.shouldAutoPlayNextEpisodeOnEnd()
    LaunchedEffect(state.playbackEnded, state.pendingExitReason, state.error, state.postPlayMode, autoNext,
        state.showEpisodesPanel, state.showSourcesPanel, postPlay.blocksNaturalCompletion) {
        if (handledEnd) return@LaunchedEffect
        val finished = complete
        when {
            state.pendingExitReason == PlayerExitReason.StillWatchingPrompt -> {
                handledEnd = true
                vm.consumePendingExitReason()
                back()
            }
            !state.playbackEnded || state.error != null -> Unit
            finished != null -> {
                handledEnd = true
                val group = vm.controller.currentStreamBingeGroup
                vm.stopAndRelease()
                finished(state.streamAutoPlayNextEpisodeEnabled, group)
            }
            postPlay.blocksNaturalCompletion || state.showEpisodesPanel || state.showSourcesPanel -> Unit
            autoNext -> vm.onEvent(PlayerEvent.OnPlayNextEpisode)
            state.nextEpisode == null && state.postPlayMode?.blocksNaturalCompletion() != true -> {
                handledEnd = true
                back()
            }
        }
    }
    DisposableEffect(vm, media) {
        val controller = vm.controller
        controller.downloadedEpisodeSource = { episode ->
            controller.downloadIdentity(episode)?.let { video ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { media.localStream(video) }
            }
        }
        vm.attachHostActivity(context as? Activity)
        vm.startInitialPlaybackIfNeeded()
        onDispose {
            controller.downloadedEpisodeSource = null
            vm.stopAndRelease()
            vm.attachHostActivity(null)
        }
    }
    LifecycleStartEffect(vm) {
        vm.resumeForLifecycle()
        onStopOrDispose { vm.pauseForLifecycle() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
            MpvPlayerSurface(vm, state.isPlaying, state.isBuffering, state.aspectMode, state.effectiveSubtitleStyle,
                subtitleLiftPx = 0f, subtitleTrackKey = state.currentVideoId, onSubtitleTextMetricsChanged = {}, modifier = Modifier.fillMaxSize())
        } else if (engine != null) {
            key(engine) {
                ExoPlayerSurface(engine, vm.controller, state.isPlaying, state.isBuffering, state.aspectMode, state.tunnelingEnabled,
                    state.tunneledSurfaceFill, state.useLibass, state.libassRenderMode, letterboxMaskEnabled = true,
                    subtitleStyle = state.effectiveSubtitleStyle, subtitleLiftPx = 0f, onBindSubtitleView = vm::bindExoSubtitleView,
                    modifier = Modifier.fillMaxSize())
            }
        }
        MobileVideoGestures(timeline.currentPosition, timeline.duration, state.playbackSpeed, vm::onEvent) { controls = !controls }
        if (state.isBuffering && state.error == null) androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (controls) {
            MobileTransport(state, timeline, scrub, { scrub = it }, {
                scrub?.let { vm.onEvent(PlayerEvent.OnSeekTo((it * timeline.duration).toLong())) }; scrub = null
            }, downloaded = vm.getCurrentStreamUrl().startsWith("file:") && (state.isPlaying || timeline.duration > 0),
                back = back, event = { lastInteraction++; vm.onEvent(it) }, tool = { tracks = it })
        }
        state.activeSkipInterval?.takeUnless { state.skipIntervalDismissed }?.let { skip ->
            Box(Modifier.align(Alignment.CenterEnd)) { Action("Skip " + skip.type) { vm.onEvent(PlayerEvent.OnSkipIntro) } }
        }
        if (mixCompleted == null && !postPlay.isVisible && (state.shouldConfirmNextEpisodeOnEnd() || state.postPlayMode != null)) {
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
        MobileWatchParty(vm.party, vm.partyMedia, openParty, vm.currentPartyStream() != null,
            { vm.switchParty() }, vm::catchUpParty) { tracks = null }
    } else tracks?.let { section -> MobilePlayerOptions(section, state, vm::onEvent) { tracks = null } }
    if (state.showSourcesPanel || state.showEpisodesPanel) {
        val episode = if (state.showEpisodeStreams) state.episodesAll.singleOrNull {
            it.id == state.episodeStreamsForVideoId && it.season == state.episodeStreamsSeason &&
                it.episode == state.episodeStreamsEpisode && it.title == state.episodeStreamsTitle
        } else null
        val video = when {
            state.showSourcesPanel -> vm.controller.downloadIdentity()
            episode != null -> vm.controller.downloadIdentity(episode)
            else -> null
        }
        val revision by media.changes.collectAsStateWithLifecycle()
        key(video, revision) {
            val local by produceState<Stream?>(null) {
                if (video != null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { media.localStream(video) }
            }
            MobilePlayerSources(state, local, vm::onEvent, vm.controller::expandSourceFilteredStreamsIfNeeded)
        }
    }
    if (mixCompleted == null && postPlay.isVisible) MobileRecommendations(postPlay, vm, back, recommendation)
}
