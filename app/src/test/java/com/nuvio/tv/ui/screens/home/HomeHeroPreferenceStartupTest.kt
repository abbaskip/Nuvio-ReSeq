package com.nuvio.tv.ui.screens.home

import io.mockk.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeHeroPreferenceStartupTest {
    private class LoadStarted : RuntimeException()
    private fun home(preferences: Flow<List<String>>, observe: (List<String>) -> Unit): HomeViewModel {
        val vm = mockk<HomeViewModel>(relaxed = true)
        var keys = listOf("stale")
        every { vm.disabledHomeCatalogKeys } returns emptySet()
        every { vm.activeCatalogLoadJobs } returns mutableSetOf()
        every { vm.activeCatalogLoadSignature } returns "not-loaded"
        every { vm.layoutPreferenceDataStore.heroCatalogSelections } returns preferences
        every { vm.currentHeroCatalogKeys } answers { keys }
        every { vm.currentHeroCatalogKeys = any() } answers { keys = firstArg() }
        // Stop at the actual first catalog-load mutation, before unrelated IO/UI work.
        every { vm.activeCatalogLoadSignature = any() } answers { observe(keys); throw LoadStarted() }
        return vm
    }

    @Test fun savedHeroSelectionIsReadyBeforeTheFirstCatalogLoad() = runTest {
        val preferences = MutableSharedFlow<List<String>>()
        var observed: List<String>? = null
        val vm = home(preferences) { observed = it }
        val job = launch { try { vm.loadAllCatalogsPipeline(emptyList()) } catch (_: LoadStarted) {} }
        runCurrent()
        assertNull(observed)
        verify(exactly = 0) { vm.catalogsLoadInProgress = any() }
        preferences.emit(listOf("chosen-series", "chosen-movies"))
        job.join()
        assertEquals(listOf("chosen-series", "chosen-movies"), observed)
    }

    @Test fun noSavedSelectionKeepsTheExistingDefaultHeroFallback() = runTest {
        var observed: List<String>? = null
        val vm = home(flowOf(emptyList())) { observed = it }
        try { vm.loadAllCatalogsPipeline(emptyList()) } catch (_: LoadStarted) {}
        assertEquals(emptyList<String>(), observed)
    }

    @Test fun cancellationWhilePreferencesLoadDoesNotStartCatalogWork() = runTest {
        val vm = home(MutableSharedFlow()) { fail("Cancelled load must not start") }
        val job = launch { vm.loadAllCatalogsPipeline(emptyList()) }
        runCurrent()
        job.cancelAndJoin()
        verify(exactly = 0) { vm.activeCatalogLoadSignature = any() }
        verify(exactly = 0) { vm.currentHeroCatalogKeys = any() }
        verify(exactly = 0) { vm.catalogsLoadInProgress = any() }
    }
}
