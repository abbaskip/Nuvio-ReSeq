package com.nuvio.tv.updater

import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UpdateInstallCleanupTest {
    @Test fun successDeletesOnlyTheInstallerCapturedByThatSession() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val apk = File(directory, "fixture.apk").apply { writeText("completed installer") }
        val files = UpdateFiles(directory)
        files.pin(apk)
        fun callback() = Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
            .putExtra("com.nuvio.tv.updater.APK_PATH", apk.absolutePath)
            .putExtra("com.nuvio.tv.updater.APK_MODIFIED", apk.lastModified())
            .putExtra("com.nuvio.tv.updater.APK_SIZE", apk.length())
        val stale = callback()
        apk.appendText(" newer download")
        UpdateInstallResultReceiver().onReceive(context, stale)
        assertTrue("An old callback must not delete a newer file", apk.exists())
        UpdateInstallResultReceiver().onReceive(context, callback())
        assertFalse(apk.exists())
        assertFalse(File(directory, "fixture.apk.installing").exists())
        val recording = File(context.filesDir, "recording.ts").apply { writeText("keep") }
        UpdateInstallResultReceiver().onReceive(context, Intent()
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
            .putExtra("com.nuvio.tv.updater.APK_PATH", recording.absolutePath)
            .putExtra("com.nuvio.tv.updater.APK_MODIFIED", recording.lastModified())
            .putExtra("com.nuvio.tv.updater.APK_SIZE", recording.length()))
        assertTrue(recording.exists())
    }
}
