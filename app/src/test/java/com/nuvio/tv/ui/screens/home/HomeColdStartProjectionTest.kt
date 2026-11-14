package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.nuvio.tv.data.cw.*
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeColdStartProjectionTest {
    @get:Rule val folder = TemporaryFolder()
    private val progress = (1..3).map { id -> WatchProgress("tt990000$id", "series", "Fixture $id",
        null, null, null, "tt990000$id:1:1", 1, 1, "Episode 1", 40_000, 100_000, System.currentTimeMillis()) }

    @After fun cleanup() = unmockkStatic("androidx.lifecycle.ViewModelKt")

    private fun TestScope.freshHome(states: Flow<CwProjection>): Pair<HomeViewModel, StateFlow<HomeUiState>> {
        mockkStatic("androidx.lifecycle.ViewModelKt")
        val vm = mockk<HomeViewModel>(relaxed = true)
        every { vm.viewModelScope } returns backgroundScope
        every { vm._uiState } returns MutableStateFlow(HomeUiState())
        every { vm._initialCwResolved } returns MutableStateFlow(false)
        every { vm.homeContentActive } returns MutableStateFlow(false)
        every { vm.cwEnrichmentCache.repository.observeProjection() } returns states
        every { vm.traktSettingsDataStore.continueWatchingDaysCap } returns flowOf(30)
        every { vm.traktSettingsDataStore.dismissedNextUpKeys } returns flowOf(emptySet())
        every { vm.layoutPreferenceDataStore.showUnairedNextUp } returns flowOf(false)
        every { vm.layoutPreferenceDataStore.continueWatchingSortMode } returns flowOf(ContinueWatchingSortMode.DEFAULT)
        every { vm.cwEnrichedInProgressOverlay } returns java.util.concurrent.ConcurrentHashMap()
        every { vm.cwEnrichedNextUpOverlay } returns java.util.concurrent.ConcurrentHashMap()
        // The public Home state must not wait for visibility, metadata or tracker history.
        val publicState = vm.createShuffleHomeState(StandardTestDispatcher(testScheduler))
        vm.observeContinueWatchingProjection()
        return vm to publicState
    }

    private suspend fun TestScope.coldStart(legacy: Boolean) {
        val store = CwProjectionStore(folder.root)
        if (legacy) File(folder.root, "inprogress_2.json").writeText(Gson().toJson(progress.map { it.cached() }))
        else store.write(CwMembershipReducer.progress(CwProjection(profileId = 2, source = "TRAKT"), progress))
        val profiles = MutableSharedFlow<Int>(replay = 1)
        val sources = MutableSharedFlow<String>(replay = 1)
        // A stale/default primary profile must not be published or claim the old snapshot.
        val updates = MutableStateFlow<CwProjection?>(CwProjection(profileId = 1, source = "NUVIO_SYNC"))
        val loads = mutableListOf<Pair<Int, String>>()
        val states = scopedCwProjections(profiles, { sources }, { profile, source ->
            loads += profile to source
            store.read(profile, source).also { updates.value = it }
        }, updates)
        val (home, publicState) = freshHome(states)
        runCurrent()
        assertFalse(home._initialCwResolved.value)
        assertTrue(loads.isEmpty())
        profiles.emit(2)
        runCurrent()
        assertFalse(home._initialCwResolved.value)
        assertTrue(loads.isEmpty())
        sources.emit("TRAKT")
        runCurrent() // no grace timer, remote refresh, metadata, or visible-Home trigger
        assertEquals(listOf(2 to "TRAKT"), loads)
        assertTrue(home._initialCwResolved.value)
        assertEquals(progress.map { it.contentId }.toSet(), publicState.value.continueWatchingItems.map { it.contentId() }.toSet())
        assertEquals(3, CwMembershipReducer.visible(updates.value!!, 30, emptySet(), false, System.currentTimeMillis()).members.size)
        // Out-of-scope emissions never replace supported members with an empty projection.
        updates.value = CwProjection(profileId = 1, source = "NUVIO_SYNC")
        runCurrent()
        assertEquals(3, publicState.value.continueWatchingItems.size)
        assertEquals(3, CwProjectionStore(folder.root).read(2, "TRAKT").members.size)
        if (legacy) assertTrue(File(folder.root, "inprogress_2.json").exists())
    }

    @Test fun storedProjectionAppearsOnFreshHomeWithoutOtherTriggers() = runTest { coldStart(false) }
    @Test fun migratedLegacySnapshotAppearsOnFreshHomeAfterScopeLoads() = runTest { coldStart(true) }

    @Test fun staleEmptyDecorationCannotReplaceNewDurableCards() {
        val cards = progress.map { it.cached().homeCard() }
        val state = HomeUiState(continueWatchingItems = cards)
        val stale = HomeCwDecoration(emptyList<ContinueWatchingItem>() to emptyList(), HomeUiState())
        assertEquals(cards, mergeHomeCwDecoration(state, stale).continueWatchingItems)
    }
}

