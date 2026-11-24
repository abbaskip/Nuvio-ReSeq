package com.nuvio.tv.mobile

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneCastControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MobileActivity>()

    @Test fun handoffOpensControlsAndProgressUpdatesRespectDismissal() {
        val controls = mutableStateOf(CastControls(device = "Living room"))
        val item = CastItem("fixture", 1, "Test film", null, live = false, iptv = false)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            MobileCastBar(activity.casting, controls.value, {}, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Cast controls").assertIsDisplayed()
        compose.runOnIdle { controls.value = controls.value.copy(item = item, duration = 60_000, position = 12_000, playing = true) }
        compose.onNodeWithText("Stop casting").assertIsDisplayed()
        val screenshots = compose.activity.getExternalFilesDir("screenshots")
        fun save(node: SemanticsNodeInteraction, name: String) {
            File(screenshots, name).outputStream().use { node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // A Material bottom sheet lives in its own window. Capture the composed display.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Compose's test clock can settle before SurfaceFlinger presents the dialog surface.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        android.os.SystemClock.sleep(500)
        val display = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(screenshots, "phone-cast-expanded.png").outputStream().use { display.compress(Bitmap.CompressFormat.PNG, 100, it) }
        display.recycle()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { controls.value = controls.value.copy(position = 13_000) }
        compose.onNodeWithText("Stop casting").assertDoesNotExist()
        save(compose.onNode(hasClickAction() and hasText("Test film")), "phone-cast-bar.png")
        compose.onNodeWithContentDescription("Cast controls").performClick()
        compose.onNodeWithText("Stop casting").assertIsDisplayed()
    }
}
