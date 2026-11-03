package com.nuvio.tv.ui.screens.tvmix

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class TvMixNameEntryTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteCanLeaveTheNameEditorAndSaveWithoutLosingText() {
        var saved: String? = null
        compose.setContent {
            NuvioTheme {
                var name by remember { mutableStateOf("") }
                val editor = remember { FocusRequester() }
                Column {
                    TvMixNameField(name, { name = it }, Modifier.focusRequester(editor).testTag("mix-name"))
                    Button(onClick = { saved = name }) { Text("Save mix") }
                }
                LaunchedEffect(Unit) { editor.requestFocus() }
            }
        }
        compose.onNodeWithTag("mix-name").performTextInput("Food and travel")
        compose.onNodeWithTag("mix-name").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Save mix").assertIsFocused()
        compose.onNodeWithText("Save mix").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("Food and travel", saved) }
    }
}
