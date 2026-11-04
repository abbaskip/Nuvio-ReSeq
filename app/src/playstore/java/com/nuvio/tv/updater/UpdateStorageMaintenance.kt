package com.nuvio.tv.updater

import javax.inject.Inject

class UpdateStorageMaintenance @Inject constructor() {
    suspend fun clean() = Unit
}
