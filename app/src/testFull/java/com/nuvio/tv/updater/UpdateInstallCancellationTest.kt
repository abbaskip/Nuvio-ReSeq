package com.nuvio.tv.updater

import android.content.Intent
import android.content.pm.PackageInstaller
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UpdateInstallCancellationTest {
    @Test fun cancellationNeverStartsAnotherInstallerAndPreservesTheDownload() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val apk = File(directory, "cancelled.apk").apply { writeText("available for user retry") }
        mockkObject(ApkInstaller)
        try {
            every { ApkInstaller.install(any(), any(), any(), any()) } just Runs
            every { ApkInstaller.launchSystemInstaller(any(), any()) } just Runs
            for (background in listOf(false, true)) {
                for (withMetadata in listOf(false, true)) {
                    UpdateInstallResultReceiver().onReceive(context, callback(apk,
                        PackageInstaller.STATUS_FAILURE_ABORTED, background, withMetadata))
                }
            }
            verify(exactly = 0) { ApkInstaller.install(any(), any(), any(), any()) }
            verify(exactly = 0) { ApkInstaller.launchSystemInstaller(any(), any()) }
            assertEquals("available for user retry", apk.readText())
        } finally {
            unmockkObject(ApkInstaller)
        }
    }

    @Test fun genuineFailuresRetainMetadataRetryAndForegroundSystemFallback() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val apk = File(directory, "failed.apk").apply { writeText("available for retry") }
        mockkObject(ApkInstaller)
        try {
            every { ApkInstaller.install(any(), any(), any(), any()) } just Runs
            every { ApkInstaller.launchSystemInstaller(any(), any()) } just Runs
            UpdateInstallResultReceiver().onReceive(context,
                callback(apk, PackageInstaller.STATUS_FAILURE_STORAGE, false, true))
            verify(exactly = 1) { ApkInstaller.install(context, apk, false, false) }
            UpdateInstallResultReceiver().onReceive(context,
                callback(apk, PackageInstaller.STATUS_FAILURE_STORAGE, false, false))
            verify(exactly = 1) { ApkInstaller.launchSystemInstaller(context, apk) }
            assertEquals("available for retry", apk.readText())
        } finally {
            unmockkObject(ApkInstaller)
        }
    }

    private fun callback(apk: File, status: Int, background: Boolean, withMetadata: Boolean) =
        Intent().putExtra(PackageInstaller.EXTRA_STATUS, status)
            .putExtra("com.nuvio.tv.updater.APK_PATH", apk.absolutePath)
            .putExtra("com.nuvio.tv.updater.BACKGROUND", background)
            .putExtra("com.nuvio.tv.updater.WITH_DEX_METADATA", withMetadata)
}
