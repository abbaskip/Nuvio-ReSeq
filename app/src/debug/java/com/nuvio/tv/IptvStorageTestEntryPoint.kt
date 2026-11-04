package com.nuvio.tv

import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Read-only access for the isolated storage instrumentation fixtures. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface IptvStorageTestEntryPoint {
    fun profileManager(): ProfileManager
}
