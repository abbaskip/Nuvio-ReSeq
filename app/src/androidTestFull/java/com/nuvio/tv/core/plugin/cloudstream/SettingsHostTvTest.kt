package com.nuvio.tv.core.plugin.cloudstream

import android.content.Intent
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsHostTvTest {
    @Test fun pluginChoiceSurvivesClosingAndReopeningSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("fixture-settings", 0)
        val id = "fixture-settings"
        val file = File(context.filesDir, "cs_extensions/$id.cs3")
        file.parentFile!!.mkdirs()
        val intent = Intent(context, ExtensionSettingsActivity::class.java)
            .putExtra("scraper_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var host: ExtensionSettingsActivity? = null
        try {
            prefs.edit().clear().commit()
            writeResourceFixture(file)
            for (selected in listOf(false, true)) {
                val activity = instrumentation.startActivitySync(intent) as ExtensionSettingsActivity
                host = activity
                var ready = false
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        val fragment = activity.supportFragmentManager.findFragmentByTag("fixture-settings")
                            as? androidx.fragment.app.DialogFragment
                        val dialog = fragment?.dialog as? AlertDialog
                        if (dialog?.isShowing == true) {
                            assertEquals(selected, prefs.getBoolean("selected", false))
                            if (selected) fragment.dismiss()
                            else dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                            ready = true
                        }
                    }
                    SystemClock.sleep(50)
                }
                assertTrue("Plugin's dialog must appear", ready)
                instrumentation.waitForIdleSync()
                assertTrue("Dismiss should close the settings host", activity.isFinishing || activity.isDestroyed)
            }
        } finally {
            instrumentation.runOnMainSync { host?.finish() }
            host?.loader?.deleteExtension(id)
            file.delete()
            prefs.edit().clear().commit()
        }
    }
}
