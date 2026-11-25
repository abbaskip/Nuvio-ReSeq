package com.nuvio.tv.updater

import javax.inject.Inject

class UpdateStorageMaintenance @Inject constructor(private val downloads: UpdateDownloadStore) {
    suspend fun clean(): Boolean = downloads.discardDownloads()
}
