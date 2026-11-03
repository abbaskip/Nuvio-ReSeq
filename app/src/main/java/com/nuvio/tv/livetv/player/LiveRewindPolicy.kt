package com.nuvio.tv.livetv.player

import androidx.media3.common.Player

internal const val REWIND_SEGMENT_SECONDS = 2.0
internal const val REWIND_TARGET_SECONDS = 3
internal const val REWIND_STARTUP_MS = 6_000L

/** A playable lead must exist before starting the rolling local playlist. */
internal fun rewindPlaylistDurationMs(playlist: String): Long = playlist.lineSequence()
    .filter { it.startsWith("#EXTINF:") }
    .sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull()?.takeIf { seconds -> seconds.isFinite() && seconds > 0 } ?: 0.0 }
    .times(1_000).toLong()

/** Only a running video renderer can establish that a newly attached surface is stuck. */
internal fun shouldRecoverLiveSurface(
    awaitingFrame: Boolean,
    playbackState: Int,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    suppressionReason: Int,
    hasVideo: Boolean
): Boolean = awaitingFrame && playbackState == Player.STATE_READY && isPlaying && playWhenReady &&
    suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && hasVideo
