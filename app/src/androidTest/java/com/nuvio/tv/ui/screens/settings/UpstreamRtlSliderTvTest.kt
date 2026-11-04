package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpstreamRtlSliderTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun negativeOffsetGlyphOrderAndRemoteDirectionStayCorrectInRtl() {
        val value = mutableIntStateOf(-500)
        val focus = FocusRequester()
        compose.setContent { NuvioTheme {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SliderSettingsItem(title = "Offset", value = value.intValue,
                    valueText = "${value.intValue} ms", minValue = -1000, maxValue = 1000,
                    step = 100, onValueChange = { value.intValue = it },
                    modifier = Modifier.width(600.dp).focusRequester(focus).testTag("offset"))
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        } }
        compose.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("-500 ms", substring = true, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val text = layout.layoutInput.text.text
        assertTrue("The minus glyph must remain before the number in an RTL layout",
            layout.getBoundingBox(text.indexOf('-')).left < layout.getBoundingBox(text.indexOf('5')).left)
        compose.onNodeWithTag("offset").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(-400, value.intValue) }
        compose.onNodeWithTag("offset").performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(-500, value.intValue) }
    }
}
