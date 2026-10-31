package com.nuvio.tv.livetv.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the TV awake (no screen saver) while [on]. Every screen that plays video asks through
 * this, and the screen only goes back to normal once none of them wants it any more.
 *
 * Before, each screen switched "keep the screen on" on and off by itself: the guide (or the
 * Sports page) turning it off on its way out could turn it off under full screen, which had just
 * turned it on, and the screen saver (Fire TV's, for one) then started in the middle of a channel.
 */
@Composable
internal fun KeepScreenAwake(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on, view) {
        if (on) ScreenAwake.acquire(view)
        onDispose { if (on) ScreenAwake.release(view) }
    }
}

internal object ScreenAwake {
    private var holders = 0

    fun acquire(view: View) {
        holders++
        view.keepScreenOn = true
    }

    fun release(view: View) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) view.keepScreenOn = false
    }
}
