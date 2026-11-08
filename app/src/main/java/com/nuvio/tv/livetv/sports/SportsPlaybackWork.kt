package com.nuvio.tv.livetv.sports

import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.livetv.player.LivePlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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

internal fun sportsCatalogueAllowed(background: Flow<Boolean>, viewers: Flow<Int>): Flow<Boolean> =
    combine(background, viewers) { idle, count -> idle || count > 0 }.distinctUntilChanged()

/** Per bulk scan. Called on the matching worker between batches, including during guide discovery. */
internal class SportsMatchPacing(
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = Thread::sleep
) {
    private var sliceStart = nanoTime()

    fun checkpoint(throttled: Boolean) {
        // Aim for 4 ms of work followed by a 16 ms break while a background scan shares a trailer.
        if (throttled && nanoTime() - sliceStart >= 4_000_000L) {
            sleep(16L)
            sliceStart = nanoTime()
        }
    }
}

/** Targeted page requests may run while browsing with a stable preview. */
internal fun sportsBrowsingAllowed(
    foreground: Flow<Boolean>,
    viewing: Flow<Int>,
    playback: Flow<LivePlaybackState>,
    fullscreen: Flow<Boolean>,
    nuvioPlaying: Flow<Boolean> = PlayerSessionTracker.isSessionOpen
): Flow<Boolean> = combine(foreground, viewing, playback, fullscreen, nuvioPlaying) { front, viewers, live, full, vod ->
    front && !vod && !full && !live.isBuffering &&
        (live.channelKey == null || viewers > 0)
}.distinctUntilChanged()

/** An opened game's local channel/EPG lookup must work even when its preview is stalled. */
internal fun sportsChannelLookupAllowed(
    foreground: Flow<Boolean>,
    viewing: Flow<Int>,
    fullscreen: Flow<Boolean>,
    nuvioPlaying: Flow<Boolean> = PlayerSessionTracker.isSessionOpen
): Flow<Boolean> = combine(foreground, viewing, fullscreen, nuvioPlaying) { front, viewers, full, vod ->
    front && viewers > 0 && !full && !vod
}.distinctUntilChanged()

/** Bulk catalogue downloads and whole-guide scans also yield to the Sports preview player. */
internal fun sportsBackgroundAllowed(
    foreground: Flow<Boolean>,
    playback: Flow<LivePlaybackState>,
    fullscreen: Flow<Boolean>,
    nuvioPlaying: Flow<Boolean> = PlayerSessionTracker.isSessionOpen
): Flow<Boolean> = combine(foreground, playback, fullscreen, nuvioPlaying) { front, live, full, vod ->
    front && !vod && !full && live.channelKey == null && !live.isBuffering
}.distinctUntilChanged()

/** Only the small watched-game score requests may continue once playback is stable. */
internal fun sportsScoresAllowed(
    foreground: Flow<Boolean>,
    playback: Flow<LivePlaybackState>,
    nuvioPlaying: Flow<Boolean> = PlayerSessionTracker.isSessionOpen,
    viewing: Flow<Int> = flowOf(0)
): Flow<Boolean> = combine(foreground, playback, nuvioPlaying, viewing) { front, live, vod, viewers ->
    front && !vod && !live.isBuffering && (live.channelKey == null || live.isPlaying || viewers > 0)
}.distinctUntilChanged()

/** Finished games need their original guide entries immediately, without a five-hour wait. */
internal fun sportsReplayLookup(event: SportsEvent, now: Long): Boolean =
    event.state == GameState.POST || event.startMs < now - 5 * 3_600_000L

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
