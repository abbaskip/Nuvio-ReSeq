package com.nuvio.tv.livetv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GuideSearchDialogTvTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteCanChooseProgrammeOrChannelSearchWithTheSameText() {
        val searches = mutableListOf<Pair<String, String>>()
        compose.setContent {
            NuvioTheme {
                GuideSearchDialog("  Fixture  ", {}, { searches += "channel" to it }, { searches += "programme" to it })
            }
        }
        compose.waitForIdle()
        android.os.SystemClock.sleep(950)
        for (label in listOf("Search programmes", "Search channels")) {
            val action = compose.onNodeWithText(label)
            action.performSemanticsAction(SemanticsActions.RequestFocus)
            action.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        }
        compose.runOnIdle { assertEquals(listOf("programme" to "Fixture", "channel" to "Fixture"), searches) }
    }
}
