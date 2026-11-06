package com.nuvio.tv.core.plugin.cloudstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.IptvStorageTestEntryPoint
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.data.local.PluginSyncProfile
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.livetv.storage.StorageTestContext
import com.squareup.moshi.Moshi
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

private const val FIXTURE_PROFILE = 42

@RunWith(AndroidJUnit4::class)
class ExternalRepoStorageTvTest {
    @Test fun partialRefreshPreservesDisabledChoicesAndFailedPackages() = runBlocking(PluginSyncProfile(FIXTURE_PROFILE)) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = StorageTestContext(app)
        val factory = ProfileDataStoreFactory(context)
        val profiles = EntryPointAccessors.fromApplication(app, IptvStorageTestEntryPoint::class.java).profileManager()
        val store = PluginDataStore(context, Moshi.Builder().build(), factory, profiles)
        val repo = PluginRepository("fixture", "Archive", "https://example.org/repo.json", type = RepositoryType.EXTERNAL_DEX)
        val old = ScraperInfo("fixture:a", "First", "", "1", "a.cs3", listOf("tv"), true, true,
            null, emptyList(), repo.id, null, RepositoryType.EXTERNAL_DEX)
        val retained = old.copy(id = "fixture:b")
        try {
            store.storeExternalDownloads(FIXTURE_PROFILE, repo, listOf(old, retained), refresh = false)
            store.setScraperEnabled(old.id, false)
            val update = old.copy(version = "2", supportedTypes = listOf("movie", "tv"))
            val refreshed = store.storeExternalDownloads(FIXTURE_PROFILE, repo, listOf(update), refresh = true)
            assertEquals(2, refreshed.scraperCount)
            assertEquals(listOf(update.copy(enabled = false), retained), store.scrapers.first())
            assertEquals(refreshed, store.repositories.first().single())
        } finally {
            factory.clearProfile(FIXTURE_PROFILE)
        }
    }
}
