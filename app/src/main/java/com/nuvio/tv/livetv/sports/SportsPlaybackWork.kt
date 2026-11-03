package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.player.LivePlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.nuvio.tv.core.network.consumeCancellableResponse
import okhttp3.Call
import okhttp3.Response

/** Targeted page requests may run while browsing with a stable preview. */
internal fun sportsBrowsingAllowed(
    foreground: Flow<Boolean>,
    viewing: Flow<Int>,
    playback: Flow<LivePlaybackState>,
    fullscreen: Flow<Boolean>,
    nuvioPlaying: Flow<Boolean>
): Flow<Boolean> = combine(foreground, viewing, playback, fullscreen, nuvioPlaying) { front, viewers, live, full, vod ->
    front && !vod && !full && !live.isBuffering &&
        (live.channelKey == null || (viewers > 0 && (live.isPlaying || live.userPaused)))
}.distinctUntilChanged()

/** Bulk catalogue downloads and whole-guide scans also yield to the Sports preview player. */
internal fun sportsBackgroundAllowed(
    foreground: Flow<Boolean>,
    playback: Flow<LivePlaybackState>,
    fullscreen: Flow<Boolean>,
    nuvioPlaying: Flow<Boolean>
): Flow<Boolean> = combine(foreground, playback, fullscreen, nuvioPlaying) { front, live, full, vod ->
    front && !vod && !full && live.channelKey == null && !live.isBuffering
}.distinctUntilChanged()

/** Only the small watched-game score requests may continue once playback is stable. */
internal fun sportsScoresAllowed(
    foreground: Flow<Boolean>,
    playback: Flow<LivePlaybackState>,
    nuvioPlaying: Flow<Boolean>
): Flow<Boolean> = combine(foreground, playback, nuvioPlaying) { front, live, vod ->
    front && !vod && (live.channelKey == null || (live.isPlaying && !live.isBuffering))
}.distinctUntilChanged()

/** One page request or refinement, with no observer left behind after it finishes. */
internal suspend fun StateFlow<Boolean>.whileAllowed(block: suspend () -> Unit) = coroutineScope {
    if (!value) return@coroutineScope
    val work = async { block() }
    val guard = launch(start = CoroutineStart.UNDISPATCHED) {
        first { !it }
        work.cancel()
    }
    try { work.await() } finally { guard.cancel() }
}

/** Cache fallback and retry paths must never turn cancellation into another request. */
internal suspend inline fun <T> sportsCatching(block: () -> T): Result<T> {
    currentCoroutineContext().ensureActive()
    return try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
    }
}

/** Keep cancellation connected to the socket until the response body has been consumed. */
internal suspend fun <T> Call.consumeSportsResponse(consume: (Response) -> T): T =
    consumeCancellableResponse(consume)
