package com.nuvio.tv.livetv.multiview

import android.content.Context

/** Persist only a guide-return marker. Pane choices, stream links and playback are never saved. */
internal class MultiviewRecoveryFlag(private val read: () -> Boolean, private val write: (Boolean) -> Unit) {
    fun opened() = write(true)
    fun left() = write(false)
    fun takeGuideReturn(): Boolean = read().also { if (it) write(false) }
}

internal object MultiviewRecovery {
    private fun flag(context: Context): MultiviewRecoveryFlag {
        val prefs = context.applicationContext.getSharedPreferences("multiview_recovery", Context.MODE_PRIVATE)
        return MultiviewRecoveryFlag({ prefs.getBoolean("return_to_guide", false) }, {
            // Commit the tiny marker before opening panes, including an immediate force-stop.
            prefs.edit().putBoolean("return_to_guide", it).commit()
        })
    }
    fun opened(context: Context) = flag(context).opened()
    fun left(context: Context) = flag(context).left()
    fun takeGuideReturn(context: Context) = flag(context).takeGuideReturn()
}
