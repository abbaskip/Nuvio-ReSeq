package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the downloaded update APK in app storage and remembers which release it belongs to, so a download made in the
 * background can be installed later, from the banner or by the idle install job.
 */
@Singleton
class UpdateDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) {
    // Files storage instead of cache, which the system may clear before the update is installed.
    private val updatesDir: File get() = File(context.filesDir, "updates")
    private val files get() = UpdateFiles(updatesDir)

    // The banner and the background job can ask for the same download; the second one waits and reuses the file.
    private val downloadMutex = Mutex()

    fun destinationFor(update: AppUpdate): File {
        val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(updatesDir, safeName).also { require(files.contains(it)) }
    }

    /** Returns the already downloaded APK for this release, or null when it is missing or incomplete. */
    suspend fun downloadedFileFor(update: AppUpdate): File? {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return null
        if (tag != update.tag) return null
        val file = File(path)
        val expectedSize = update.assetSizeBytes
        return file.takeIf { files.contains(it) && it.exists() && it.length() > 0 &&
            System.currentTimeMillis() - it.lastModified() < UpdateFiles.PENDING_TTL_MS &&
            (expectedSize == null || it.length() == expectedSize) }
    }

    /** Returns the downloaded APK when it is for a version newer than the installed one. */
    suspend fun pendingInstall(): Pair<String, File>? {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return null
        val file = File(path)
        if (!files.contains(file) || !file.exists() || file.length() == 0L ||
            System.currentTimeMillis() - file.lastModified() >= UpdateFiles.PENDING_TTL_MS ||
            !VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) return null
        return tag to file
    }

    suspend fun download(
        update: AppUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<File> = downloadMutex.withLock {
        downloadedFileFor(update)?.let { return@withLock Result.success(it) }
        files.sweep(null, keepPending = false)
        val destination = destinationFor(update)
        if (files.isPinned(destination)) return@withLock Result.failure(IllegalStateException("An update installation is still in progress"))
        apkDownloader.download(update.assetUrl, destination, onProgress)
            .mapCatching { file ->
                if ((update.assetSizeBytes != null && file.length() != update.assetSizeBytes) ||
                    !ApkInstaller.isCompatibleUpdate(context, file)) {
                    files.completed(file)
                    error("Downloaded update is incomplete or incompatible")
                }
                file
            }
            .onSuccess { file ->
                files.sweep(file, keepPending = true)
                updatePreferences.setDownloadedUpdate(update.tag, file.absolutePath)
            }
    }

    /** Drops the downloaded APK once it is installed, outdated, or no longer wanted. */
    suspend fun clear() = downloadMutex.withLock {
        if (files.sweep(null, keepPending = false)) updatePreferences.setDownloadedUpdate(null, null)
    }

    suspend fun clearIfInstalled() = downloadMutex.withLock {
        val record = updatePreferences.downloadedUpdate.first()
        val pending = record?.second?.let(::File)
        val keep = record != null && VersionUtils.isRemoteNewer(record.first, BuildConfig.VERSION_NAME) &&
            pending != null && files.contains(pending) && pending.exists() && pending.length() > 0 &&
            System.currentTimeMillis() - pending.lastModified() < UpdateFiles.PENDING_TTL_MS
        if (record != null && pending != null && !VersionUtils.isRemoteNewer(record.first, BuildConfig.VERSION_NAME)) {
            files.completed(pending)
        }
        val removed = files.sweep(pending, keepPending = keep)
        if (!keep && removed) updatePreferences.setDownloadedUpdate(null, null)
    }
}
