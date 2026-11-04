package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TvMixStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.screens.home.rebuildCatalogOrder
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogOrderTvMixTest {
    @get:Rule val main = MainDispatcherRule()
    private val models = mutableListOf<CatalogOrderViewModel>()
    private val mixes = MutableStateFlow(listOf(TvMix("mix", "Sitcoms")))
    private val order = MutableStateFlow<List<String>>(emptyList())
    private val disabled = MutableStateFlow<List<String>>(emptyList())
    private val follow = MutableStateFlow(false)
    private val collections = MutableStateFlow(listOf(Collection("saved", "Favorites")))
    private val addons = listOf(addon("first", "one", "two"), addon("second", "three"))
    private val installed = MutableStateFlow(addons)
    private val addonKeys = addons.flatMap { a -> a.catalogs.map { catalogRowLegacyKey(a.id, it.apiType, it.id) } }
    private val collectionKey = "collection_saved"

    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() } }

    @Test fun `mix moves up and down with normal catalogues and restored settings keep its position`() = runTest {
        val vm = model()
        advanceUntilIdle()
        assertEquals(addonKeys + collectionKey + TvMixHomeCatalog.key, keys(vm))
        repeat(2) { vm.moveUp(TvMixHomeCatalog.key); advanceUntilIdle() }
        val expected = listOf(addonKeys[0], addonKeys[1], TvMixHomeCatalog.key, addonKeys[2], collectionKey)
        assertEquals(expected, order.value)
        assertEquals(expected, homeOrder())
        val restored = model()
        advanceUntilIdle()
        assertEquals(expected, keys(restored))
        restored.moveDown(TvMixHomeCatalog.key)
        advanceUntilIdle()
        assertEquals(listOf(addonKeys[0], addonKeys[1], addonKeys[2], TvMixHomeCatalog.key, collectionKey), homeOrder())
    }

    @Test fun `follow addon order moves TV Mix across whole addon blocks and agrees with Home`() = runTest {
        follow.value = true
        val vm = model()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.items.last().canMoveUp)
        assertFalse(vm.uiState.value.items.first().canMoveDown)
        vm.moveUp(TvMixHomeCatalog.key)
        advanceUntilIdle()
        assertEquals(addonKeys + TvMixHomeCatalog.key + collectionKey, keys(vm))
        vm.moveUp(TvMixHomeCatalog.key)
        advanceUntilIdle()
        assertEquals(listOf(addonKeys[0], addonKeys[1], TvMixHomeCatalog.key, addonKeys[2], collectionKey), keys(vm))
        vm.moveUp(TvMixHomeCatalog.key)
        advanceUntilIdle()
        assertEquals(listOf(TvMixHomeCatalog.key) + addonKeys + collectionKey, keys(vm))
        assertEquals(keys(vm), homeOrder())
        vm.moveDown(TvMixHomeCatalog.key)
        advanceUntilIdle()
        assertEquals(listOf(addonKeys[0], addonKeys[1], TvMixHomeCatalog.key, addonKeys[2], collectionKey), homeOrder())
    }

    @Test fun `enabling follow order normalizes a mid addon mix position in settings and Home`() = runTest {
        order.value = listOf(addonKeys[0], TvMixHomeCatalog.key, addonKeys[1], collectionKey, addonKeys[2])
        follow.value = true
        val vm = model()
        advanceUntilIdle()
        val expected = listOf(addonKeys[0], addonKeys[1], TvMixHomeCatalog.key, collectionKey, addonKeys[2])
        assertEquals(expected, keys(vm))
        assertEquals(expected, homeOrder())
    }

    @Test fun `mix entry reacts to profile data and uses the existing enabled setting`() = runTest {
        mixes.value = emptyList()
        val vm = model()
        advanceUntilIdle()
        assertFalse(keys(vm).contains(TvMixHomeCatalog.key))
        mixes.value = listOf(TvMix("other-profile", "Travel"))
        advanceUntilIdle()
        assertEquals("TV Mix", vm.uiState.value.items.last().catalogName)
        vm.toggleCatalogEnabled(TvMixHomeCatalog.key)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.items.last().isDisabled)
        assertEquals(listOf(TvMixHomeCatalog.key), disabled.value)
        vm.toggleCatalogEnabled(TvMixHomeCatalog.key)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.items.last().isDisabled)
        mixes.value = emptyList()
        advanceUntilIdle()
        assertFalse(keys(vm).contains(TvMixHomeCatalog.key))
        assertFalse(homeOrder().contains(TvMixHomeCatalog.key))
    }

    @Test fun `TV Mix can move around a collection when there are no addon catalogues`() = runTest {
        installed.value = emptyList()
        follow.value = true
        val vm = model()
        advanceUntilIdle()
        assertEquals(listOf(collectionKey, TvMixHomeCatalog.key), keys(vm))
        vm.moveUp(TvMixHomeCatalog.key)
        advanceUntilIdle()
        assertEquals(listOf(TvMixHomeCatalog.key, collectionKey), keys(vm))
        assertEquals(keys(vm), homeOrder())
        vm.moveDown(TvMixHomeCatalog.key)
        advanceUntilIdle()
        assertEquals(listOf(collectionKey, TvMixHomeCatalog.key), keys(vm))
        assertEquals(keys(vm), homeOrder())
    }

    private fun keys(vm: CatalogOrderViewModel) = vm.uiState.value.items.map { it.key }

    private fun model(): CatalogOrderViewModel {
        val repository = mockk<AddonRepository>()
        every { repository.getInstalledAddons() } returns installed
        val collectionStore = mockk<CollectionsDataStore>()
        every { collectionStore.collections } returns collections
        val layout = mockk<LayoutPreferenceDataStore>()
        every { layout.homeCatalogOrderKeys } returns order
        every { layout.disabledHomeCatalogKeys } returns disabled
        every { layout.customCatalogTitles } returns MutableStateFlow(emptyMap())
        every { layout.followAddonsOrder } returns follow
        coEvery { layout.setHomeCatalogOrderKeys(any()) } coAnswers { order.value = firstArg() }
        coEvery { layout.setDisabledHomeCatalogKeys(any()) } coAnswers { disabled.value = firstArg() }
        val servers = mockk<ServerCatalog>()
        every { servers.addons } returns MutableStateFlow(emptyList())
        val store = mockk<TvMixStore>()
        every { store.mixes } returns mixes
        return CatalogOrderViewModel(repository, collectionStore, layout, mockk<HomeCatalogSettingsSyncService>(relaxed = true), servers, store)
            .also { models += it }
    }

    private fun homeOrder(): List<String> {
        val vm = mockk<HomeViewModel>()
        val result = mutableListOf<String>()
        every { vm.catalogStateLock } returns Any()
        every { vm.catalogOrder } returns result
        every { vm.homeCatalogOrderKeys } returns order.value
        every { vm.collectionsCache } returns collections.value
        every { vm.tvMixes } returns mixes.value
        every { vm.followAddonsOrderEnabled } returns follow.value
        every { vm.disabledHomeCatalogKeys } returns disabled.value.toSet()
        vm.rebuildCatalogOrder(installed.value)
        return result.toList()
    }

    private fun addon(id: String, vararg catalogs: String) = Addon(
        id = id, name = id, version = "1", description = null, logo = null,
        baseUrl = "https://$id.example.test", types = listOf(ContentType.SERIES), resources = emptyList(),
        catalogs = catalogs.map { CatalogDescriptor(ContentType.SERIES, id = it, name = it) }
    )
}
