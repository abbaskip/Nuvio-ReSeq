package com.nuvio.tv.core.plugin.cloudstream

import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudStreamTestEntryPoint {
    fun pluginManager(): PluginManager
    fun streamRepository(): StreamRepository
}
