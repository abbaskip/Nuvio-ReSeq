package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileSourceTileTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactPhoneWrapsCompleteReleaseNamesDescriptionsAndFilenames() {
        val name = "Remastered.Extended.Edition.".repeat(8) + "HDR"
        val description = "Original audio, director commentary and restored footage. ".repeat(6).trim()
        val filename = "Season.02.Episode.13.".repeat(14) + "1080p.mkv"
        val stream = Stream(name, "Festival edition", description, "https://example.test/file", null, null, null, null,
            StreamBehaviorHints(null, null, null, null, filename = filename), "Archive", null)
        compose.setContent {
            MaterialTheme { Column(Modifier.width(285.dp).verticalScroll(rememberScrollState())) { SourceTile(stream, true) {} } }
        }
        for (field in listOf(name, description, filename)) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(field, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue(layout.lineCount > 4)
            assertFalse(layout.hasVisualOverflow)
            assertEquals(field.length, layout.getLineEnd(layout.lineCount - 1))
            assertFalse(layout.isLineEllipsized(layout.lineCount - 1))
        }
    }
}
