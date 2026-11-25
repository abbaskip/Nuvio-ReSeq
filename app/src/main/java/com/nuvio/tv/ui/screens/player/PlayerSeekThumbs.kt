package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
import com.nuvio.tv.livetv.thumbs.SeekThumbs
import com.nuvio.tv.livetv.thumbs.SeekrSpriteTrack
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.seekr.previews.core.PreviewTrack
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.SeekrPreviews

/**
 * Seek thumbnails for the main player (both engines: the pictures are read from the file
 * separately from playback). Checks every couple of seconds which video is playing, so a source
 * switch or the next episode gets its own thumbnails.
 */
@Composable
internal fun PlayerSeekThumbsHook(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val settings by SeekThumbSettings.values.collectAsState()
    LaunchedEffect(context) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SeekThumbSettings.load(context) } }
    if (!settings.enabled) return
    val state by viewModel.uiState.collectAsState()
    val duration by remember(viewModel) {
        viewModel.playbackTimeline.map { if (it.isLive) 0L else it.duration.coerceAtLeast(0L) / 1000 }
            .distinctUntilChanged()
    }.collectAsState(initial = 0L)
    val controller = viewModel.controller
    val meta by controller.playbackMeta.collectAsState()
    val providerOrder = controller.navigationArgs.tvdbContextJson == "provider"
    val selected = remember(state.currentVideoId, state.currentSeason, state.currentEpisode, state.tvdbEpisodeIdentity, meta) {
        controller.currentTvdbContext()
    }
    val client = remember(settings.apiKey) {
        settings.apiKey.takeIf { it.isNotBlank() }?.let { SeekrPreviews.create(it, httpClient = seekrHttp) }
    }
    val durationMs = duration * 1000
    val source = state.currentStreamUrl
    val remote = viewModel.seekrPreview
    // Null while matching, empty for a known miss. No previous episode can supply the next one's frames.
    var windows by remember(client, meta, selected, durationMs, source, state.currentVideoId, state.currentSeason, state.currentEpisode) {
        mutableStateOf<List<SeekrWindow>?>(null)
    }
    LaunchedEffect(client, meta, selected, durationMs, source, state.currentVideoId, state.currentSeason, state.currentEpisode) {
        val catalogue = meta
        windows = if (client == null || durationMs <= 0) emptyList() else withContext(Dispatchers.Default) {
            seekrWindows(controller.contentId, state, durationMs, selected, catalogue, providerOrder,
                explicitOrder = controller.navigationArgs.tvdbContextJson != null,
                hasSelectedOrder = { id -> viewModel.previewEpisodeResolver.get().isSelected(controller.profileId, id) },
                tmdbMatches = { data, ids -> viewModel.previewEpisodeResolver.get().tmdbEpisodeTargets(data, ids) })
        }
    }
    val plan = windows
    val playingWindow by remember(viewModel, plan, durationMs) {
        viewModel.playbackTimeline.map { plan?.windowAt(it.currentPosition, durationMs) }.distinctUntilChanged()
    }.collectAsState(initial = null)
    val pendingPosition = state.pendingPreviewSeekPosition
    val window = if (pendingPosition != null) plan?.windowAt(pendingPosition, durationMs)
        else playingWindow?.takeIf { plan?.contains(it) == true }
    val tracks = remember(client, source, state.currentVideoId, durationMs) {
        mutableMapOf<Pair<SeekrContent, Long>, PreviewTrack?>()
    }
    LaunchedEffect(client, source, state.currentVideoId, durationMs, window, plan == null, settings.seekrOnly) {
        remote.reset()
        SeekThumbs.close(SeekThumbs.Owner.MAIN)
        if (plan == null) return@LaunchedEffect
        if (client == null || window == null) {
            remote.resolved(null, settings.seekrOnly)
            return@LaunchedEffect
        }
        val key = window.content to window.durationMs
        if (key !in tracks) {
            val result = try {
                client.loadTrack(window.content, window.durationMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            tracks[key] = result
        }
        val sprites = tracks[key]?.let { SeekrSpriteTrack(it, seekrHttp) }
        remote.resolved(sprites, settings.seekrOnly, window) { tracks[key] = null }
    }
    LaunchedEffect(viewModel, settings, remote.allowGeneration, state.currentStreamUrl, duration) {
        if (!remote.allowGeneration || settings.seekrOnly) {
            SeekThumbs.close(SeekThumbs.Owner.MAIN)
            return@LaunchedEffect
        }
        while (true) {
            update(context, viewModel)
            delay(2_000)
        }
    }
    DisposableEffect(viewModel) { onDispose { remote.reset(); SeekThumbs.close(SeekThumbs.Owner.MAIN) } }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun update(context: android.content.Context, viewModel: PlayerViewModel) {
    val owner = SeekThumbs.Owner.MAIN
    if (!SeekThumbSettings.values.value.enabled) { SeekThumbs.close(owner); return }
    val c = viewModel.controller
    if (c._exoPlayer?.videoFormat?.drmInitData != null) { SeekThumbs.unsupported(owner, SeekThumbs.MSG_PROTECTED); return }
    c._exoPlayer?.videoFormat.let { f -> SeekThumbs.playingFormat(owner, f?.sampleMimeType, f?.width ?: 0, f?.height ?: 0) }
    val timeline = viewModel.playbackTimeline.value
    val state = viewModel.uiState.value
    val url = viewModel.getCurrentStreamUrl()
    if (timeline.isLive) { SeekThumbs.close(owner); return }
    if (c.isTorrentStream) { SeekThumbs.unsupported(owner, SeekThumbs.MSG_TORRENT); return }
    if (url.isBlank() || timeline.duration <= 0L) { SeekThumbs.close(owner); return }
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
        SeekThumbs.unsupported(owner, "No thumbnails for this kind of link")
        return
    }
    val hls = isHls(url, c.currentStreamMimeType)
    if (!hls && isSegmented(url, c.currentStreamMimeType)) { SeekThumbs.unsupported(owner, SeekThumbs.MSG_STREAMED); return }
    if (com.nuvio.tv.core.usenet.UsenetHttpSession.isSessionUrl(url)) { SeekThumbs.close(owner); return }
    val identity = c.currentVideoId ?: c.contentId ?: ""
    val key = "$identity|$url|${timeline.duration / 1000}"
    val headers = viewModel.getCurrentHeaders()
    SeekThumbs.open(
        context = context,
        owner = owner,
        // Streamed (HLS) links are read piece by piece, from their smallest version.
        source = if (hls) SeekThumbs.Source.Hls(url, headers, "hls|$key")
            else SeekThumbs.Source.Remote(url, headers, key),
        durationMs = timeline.duration,
        videoHeight = c.currentVideoHeight ?: 0,
        hdr = state.isHdrVideo,
        playback = SeekThumbs.Playback(
            buffering = { viewModel.uiState.value.isBuffering || viewModel.uiState.value.playbackEnded || com.nuvio.tv.core.player.PlayerSessionTracker.playbackUnstable },
            isPlaying = { viewModel.uiState.value.isPlaying },
            positionMs = { viewModel.playbackTimeline.value.currentPosition },
            bufferedAheadMs = {
                val t = viewModel.playbackTimeline.value
                (t.bufferedPosition - t.currentPosition).coerceAtLeast(0L)
            },
        ),
    )
}

private val HLS_TYPES = setOf(
    "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl",
)

/** An HLS link: read piece by piece for thumbnails. */
internal fun isHls(url: String, mimeType: String?): Boolean {
    if (mimeType != null && mimeType.trim().lowercase() in HLS_TYPES) return true
    val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".m3u")
}

private val SEGMENTED_TYPES = setOf(
    "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl",
    "application/dash+xml", "application/vnd.ms-sstr+xml",
)

/** HLS, DASH or Smooth Streaming. */
internal fun isSegmented(url: String, mimeType: String?): Boolean {
    if (mimeType != null && mimeType.trim().lowercase() in SEGMENTED_TYPES) return true
    val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    return listOf(".m3u8", ".m3u", ".mpd", ".ism", ".ism/manifest").any { path.endsWith(it) }
}

private val seekrHttp by lazy {
    okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        if (!url.isHttps || url.host !in setOf("api.seekr.tv", "sprites.seekr.tv")) throw java.io.IOException("Unexpected Seekr host")
        chain.proceed(chain.request())
    }.callTimeout(2, TimeUnit.SECONDS)
        .followRedirects(false).retryOnConnectionFailure(false).build()
}

internal class SeekrPreviewState {
    private var seekrOnly = false
    private var window: SeekrWindow? = null
    private var onFailure: (() -> Unit)? = null
    var track by mutableStateOf<SeekrSpriteTrack?>(null)
        private set
    var allowGeneration by mutableStateOf(false)
        private set

    fun cueAt(positionMs: Long) = window.let { part ->
        if (part != null && (positionMs < part.startMs || positionMs >= part.endMs)) null
        else track?.cueAt(part?.positionInTrack(positionMs) ?: positionMs)
    }

    fun reset() {
        onFailure = null
        window = null
        track = null
        allowGeneration = false
    }

    fun resolved(result: SeekrSpriteTrack?, seekrOnly: Boolean, window: SeekrWindow? = null, onFailure: (() -> Unit)? = null) {
        this.window = window
        this.onFailure = onFailure
        this.seekrOnly = seekrOnly
        track = result
        allowGeneration = result == null && !seekrOnly
    }

    fun tileUnavailable(failedTrack: SeekrSpriteTrack) {
        if (failedTrack !== track) return
        onFailure?.invoke()
        resolved(null, seekrOnly)
    }
}
