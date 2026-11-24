package com.nuvio.tv.updater

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nuvio.tv.BuildConfig
import java.io.File

object ApkInstaller {

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }
    }

    /**
     * Installs [apkFile] as an update of this app through a PackageInstaller session, adding the APK's own dex
     * metadata so the new version is compiled at install time. A [background] install asks the system to skip the
     * confirmation screen, which Android 12+ allows for an app updating itself; elsewhere the system still asks.
     * The outcome arrives in [UpdateInstallResultReceiver].
     */
    @Synchronized
    fun install(
        context: Context,
        apkFile: File,
        background: Boolean,
        withDexMetadata: Boolean = true
    ) {
        UpdateFiles(File(context.filesDir, "updates")).pin(apkFile)
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Sessions default to internal-only, which the system rejects for an app the user moved to adopted
            // storage. Auto keeps the update on the volume the app is already on.
            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
            if (background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val dexMetadata = if (withDexMetadata) {
            UpdateDexMetadata.extract(apkFile, Build.VERSION.SDK_INT, File(apkFile.parentFile, "update.dm"))
        } else {
            null
        }
        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.writeFile("base.apk", apkFile)
                dexMetadata?.let { session.writeFile("base.dm", it) }
                if (background) UpdateRelaunchReceiver.cancelReopen(context)
                else UpdateRelaunchReceiver.requestReopen(context)
                session.commit(
                    UpdateInstallResultReceiver.statusReceiver(
                        context = context,
                        sessionId = sessionId,
                        apkFile = apkFile,
                        background = background,
                        withDexMetadata = dexMetadata != null
                    ).intentSender
                )
            }
        } catch (e: Exception) {
            packageInstaller.abandonSession(sessionId)
            throw e
        } finally {
            dexMetadata?.delete()
        }
    }

    /** Opens the downloaded APK in the system installer with temporary read access. */
    @Synchronized
    fun launchSystemInstaller(context: Context, apkFile: File) {
        UpdateRelaunchReceiver.cancelReopen(context)
        UpdateFiles(File(context.filesDir, "updates")).pin(apkFile)
        context.startActivity(systemInstallerIntent(context, apkFile))
    }

    // A session owns its staged copy after commit. An external installer may still need to open our URI.
    @Synchronized
    internal fun discardDownloads(context: Context, files: UpdateFiles): Boolean =
        files.discardDownloads { apk ->
            val intent = systemInstallerIntent(context, apk)
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any { handler ->
                context.checkUriPermission(requireNotNull(intent.data), -1, handler.activityInfo.applicationInfo.uid,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
            }
        }

    private fun systemInstallerIntent(context: Context, apkFile: File): Intent {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)

        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .apply { clipData = ClipData.newRawUri(apkFile.name, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun PackageInstaller.Session.writeFile(name: String, file: File) {
        file.inputStream().use { input ->
            openWrite(name, 0, file.length()).use { output ->
                input.copyTo(output)
                fsync(output)
            }
        }
    }
}
