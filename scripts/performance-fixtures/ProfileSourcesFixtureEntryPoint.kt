package com.nuvio.tv

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.ondemand.IptvStreamSources
import com.nuvio.tv.livetv.ondemand.IptvVodStreams
import com.nuvio.tv.livetv.ondemand.OnDemandRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProfileSourcesFixtureEntryPoint {
    fun profiles(): ProfileManager
    fun live(): LiveTvPreferences
    fun addons(): AddonRepository
    fun sources(): IptvStreamSources
    fun matches(): IptvVodStreams
    fun catalogue(): OnDemandRepository
    fun lastLinks(): StreamLinkCacheDataStore
}
