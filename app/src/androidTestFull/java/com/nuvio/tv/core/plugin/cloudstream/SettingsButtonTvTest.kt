package com.nuvio.tv.core.plugin.cloudstream

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.ui.screens.plugin.PluginScreenContent
import com.nuvio.tv.ui.screens.plugin.PluginUiState
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

class SettingsButtonTvTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun providerRowOnlyOffersAvailableSettingsAndOpensThemWithTheRemote() {
        val context = compose.activity
        val id = "fixture-settings-row"
        val file = File(context.filesDir, "cs_extensions/$id.cs3")
        file.parentFile!!.mkdirs()
        writeResourceFixture(file)
        val enabled = mutableStateOf(false)
        val repo = PluginRepository("test", "Fixture repo", "", type = RepositoryType.EXTERNAL_DEX)
        val scraper = ScraperInfo(id, "Configurable fixture", "", "1", "", listOf("movie"), true, true,
            null, emptyList(), repo.id, null, RepositoryType.EXTERNAL_DEX)
        val settings = context.getString(R.string.nav_settings)
        try {
            compose.runOnUiThread {
                context.setContent {
                    NuvioTheme {
                        PluginScreenContent(PluginUiState(repositories = listOf(repo),
                            scrapers = listOf(scraper.copy(enabled = enabled.value))))
                    }
                }
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(scraper.name))
            compose.onNodeWithText(settings).assertDoesNotExist()
            compose.runOnUiThread { enabled.value = true }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(settings).fetchSemanticsNodes().isNotEmpty() }
            // Enter the list the way a TV viewer does; direct focus requests from outside a
            // focusRestorer group deliberately go to its saved entry point.
            compose.onNode(hasScrollAction()).performScrollToIndex(0)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val visited = mutableListOf<String>()
            for (step in 0 until 18) {
                compose.waitForIdle()
                val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()
                val text = focused?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
                val description = focused?.config?.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                visited += text.ifBlank { description.joinToString() }
                if (text == settings) break
                val inProvider = text == context.getString(R.string.plugin_test_btn) ||
                    description.any { it.contains(scraper.name) }
                instrumentation.sendKeyDownUpSync(if (inProvider) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_DOWN)
            }
            org.junit.Assert.assertEquals("D-pad focus path: $visited", settings, visited.last())
            fun move(key: Int) {
                instrumentation.sendKeyDownUpSync(key)
                compose.waitForIdle()
            }
            val test = context.getString(R.string.plugin_test_btn)
            move(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithText(test).assertIsFocused()
            move(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithContentDescription(context.getString(R.string.cd_enable_scraper, scraper.name)).assertIsFocused()
            move(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithText(test).assertIsFocused()
            move(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithText(settings).assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) {
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Select")?.isNotEmpty() == true
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { context.hasWindowFocus() }
            compose.onNodeWithText(settings).assertIsDisplayed().assertIsFocused()
        } finally {
            file.delete()
        }
    }
}
