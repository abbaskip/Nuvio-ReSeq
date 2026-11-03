package com.nuvio.tv.livetv.ui

/** Leave CPU time for the picture while a held remote button moves through the guide. */
internal class GuideNavigationRepeatGate {
    private var lastKeyCode: Int? = null
    private var lastAcceptedAtMs = 0L

    fun shouldConsume(keyCode: Int, repeated: Boolean, eventTimeMs: Long, playbackActive: Boolean): Boolean {
        if (playbackActive && repeated && keyCode == lastKeyCode &&
            eventTimeMs - lastAcceptedAtMs in 0 until REPEAT_INTERVAL_MS
        ) return true
        lastKeyCode = keyCode
        lastAcceptedAtMs = eventTimeMs
        return false
    }

    private companion object {
        const val REPEAT_INTERVAL_MS = 100L
    }
}

internal const val PLAYING_GUIDE_DETAILS_DELAY_MS = 350L
