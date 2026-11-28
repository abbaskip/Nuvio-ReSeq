package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.TvHiltTestActivity
import com.nuvio.tv.core.streams.SourceStreamTemplates
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceStreamFormatTvTest {
    @get:Rule
    val compose = createAndroidComposeRule<TvHiltTestActivity>()

    @Test
    fun templatesCanBeEditedAndRestoredOnTheTv() {
        val initial = UsenetSourceConfiguration(maxSizeGb = 20)
        var saved = initial
        compose.setContent {
            NuvioTheme {
                var configuration by remember { mutableStateOf(initial) }
                UsenetSourcesCard(
                    configuration = configuration,
                    update = { configuration = it; saved = it },
                    testIndexer = { false },
                    testProvider = { null },
                    initialFocusRequester = remember { FocusRequester() },
                    expanded = emptySet()
                )
            }
        }

        click(R.string.source_stream_format_title)
        val custom = SourceStreamTemplates("{stream.type}\n{stream.resolution}", "{stream.title}\n{stream.filename}")
        val fields = compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        fields.assertCountEquals(2)
        fields[0].performTextReplacement(custom.name)
        fields[1].performTextReplacement(custom.description)
        click(R.string.action_save)
        compose.runOnIdle { assertEquals(initial.copy(streamTemplates = custom), saved) }

        click(R.string.source_stream_format_title)
        click(R.string.web_debrid_restore_default)
        click(R.string.action_save)
        compose.runOnIdle { assertEquals(initial, saved) }
    }

    private fun click(title: Int) {
        try {
            compose.onNode(hasText(label(title)) and hasClickAction())
                .performSemanticsAction(SemanticsActions.OnClick)
        } catch (failure: AssertionError) {
            throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(10), failure)
        }
    }

    private fun label(title: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(title)
}
