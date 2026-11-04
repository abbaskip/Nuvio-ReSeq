package com.nuvio.tv.livetv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveTvOrderDialogTest {
    @get:Rule val compose = createComposeRule()
    private var saved: List<String>? = null
    private var dismissed = false
    private fun open() {
        // The instrumentation APK must carry the same configured backend as its target.
        assertTrue(listOf(com.nuvio.tv.BuildConfig.SUPABASE_URL, com.nuvio.tv.BuildConfig.SUPABASE_ANON_KEY).all { it.isNotBlank() })
        val request = OrderRequest("Manage group order", listOf("A", "B", "C", "D").map { OrderEntry(it, it) }, "B") { saved = it }
        compose.setContent { NuvioTheme { LiveTvOrderDialog(request) { dismissed = true } } }
        compose.waitForIdle()
        android.os.SystemClock.sleep(950) // Shared dialog suppresses the opening long-press release.
    }
    private fun click(label: String) { compose.onNodeWithText(label).performClick() }
    @Test fun pickupMovePutDownAndSave() {
        open()
        compose.onNodeWithText("2. B").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("✓ B").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("✓ B").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        click("Save")
        compose.runOnIdle { assertEquals(listOf("A", "C", "B", "D"), saved); assertTrue(dismissed) }
    }
    @Test fun cancelDoesNotPersistDraft() {
        open(); click("2. B"); click("Move to bottom"); click("Cancel")
        compose.runOnIdle { assertNull(saved); assertTrue(dismissed) }
    }
    @Test fun nonAdjacentMultiSelectionPreservesListOrder() {
        open()
        compose.onNodeWithText("4. D").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        click("2. B"); click("Move to top")
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "iptv-order-selection.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        click("Save")
        compose.runOnIdle { assertEquals(listOf("B", "D", "A", "C"), saved) }
    }
    @Test fun explicitSelectionModeWorksWithoutLongPress() {
        open(); click("Select multiple"); click("2. B"); click("4. D"); click("Move to bottom"); click("Save")
        compose.runOnIdle { assertEquals(listOf("A", "C", "B", "D"), saved) }
    }
    @Test fun alphabeticalWarningCancelLeavesCustomOrderUntouched() {
        var replaced = false
        compose.setContent { NuvioTheme {
            OrderSortWarningDialog(OrderSortWarning("groups") { replaced = true }) { dismissed = true }
        } }
        compose.onNodeWithText("Replace custom order?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused().performClick()
        compose.runOnIdle { assertFalse(replaced); assertTrue(dismissed) }
    }
    @Test fun alphabeticalWarningContinueStartsReplacement() {
        var replacements = 0
        compose.setContent { NuvioTheme {
            OrderSortWarningDialog(OrderSortWarning("groups") { replacements++ }) { dismissed = true }
        } }
        click("Continue")
        compose.runOnIdle { assertEquals(1, replacements); assertTrue(dismissed) }
    }

}
