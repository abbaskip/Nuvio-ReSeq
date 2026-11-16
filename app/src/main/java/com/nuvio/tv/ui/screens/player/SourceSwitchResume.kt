package com.nuvio.tv.ui.screens.player

/** Capture once before resolution or release, and carry it through every source preparation. */
internal fun sourceSwitchResumePosition(
    live: Boolean,
    rendered: Boolean,
    current: Long?,
    timeline: Long?,
    queued: Long?,
    saved: Long?
): Long? {
    if (live) return null
    // Zero is a real position once playback has started, including after an explicit restart.
    if (rendered) return (current?.takeUnless { it == 0L && (timeline ?: 0L) > 0L } ?: timeline)?.coerceAtLeast(0L)
    return listOf(queued, saved, current, timeline).firstOrNull { it != null && it > 0L }
}
