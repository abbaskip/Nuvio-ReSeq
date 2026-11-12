package com.nuvio.tv.livetv.data

/** Decisions are sampled only by an active guide build; no background observer or worker. */
internal object GuidePlaybackBudget {
    const val FINISH_AFTER_MS = 30 * 60_000L
    fun message(playing: Boolean, unstable: Boolean, heapFraction: Double, ageMs: Long): String? = when {
        heapFraction > if (playing) 0.75 else 0.85 -> "Guide update waiting for memory"
        playing && unstable -> "Guide update paused · buffering"
        ageMs >= FINISH_AFTER_MS -> "Finishing guide update"
        playing -> "Guide updating · slowed during playback"
        else -> null
    }
    fun paused(message: String?) = message == "Guide update waiting for memory" || message == "Guide update paused · buffering"
}
