package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpstreamSourceChipFocusTvTest {
    @get:Rule val compose = createComposeRule()
    private val names = mutableStateOf((1..20).map { "Addon $it" })
    private val selected = mutableStateOf<String?>(null)

    private fun open(direction: LayoutDirection) {
        val results = FocusRequester()
        compose.setContent { NuvioTheme {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Column(Modifier.width(360.dp)) {
                    AddonFilterChips(addons = names.value, selectedAddon = selected.value,
                        onAddonSelected = { selected.value = it })
                    Box(Modifier.size(200.dp, 60.dp).focusRequester(results).focusable().testTag("results"))
                }
                LaunchedEffect(Unit) { results.requestFocus() }
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("results").assertIsFocused()
    }

    @Test fun offscreenSelectionStaysVisibleWithoutStealingFocusAndNewAddonKeepsIdentity() {
        open(LayoutDirection.Ltr)
        compose.runOnIdle { selected.value = "Addon 18" }
        compose.waitForIdle()
        compose.onNodeWithTag("results").assertIsFocused()
        compose.onNodeWithText("Addon 18").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Addon 18").assertIsFocused()
        compose.runOnIdle { names.value = listOf("New arrival") + names.value }
        compose.waitForIdle()
        compose.onNodeWithText("Addon 18").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Addon 19", selected.value) }
        compose.onNodeWithText("Addon 19").assertIsFocused()
    }

    @Test fun rtlRemoteNavigationUsesSelectedChipAndRetainsThePhysicalDirection() {
        open(LayoutDirection.Rtl)
        compose.runOnIdle { selected.value = "Addon 18" }
        compose.waitForIdle()
        compose.onNodeWithText("Addon 18").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithText("Addon 18").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Addon 19", selected.value) }
        compose.onNodeWithText("Addon 19").assertIsFocused()
    }
}
