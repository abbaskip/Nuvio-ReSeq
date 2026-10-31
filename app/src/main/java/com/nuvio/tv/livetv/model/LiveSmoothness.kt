package com.nuvio.tv.livetv.model

/**
 * How smoothly each screen draws on this box, for the start-up report: the share of frames that
 * came too late (a stutter). Counted per screen and written as one line once a screen has been
 * on long enough to say (300 frames, about 5 seconds), when you leave it (and every few minutes
 * on a screen you stay on):
 *   "Smoothness: Guide 6% slow frames (2400 frames, AFTKA)"
 * So "it feels sluggish" can be told apart by screen and device, and a release checked against
 * the last one.
 */
object LiveSmoothness {
    private class Tally { var frames = 0; var slow = 0 }

    private val tallies = HashMap<String, Tally>()
    private var current: String? = null

    /** Names for the app's screens (from the navigation route or the Live TV view). */
    private fun label(screen: String): String {
        val base = screen.substringBefore('/').substringBefore('?')
        return when (base) {
            "livetv" -> "Live TV"
            "sports" -> "Sports"
            "home" -> "Home"
            "settings" -> "Settings"
            "search" -> "Search"
            "library" -> "Library"
            "discover" -> "Discover"
            "detail" -> "Details"
            else -> base.replaceFirstChar { it.uppercase() }
        }
    }

    @Synchronized
    fun frame(screen: String?, slow: Boolean) {
        val s = screen?.takeIf { it.isNotBlank() } ?: return
        if (s != current) {
            current?.let { report(it) }
            current = s
        }
        val t = tallies.getOrPut(s) { Tally() }
        t.frames++
        if (slow) t.slow++
        // Staying on one screen a long time (watching, browsing): a line every few minutes too.
        if (t.frames >= 10_000) report(s)
    }

    private fun report(screen: String) {
        val t = tallies[screen] ?: return
        if (t.frames < 300) return
        val pct = t.slow * 100.0 / t.frames
        val shown = if (pct < 10) String.format(java.util.Locale.US, "%.1f", pct) else pct.toInt().toString()
        LiveTvLoadReport.add("Smoothness: ${label(screen)} $shown% slow frames (${t.frames} frames, ${android.os.Build.MODEL})")
        tallies.remove(screen)
    }
}
