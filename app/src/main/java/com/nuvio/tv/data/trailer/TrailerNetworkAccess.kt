package com.nuvio.tv.data.trailer

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow

/** The mobile app supplies its device's trailer data preference. */
interface TrailerNetworkAccess {
    val playbackAllowed: StateFlow<Boolean>
    fun allowsPlaybackNow(): Boolean
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TrailerNetworkModule {
    @BindsOptionalOf abstract fun access(): TrailerNetworkAccess
}
