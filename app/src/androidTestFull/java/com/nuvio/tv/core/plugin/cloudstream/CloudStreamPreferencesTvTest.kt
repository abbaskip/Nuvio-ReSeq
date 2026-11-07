package com.nuvio.tv.core.plugin.cloudstream

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.ui.screens.plugin.CloudStreamPreferences
import com.nuvio.tv.ui.screens.plugin.PluginUiState
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudStreamPreferencesTvTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun remoteCanSelectLanguagesAndChangeDns() {
        val state = mutableStateOf(PluginUiState(installedLanguages = mapOf("en" to 2, "hi" to 3)))
        compose.runOnUiThread {
            compose.activity.setContent {
                NuvioTheme {
                    CloudStreamPreferences(state.value, false,
                        onLanguage = { code -> state.value = state.value.copy(sourceLanguages = code?.let { setOf(it) }.orEmpty()) },
                        onDns = { state.value = state.value.copy(extensionDns = "CLOUDFLARE") })
                }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun press(key: Int) { instrumentation.sendKeyDownUpSync(key); compose.waitForIdle() }
        compose.onNodeWithText("All languages").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithText("All languages").assertIsFocused().assertIsSelected()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("English · 2").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("English · 2").assertIsSelected()
        compose.onNodeWithText("All languages").assertIsNotSelected()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("CloudStream DNS · Cloudflare").assertIsDisplayed()
        assertTrue(state.value.sourceLanguages == setOf("en"))
    }
}
