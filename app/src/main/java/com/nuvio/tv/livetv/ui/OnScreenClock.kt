package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.model.LiveTvSettings
import kotlinx.coroutines.delay

/**
 * The always-on clock in a corner of the full-screen player (Settings → Live TV → On-Screen
 * Clock). It moves a few pixels every few minutes, so it never sits in exactly the same spot
 * for hours (screen burn-in on OLED TVs).
 */
@Composable
internal fun BoxScope.OnScreenClock(settings: LiveTvSettings, visible: Boolean) {
    if (settings.clockMode != "always" || !visible) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Tick on the minute change rather than every second.
            delay(60_000L - now % 60_000L + 50)
        }
    }
    // Burn-in protection: a small step every 3 minutes, around a 4-position loop.
    val step = ((now / 180_000L) % 4).toInt()
    val dx = if (step == 1 || step == 2) 6 else 0
    val dy = if (step >= 2) 4 else 0
    val alignment = when (settings.clockPosition) {
        "top_left" -> Alignment.TopStart
        "bottom_left" -> Alignment.BottomStart
        "bottom_right" -> Alignment.BottomEnd
        else -> Alignment.TopEnd
    }
    val left = settings.clockPosition.endsWith("left")
    val top = settings.clockPosition.startsWith("top")
    val size = when (settings.clockSize) {
        "small" -> 16.sp
        "large" -> 30.sp
        else -> 22.sp
    }
    LiveText(
        formatClock(now, settings.use24HourClock),
        size = size,
        weight = FontWeight.SemiBold,
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 36.dp, vertical = 28.dp)
            .offset(x = (if (left) dx else -dx).dp, y = (if (top) dy else -dy).dp)
            .alpha(settings.clockOpacity.coerceIn(10, 100) / 100f)
            .then(
                if (settings.clockBackground) Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                else Modifier
            )
    )
}
