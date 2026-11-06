package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>
    /** Wait for the full list, or return earlier if all explicitly requested manifests are available. */
    suspend fun awaitInstalledAddons(requiredIds: Set<String> = emptySet()): List<Addon> = getInstalledAddons().first()
    suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAddon(url: String)
    suspend fun removeAddon(url: String)
    suspend fun setAddonOrder(urls: List<String>)
    suspend fun setAddonEnabled(url: String, enabled: Boolean)
}
