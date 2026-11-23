// Adapted from NuvioPlusIPTV-Mobile PlayerSurfaceGestures.kt (GPL-3.0). See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.screens.player.PlayerEvent
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun MobileVideoGestures(position: Long, duration: Long, speed: Float, onEvent: (PlayerEvent) -> Unit, toggle: () -> Unit) {
    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val pos by rememberUpdatedState(position)
    val length by rememberUpdatedState(duration)
    val rate by rememberUpdatedState(speed)
    val send by rememberUpdatedState(onEvent)
    val tap by rememberUpdatedState(toggle)
    var feedback by remember { mutableStateOf("") }
    DisposableEffect(window) {
        val initial = window?.attributes?.screenBrightness
        onDispose { if (initial != null) window.attributes = window.attributes.apply { screenBrightness = initial } }
    }
    Box(Modifier.fillMaxSize().semantics { contentDescription = "Video player"; onClick(label = "Show or hide controls") { tap(); true } }
        .pointerInput(Unit) {
            var restoreSpeed: Float? = null
            detectTapGestures(onTap = { tap() }, onDoubleTap = { point ->
                send(when { point.x < size.width * .35f -> PlayerEvent.OnSeekBy(-10000)
                    point.x > size.width * .65f -> PlayerEvent.OnSeekBy(10000)
                    else -> PlayerEvent.OnPlayPause })
            }, onLongPress = {
                restoreSpeed = rate; send(PlayerEvent.OnSetPlaybackSpeed(2f)); feedback = "2×"
            }, onPress = {
                try { tryAwaitRelease() } finally {
                    restoreSpeed?.let { send(PlayerEvent.OnSetPlaybackSpeed(it)); feedback = ""; restoreSpeed = null }
                }
            })
        }.pointerInput(window, audio) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                if (width <= 0 || height <= 0 || down.position.x < 20.dp.toPx() || down.position.x > width - 20.dp.toPx()) return@awaitEachGesture
                val baseline = pos
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum
                val brightness = window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                    ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                var mode: GestureAxis? = null
                var target = baseline
                var verticalStart = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.size != 1 || change.isConsumed && mode == null && change.pressed) break
                    if (!change.pressed) {
                        if (mode == GestureAxis.SEEK && length > 0) send(PlayerEvent.OnSeekTo(target))
                        break
                    }
                    val delta = change.position - down.position
                    if (mode == null) {
                        mode = when {
                            length > 0 && abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y) -> GestureAxis.SEEK
                            abs(delta.y) > maxOf(viewConfiguration.touchSlop * 2f, height * .035f) && abs(delta.y) > abs(delta.x) * 1.3f -> {
                                verticalStart = delta.y
                                when { down.position.x < width * .35f -> GestureAxis.BRIGHTNESS
                                    down.position.x > width * .65f -> GestureAxis.VOLUME
                                    else -> null }
                            }
                            else -> null
                        }
                    }
                    val dy = -(delta.y - verticalStart) / height * 1.5f
                    when (mode) {
                        GestureAxis.SEEK -> {
                            val span = when { length >= 3600000 -> 120000; length >= 1800000 -> 90000; else -> 60000 }
                            target = (baseline + (delta.x / width * span).roundToLong()).coerceIn(0, length.coerceAtLeast(0))
                            feedback = "Seek · " + mobilePlaybackTime(target)
                        }
                        GestureAxis.BRIGHTNESS -> {
                            val value = (brightness + dy).coerceIn(.01f, 1f)
                            window?.attributes = window.attributes.apply { screenBrightness = value }
                            feedback = "Brightness ${(value * 100).roundToInt()}%"
                        }
                        GestureAxis.VOLUME -> {
                            val value = ((volume + dy).coerceIn(0f, 1f) * maximum).roundToInt()
                            if (!audio.isVolumeFixed) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                            feedback = "Volume $value / $maximum"
                        }
                        null -> continue
                    }
                    change.consume()
                }
                feedback = ""
            }
        }) {
        if (feedback.isNotEmpty()) Label(feedback, Modifier.align(Alignment.Center).padding(12.dp), strong = true)
    }
}

private enum class GestureAxis { SEEK, BRIGHTNESS, VOLUME }

internal fun mobilePlaybackTime(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
