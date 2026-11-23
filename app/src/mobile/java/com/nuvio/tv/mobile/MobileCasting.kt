package com.nuvio.tv.mobile

import android.content.Context
import android.os.SystemClock
import androidx.annotation.Keep
import androidx.compose.runtime.staticCompositionLocalOf
import com.google.android.gms.cast.*
import com.google.android.gms.cast.framework.*
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Keep
class MobileCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

internal val LocalCasting = staticCompositionLocalOf<MobileCasting> { error("Missing Cast sender") }

internal fun receiverUrl(url: String?): Boolean {
    val host = url?.toHttpUrlOrNull()?.host?.lowercase()?.trimEnd('.') ?: return false
    return host != "localhost" && !host.endsWith(".localhost") && !host.startsWith("127.") &&
        host !in setOf("::1", "::", "0.0.0.0", "10.0.2.2")
}

internal data class CastSource(val url: String, val title: String, val headers: Map<String,String> = emptyMap(),
    val video: MobileVideo? = null, val live: Boolean = false, val iptv: Boolean = false, val mime: String? = null,
    val subtitles: List<MediaTrack> = emptyList(), val cloudToken: String? = null, val torrent: CastTorrent? = null)

internal data class CastTorrent(val hash: String, val fileIndex: Int?, val filename: String?, val trackers: List<String>)

@Keep internal data class CastItem(val token: String, val profile: Int, val title: String, val video: MobileVideo?,
    val live: Boolean, val iptv: Boolean, val cloudToken: String? = null)

internal data class CastControls(val device: String? = null, val item: CastItem? = null, val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val tracks: List<MediaTrack> = emptyList(),
    val selected: Set<Long> = emptySet(), val error: String? = null, val loading: Boolean = false, val speed: Double = 1.0)

@Singleton
internal class MobileCasting @Inject constructor(@ApplicationContext private val context: Context,
    private val tracker: MobileCastTracking, private val profiles: ProfileManager, private val torrents: TorrentService) : SessionManagerListener<CastSession> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("cast_session", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val controls = MutableStateFlow(CastControls())
    val state = controls.asStateFlow()
    val available = MutableStateFlow(false)
    val requestedSources = MutableStateFlow<MobileVideo?>(null)
    private var manager: SessionManager? = null
    private var initializing = false
    private var remote: RemoteMediaClient? = null
    private var pending: CastItem? = null
    private var relay: CastRelay? = null
    private var pendingRelay: CastRelay? = null
    private var loading: Job? = null
    private var accepted: (() -> Unit)? = null
    private val observer = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = update()
        override fun onMetadataUpdated() = update()
    }
    private val tick = RemoteMediaClient.ProgressListener { _, _ -> update() }

    init {
        scope.launch {
            profiles.activeProfileId.collect { profile ->
                if (listOfNotNull(controls.value.item, pending).any { it.profile != profile }) disconnect()
            }
        }
    }

    fun initialize() {
        if (manager != null || initializing || GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) return
        initializing = true
        val initialization = runCatching {
            CastContext.getSharedInstance(context, Executor { Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, it) })
        }.getOrElse { initializing = false; return }
        initialization
            .addOnSuccessListener { cast ->
                initializing = false
                manager = cast.sessionManager
                manager?.addSessionManagerListener(this, CastSession::class.java)
                available.value = true
                manager?.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
            }.addOnFailureListener { initializing = false }
    }

    private fun attach(session: CastSession) {
        detachClient()
        remote = session.remoteMediaClient
        remote?.registerCallback(observer)
        remote?.addProgressListener(tick, 1_000)
        controls.update { it.copy(device = session.castDevice?.friendlyName ?: "Chromecast", error = null) }
        update()
    }

    private fun detachClient() {
        remote?.unregisterCallback(observer)
        remote?.removeProgressListener(tick)
        remote = null
    }

    fun load(source: CastSource, position: Long? = null, autoplay: Boolean = true, onAccepted: () -> Unit = {}) {
        val client = remote ?: return
        if (!receiverUrl(source.url) && source.torrent == null && !isCurrentTorrent(source.url)) { fail("Choose a network stream to cast this title."); return }
        cancelPending()
        val item = CastItem(UUID.randomUUID().toString(), profiles.activeProfileId.value, source.title, source.video, source.live, source.iptv, source.cloudToken)
        pending = item
        accepted = onAccepted
        controls.update { it.copy(loading = true, error = null) }
        loading = scope.launch {
            try {
                val previous = controls.value
                val start = when {
                    source.live -> 0L
                    position != null -> position
                    item.video != null && item.video.key == previous.item?.video?.key -> previous.position
                    item.video != null && !item.iptv -> withContext(Dispatchers.IO) { tracker.resume(item.video, item.cloudToken) }
                    else -> 0L
                }
                if (pending != item) return@launch
                val torrent = source.torrent
                val url = if (torrent == null) source.url else torrents.startStream(torrent.hash, torrent.fileIndex, torrent.filename, torrent.trackers)
                val lease = if (torrent != null || !receiverUrl(url) && isCurrentTorrent(url)) requireNotNull(torrents.retainStream(url)) else null
                if (torrent != null) torrents.shutdown()
                val endpoint = CastRelay.prepare(context, source.copy(url = url), manager?.currentCastSession?.castDevice?.inetAddress, lease)
                pendingRelay = endpoint.relay
                val metadata = MediaMetadata(if (item.video?.episode != null) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_GENERIC)
                metadata.putString(MediaMetadata.KEY_TITLE, item.title)
                val info = MediaInfo.Builder(endpoint.url).setContentType(endpoint.mime)
                    .setStreamType(if (source.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                    .setMetadata(metadata).setCustomData(JSONObject().put("reseqCastId", item.token))
                    .setMediaTracks(source.subtitles).build()
                val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(autoplay)
                if (!source.live) request.setCurrentTime(start.coerceAtLeast(0))
                client.load(request.build()).setResultCallback { result ->
                    if (pending == item) {
                        if (result.status.isSuccess) update()
                        else fail("Chromecast couldn't play this source. Choose another source.")
                    }
                }
                delay(30_000)
                if (pending == item) fail("Chromecast didn't start this source. Check the receiver or choose another source.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (pending == item) fail("Couldn't prepare this source for Chromecast.") }
        }
    }

    private fun update() {
        val client = remote ?: return
        val status = client.mediaStatus ?: return
        val id = status.mediaInfo?.customData?.optString("reseqCastId")?.takeIf { it.isNotBlank() }
        val idle = status.playerState == MediaStatus.PLAYER_STATE_IDLE
        if (id == pending?.token && pending != null && !idle && status.playerState != MediaStatus.PLAYER_STATE_UNKNOWN) {
            val item = requireNotNull(pending)
            val old = controls.value.item
            val sameVideo = item.video != null && old != null && item.video.key == old.video?.key && item.iptv == old.iptv
            if (sameVideo) relay?.close() else finish(false)
            relay = pendingRelay
            pendingRelay = null
            pending = null
            prefs.edit().putString("item", gson.toJson(item)).apply()
            controls.update { it.copy(item = item, loading = false, error = null) }
            val callback = accepted
            accepted = null
            callback?.invoke()
        }
        if (pending != null && id == pending?.token && idle && status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
            fail("The receiver couldn't decode or open this source."); return
        }
        if (controls.value.item == null && id != null) {
            val saved = runCatching { gson.fromJson(prefs.getString("item", null), CastItem::class.java) }.getOrNull()
            if (saved?.token == id && saved.profile == profiles.activeProfileId.value) controls.update { it.copy(item = saved) }
        }
        val item = controls.value.item ?: return
        if (id != item.token) {
            if (pending == null && idle && id == null) finish(status.idleReason == MediaStatus.IDLE_REASON_FINISHED)
            else if (pending == null && (id != null || !idle && status.mediaInfo != null)) finish(false)
            return
        }
        if (idle) {
            if (pending != null && status.idleReason == MediaStatus.IDLE_REASON_INTERRUPTED) return
            finish(status.idleReason == MediaStatus.IDLE_REASON_FINISHED)
            if (status.idleReason == MediaStatus.IDLE_REASON_ERROR) fail("Chromecast stopped playing this source.")
            return
        }
        val playing = client.isPlaying
        val duration = client.streamDuration.coerceAtLeast(0)
        val position = client.approximateStreamPosition.coerceAtLeast(0)
        controls.update { it.copy(playing = playing, position = position, duration = duration,
            tracks = status.mediaInfo?.mediaTracks.orEmpty(), selected = status.activeTrackIds?.toSet().orEmpty(), speed = status.playbackRate) }
        if (!item.iptv && item.video != null && (playing || client.isPaused))
            tracker.observe(CastSample(item.video, position, duration, playing, SystemClock.elapsedRealtime(), item.cloudToken))
    }

    private fun finish(completed: Boolean) {
        tracker.finish(completed)
        relay?.close()
        relay = null
        prefs.edit().remove("item").apply()
        controls.update { CastControls(device = it.device, loading = pending != null, error = it.error) }
    }
    private fun cancelPending() {
        loading?.cancel()
        loading = null
        pendingRelay?.close()
        pendingRelay = null
        pending = null
        accepted = null
    }
    private fun fail(text: String) {
        cancelPending()
        controls.update { it.copy(loading = false, error = text) }
    }
    fun dismissError() { controls.update { it.copy(error = null) } }
    fun isCurrentTorrent(url: String?) = url != null && (torrents.state.value as? TorrentState.Streaming)?.localUrl == url
    fun toggle() { remote?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seek(ms: Long) {
        if (controls.value.item?.live == false) remote?.seek(MediaSeekOptions.Builder().setPosition(ms.coerceIn(0L, controls.value.duration)).build())
    }
    fun speed(rate: Double) { if (controls.value.item?.live == false) remote?.setPlaybackRate(rate) }
    fun track(type: Int, id: Long?) {
        val state = controls.value
        if (state.item == null) return
        val keep = state.selected - state.tracks.filter { it.type == type }.map { it.id }.toSet()
        remote?.setActiveMediaTracks((keep + listOfNotNull(id)).toLongArray())?.setResultCallback {
            if (!it.status.isSuccess) controls.update { old -> old.copy(error = "The receiver couldn't change tracks.") }
        }
    }
    fun disconnect() { manager?.endCurrentSession(true) }
    override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
    override fun onSessionEnding(session: CastSession) { update() }
    override fun onSessionEnded(session: CastSession, error: Int) {
        detachClient(); cancelPending(); finish(false); controls.value = CastControls()
    }
    override fun onSessionSuspended(session: CastSession, reason: Int) {
        val state = controls.value
        state.item?.takeUnless { it.iptv }?.video?.let {
            tracker.observe(CastSample(it, state.position, state.duration, false, SystemClock.elapsedRealtime(), state.item.cloudToken))
        }
        detachClient()
        controls.update { it.copy(playing = false) }
    }
    override fun onSessionStarting(session: CastSession) = Unit
    override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
    override fun onSessionStartFailed(session: CastSession, error: Int) = fail("Couldn't connect to Chromecast.")
    override fun onSessionResumeFailed(session: CastSession, error: Int) = onSessionEnded(session, error)
}
