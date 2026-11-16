package com.nuvio.tv.ui.screens.player

/** Respect the thermal API's sampling interval and retry transient NaNs at a bounded rate. */
internal class ThermalHeadroomPoller {
    private var nextSampleMs = 0L
    private var misses = 0
    private var last = Float.NaN

    fun sample(nowMs: Long, read: () -> Float): Float {
        if (nowMs < nextSampleMs) return last
        last = runCatching(read).getOrDefault(Float.NaN)
        misses = if (last.isNaN()) (misses + 1).coerceAtMost(3) else 0
        val retryMs = (10_000L shl misses).coerceAtMost(60_000L)
        nextSampleMs = nowMs + retryMs
        return last
    }
}
