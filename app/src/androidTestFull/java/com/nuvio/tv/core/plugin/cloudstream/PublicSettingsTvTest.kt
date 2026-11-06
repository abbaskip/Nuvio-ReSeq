package com.nuvio.tv.core.plugin.cloudstream

import android.content.Intent
import androidx.fragment.app.DialogFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.plugin.TestDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in test of the published plugin settings only. No accounts or media requests are needed. */
@RunWith(AndroidJUnit4::class)
class PublicSettingsTvTest {
    private val phisher = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/4bf1a1d463dc266163faf5d0a504d174b1e81285"

    @Test fun streamPlaySettings() = checkSettings("StreamPlay", phisher,
        "sha256-1e4c77492069b3b8da2c2825c4d40e813436c0c2feed73b193b264ba4e1736ea")
    @Test fun superStreamSettings() = checkSettings("SuperStream", phisher,
        "sha256-7104a9f6efcb35b091e124ea5562cdda9ec50361a6423f559fdeab8df4da3d14")
    @Test fun cineStreamSettings() = checkSettings("CineStream",
        "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/923c5c05a07ea2fb70ae25530d068bf1fbd4da9f",
        "sha256-30dc643628702d6b2fe139339e59996783c95a0c1d41efe0a31872ae4d10908b")

    private fun checkSettings(name: String, base: String, hash: String) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicSettings") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = "settings-test:$name"
        val loader = ExternalExtensionLoader(context, ExternalExtractorRegistry())
        var host: ExtensionSettingsActivity? = null
        try {
            assertNotNull(loader.downloadExtension(id, "$base/$name.cs3", hash))
            val trace = TestDiagnostics()
            loader.loadExtension(id, trace)
            assertNotNull("$name: ${trace.steps.joinToString()}", loader.settingsCallback(id))
            // Use the installed package and activity exactly as the Settings button does.
            val intent = Intent(context, ExtensionSettingsActivity::class.java)
                .putExtra("scraper_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val activity = instrumentation.startActivitySync(intent) as ExtensionSettingsActivity
            host = activity
            var shown = false
            withTimeout(15_000) {
                while (!shown) {
                    instrumentation.runOnMainSync {
                        assertFalse("$name settings host closed", activity.isFinishing)
                        shown = activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>()
                            .any { it.dialog?.isShowing == true }
                    }
                    if (name == "CineStream") {
                        val root = instrumentation.uiAutomation.rootInActiveWindow
                        shown = root?.findAccessibilityNodeInfosByText("Scraping Settings")?.isNotEmpty() == true
                    }
                    delay(100)
                }
            }
            // Back should dismiss the plugin dialog and return to the provider list.
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            withTimeout(5_000) { while (!activity.isFinishing && !activity.isDestroyed) delay(100) }
        } finally {
            instrumentation.runOnMainSync { host?.finish() }
            loader.deleteExtension(id)
        }
    }
}
