package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** A dismissed TV keyboard must not leave Up/Down trapped in a single-line editor. */
@Composable
internal fun Modifier.tvTextFieldNavigation(): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> null
        }
        if (event.type == KeyEventType.KeyDown && direction != null) {
            focus.moveFocus(direction).also { moved -> if (moved) keyboard?.hide() }
        } else false
    }
}
