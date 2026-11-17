package com.nuvio.tv.livetv.data

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Keeps Live TV and On Demand's heavy background jobs (playlist, guide and catalog downloads,
 * reading and saving them) out of the way while Nuvio's own player is on screen. On slower TV
 * boxes they competed with the video for the processor, storage and network, and movies and
 * episodes stuttered. Jobs already under way pause at their next step and carry on once the
 * player closes. Updates you start yourself (Update now) aren't held back.
 */
object BackgroundWork {
    @Volatile var livePlaybackUnstable: () -> Boolean = { false }
    @Volatile var seekQuietUntilNanos: Long = 0
    fun onLiveSeek() { seekQuietUntilNanos = System.nanoTime() + 2_000_000_000L }
    val playbackUnstable: Boolean get() = livePlaybackUnstable() ||
        System.nanoTime() < seekQuietUntilNanos || com.nuvio.tv.core.player.PlayerSessionTracker.playbackUnstable

    val liveChannelOpen = com.nuvio.tv.core.player.PlayerSessionTracker.livePlayback
    internal val liveChannelSignal = LiveChannelSignal { liveChannelOpen.value = it }

    @Volatile private var lastNavigationNanos = 0L
    private val guideNavigation = NavigationPause()

    fun onNavigation() { lastNavigationNanos = System.nanoTime() }
    fun onGuideNavigation() { guideNavigation.onNavigation(); onNavigation() }

    /** Wait outside the guide's write transaction until navigation has been idle for two seconds. */
    suspend fun awaitGuideNavigationIdle() {
        guideNavigation.awaitIdle()
    }

    /** Called between small batches on a worker, never from the UI thread. */
    fun yieldToNavigation() {
        if (System.nanoTime() - lastNavigationNanos in 0..250_000_000L) Thread.yield()
    }

    /** Full player sessions hold bulk work while Home trailers share first loads. */
    val nuvioPlaying = com.nuvio.tv.core.player.PlayerSessionTracker.isSessionOpen

    val isNuvioPlaying: Boolean
        get() = com.nuvio.tv.core.player.PlayerSessionTracker.hasOpenSession

    class PlaybackDeferred : RuntimeException("Import paused for playback")

    /** Unwind the synchronous producer at a safe checkpoint; the coroutine waits after releasing it. */
    fun importCheckpoint() {
        if (com.nuvio.tv.core.player.PlayerSessionTracker.hasBulkPlayback) throw PlaybackDeferred()
    }

    /**
     * Two low-priority threads for work nobody is waiting on: automatic playlist and guide
     * updates. Sports matching owns a separate worker. Android
     * gives the screen and the video first claim on the processor over these, so on a slower box
     * scrolling and playback stay smooth while they run (they just take a little longer).
     */
    val quiet: kotlinx.coroutines.CoroutineDispatcher by lazy {
        val count = java.util.concurrent.atomic.AtomicInteger()
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread({
                // Just short of Android's "background" class (10): that class is held to a small
                // slice of the processor whenever anything else is busy, which stretched a guide
                // update from a minute or two to ten on slower boxes. 9 still yields to the screen.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND - 1)
                r.run()
            }, "nuvio-quiet-${count.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * Reading listings out of the guide database for the channels (all of them, once the guide
     * opens; more as you scroll ahead). Big reads and lots of memory churn: at normal priority
     * they competed with the screen and made the guide stutter while they ran, so they get
     * their own low-priority threads, which the screen takes precedence over.
     */
    val reads: kotlinx.coroutines.CoroutineDispatcher by lazy {
        val count = java.util.concurrent.atomic.AtomicInteger()
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "nuvio-reads-${count.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /** Waits while Nuvio's player is open. */
    suspend fun awaitIdle() {
        if (com.nuvio.tv.core.player.PlayerSessionTracker.hasBulkPlayback)
            com.nuvio.tv.core.player.PlayerSessionTracker.bulkPlaybackActive.first { !it }
    }

    /**
     * Heavy jobs running now (building the guide, finding channels for Sports games). An automatic
     * On Demand import waits for them to finish instead of competing with them for the processor:
     * all at once, they slowed each other (and the video) right down.
     */
    val heavyJobs = MutableStateFlow(0)

    /** The guide is being built from the guide files right now. */
    val guideBuilding = MutableStateFlow(false)

    suspend fun <T> heavy(block: suspend () -> T): T {
        heavyJobs.update { it + 1 }
        try { return block() } finally { heavyJobs.update { (it - 1).coerceAtLeast(0) } }
    }

    /** Waits (up to 10 minutes) until no heavy job is running. */
    suspend fun awaitNoHeavy() {
        if (heavyJobs.value == 0) return
        kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) { heavyJobs.first { it == 0 } }
    }
}

internal class NavigationPause(private val nanoTime: () -> Long = System::nanoTime) {
    @Volatile private var lastNavigation = Long.MIN_VALUE / 2
    fun onNavigation() { lastNavigation = nanoTime() }
    suspend fun awaitIdle() {
        while (true) {
            val remaining = 2_000_000_000L - (nanoTime() - lastNavigation)
            if (remaining <= 0) return
            kotlinx.coroutines.delay((remaining / 1_000_000L).coerceAtLeast(1))
        }
    }
}
