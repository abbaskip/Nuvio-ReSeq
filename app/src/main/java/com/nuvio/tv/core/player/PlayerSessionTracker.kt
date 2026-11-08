package com.nuvio.tv.core.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the in-app player is open, from controller creation until the player exits.
 * Unlike TvRecommendationManager.isPlaybackActive it stays set while paused, buffering or seeking,
 * so work hidden behind the player can wait for it to close instead of competing with playback.
 * Sessions are counted because the next player can open before the previous one is cleared.
 */
object PlayerSessionTracker {
    private data class Counts(val players: Int = 0, val trailers: Int = 0) {
        val hasPlayback: Boolean get() = players > 0 || trailers > 0
    }

    private val openSessions = MutableStateFlow(Counts())

    /** Synchronous worker checkpoints must not wait for a UI collector to observe the session. */
    val hasOpenSession: Boolean get() = openSessions.value.players > 0

    val isSessionOpen: Flow<Boolean> = openSessions.map { it.players > 0 }.distinctUntilChanged()

    // Background work yields to previews too. Watch-progress refresh still follows full players.
    val hasPlayback: Boolean get() = openSessions.value.hasPlayback
    val playbackActive: Flow<Boolean> = openSessions.map { it.hasPlayback }.distinctUntilChanged()

    fun open(): Session {
        openSessions.update { it.copy(players = it.players + 1) }
        return Session { openSessions.update { it.copy(players = it.players - 1) } }
    }

    fun openTrailer(): Session {
        openSessions.update { it.copy(trailers = it.trailers + 1) }
        return Session { openSessions.update { it.copy(trailers = it.trailers - 1) } }
    }

    class Session internal constructor(private val onClose: () -> Unit) {
        private val closed = AtomicBoolean(false)

        /** Safe to call more than once; only the first call closes the session. */
        fun close() {
            if (closed.compareAndSet(false, true)) {
                onClose()
            }
        }
    }
}
