package com.nuvio.tv.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.sports.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileSportsCardTest {
    @get:Rule val compose = createComposeRule()

    private fun side(name: String, badge: String, score: String, home: Boolean, color: Long) =
        Side(TeamInfo("ca/shield", badge, name, name, badge, null, color, null, null), score, null, false, home)

    private val fixture = SportsEvent("shield-test", "ca/shield", 1795386600000L, true, GameState.LIVE,
        "NSW vs Queensland Bulls", null, "In Play",
        side("NSW", "NSW", "174/6 (60.0 ov)", false, 0xff66aadd),
        side("Queensland Bulls", "QLD", "278/10 (111.1 ov)", true, 0xff77364b))

    private fun layout(label: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val found = compose.onAllNodesWithText(label, substring = true, useUnmergedTree = true)
        for (index in found.fetchSemanticsNodes().indices) {
            found[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        }
        // A fallback team crest can print the same abbreviation as the team name.
        return layouts.maxBy { it.layoutInput.style.fontSize.value }
    }

    private fun assertFullyVisible(label: String, text: TextLayoutResult) {
        // Paragraph edges are fractional pixels, while the measured composable uses integer pixels.
        for (line in 0 until text.lineCount) {
            assertFalse("$label was ellipsized", text.isLineEllipsized(line))
            assertTrue("$label overran ${text.size.width}px at ${text.getLineRight(line)}",
                text.getLineLeft(line) >= -1f && text.getLineRight(line) <= text.size.width + 1f)
        }
        assertTrue("$label was clipped vertically", text.getLineBottom(text.lineCount - 1) <= text.size.height + 1f)
    }

    @Test fun cricketNamesAndFullInningsFitAtPhoneAndTabletWidths() {
        var width by mutableStateOf(276.dp)
        var fontScale by mutableFloatStateOf(1f)
        var hidden by mutableStateOf(false)
        var opened = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black,
                    primary = Color(0xff9abce2))) {
                    FixtureTile(fixture, hidden, false, Modifier.width(width).background(Color.Black).testTag("fixture")) {
                        opened = true
                    }
                }
            }
        }
        for ((cardWidth, scale) in listOf(276 to 1f, 316 to 1f, 340 to 1f, 276 to 1.55f, 214 to 1.55f)) {
            compose.runOnIdle { width = cardWidth.dp; fontScale = scale }
            for (name in listOf("NSW", "Queensland Bulls")) {
                val text = layout(name)
                assertFullyVisible(name, text)
                if (cardWidth >= 276 || !name.contains(' ')) {
                    assertEquals("$name wrapped at ${cardWidth}dp / $scale", 1, text.lineCount)
                } else {
                    val lines = (0 until text.lineCount).map { line ->
                        name.substring(text.getLineStart(line), text.getLineEnd(line)).trim()
                    }
                    assertEquals("$name broke inside a word", name, lines.joinToString(" "))
                }
            }
            for (score in listOf("174/6", "60.0 ov", "278/10", "111.1 ov")) {
                assertFullyVisible(score, layout(score))
            }
            if (cardWidth == 276 && scale == 1f) {
                val root = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("screenshots")
                File(root, "sheffield-shield-320dp.png").outputStream().use {
                    compose.onNodeWithTag("fixture").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        compose.onNodeWithTag("fixture").performClick()
        compose.runOnIdle { assertTrue(opened); hidden = true }
        for (label in listOf("174/6", "278/10", " ov", "In Play")) {
            compose.onNodeWithText(label, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
        assertEquals(1, layout("Queensland Bulls").lineCount)
    }
}
