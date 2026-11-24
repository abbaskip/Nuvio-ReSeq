package com.nuvio.tv.updater

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.FileProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
    @Test fun settingsCleanupDiscardsThePendingRetryAfterAndroidReleasesItsReadGrant() = runBlocking {
        val context = mockk<Context>()
        val root = RuntimeEnvironment.getApplication().filesDir
        every { context.filesDir } returns root
        val directory = File(root, "updates").apply { mkdirs() }
        val apk = File(directory, "pending.apk").apply { writeText("failed update") }
        UpdateFiles(directory).pin(apk)
        val pending = MutableStateFlow<DownloadedUpdate?>(DownloadedUpdate("v9.0.0", apk.path, apk.length(), null))
        val preferences = mockk<UpdatePreferences>()
        every { preferences.downloadedUpdate } returns pending
        coEvery { preferences.setDownloadedUpdate(any()) } answers { pending.value = firstArg() }
        val store = UpdateDownloadStore(context, preferences, mockk())
        val settings = UpdateStorageMaintenance(store)
        val uri = Uri.parse("content://installer/updates/pending.apk")
        val installerUid = 12345
        val handler = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                applicationInfo = ApplicationInfo().apply { uid = installerUid }
            }
        }
        val pm = mockk<PackageManager>()
        every { context.packageManager } returns pm
        @Suppress("DEPRECATION")
        every { pm.queryIntentActivities(any<Intent>(), PackageManager.MATCH_DEFAULT_ONLY) } returns listOf(handler)
        var permission = PackageManager.PERMISSION_GRANTED
        every { context.checkUriPermission(uri, -1, installerUid, Intent.FLAG_GRANT_READ_URI_PERMISSION) } answers { permission }
        mockkStatic(FileProvider::class)
        try {
            every { FileProvider.getUriForFile(context, any(), apk) } returns uri
            store.clearIfInstalled()
            assertEquals(apk, store.pendingInstall()?.second)
            assertFalse(settings.clean())
            assertTrue(apk.exists())
            assertNotNull(pending.value)
            permission = PackageManager.PERMISSION_DENIED
            assertTrue(settings.clean())
            assertTrue(directory.listFiles()!!.isEmpty())
            assertNull(pending.value)
            assertNull(store.pendingInstall())
        } finally {
            unmockkStatic(FileProvider::class)
        }
    }

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
