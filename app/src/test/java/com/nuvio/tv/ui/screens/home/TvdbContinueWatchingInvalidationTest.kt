package com.nuvio.tv.ui.screens.home

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class TvdbContinueWatchingInvalidationTest {
    @Test fun `Office invalidation never empties the current row or unrelated metadata`() {
        val vm = mockk<HomeViewModel>(relaxed = true)
        val office = "tt0386676"
        val progress = com.nuvio.tv.domain.model.WatchProgress("other", "series", "Other", null, null, null,
            "other:1:1", 1, 1, "Pilot", 10, 100, 1)
        val state = MutableStateFlow(HomeUiState(continueWatchingItems = listOf(ContinueWatchingItem.InProgress(progress))))
        val cache = mutableMapOf<String, CwMetaSummary?>("series:$office" to null, "series:other" to null)
        val refresh = MutableStateFlow(0)
        every { vm._uiState } returns state
        every { vm.cwMetaCache } returns cache
        every { vm.cwMetaNegativeCacheTimestamps } returns mutableMapOf()
        every { vm.cwBadgeEpisodeCache } returns mutableMapOf()
        every { vm.cwBadgeNextSeasonMs } returns mutableMapOf()
        every { vm.cwTmdbIdCache } returns mutableMapOf()
        every { vm.cwNextUpResolutionCache } returns mutableMapOf()
        every { vm.cwNextUpNegativeCacheTimestamps } returns java.util.concurrent.ConcurrentHashMap()
        every { vm.cwEnrichedNextUpOverlay } returns java.util.concurrent.ConcurrentHashMap()
        every { vm.cwEnrichedInProgressOverlay } returns java.util.concurrent.ConcurrentHashMap()
        every { vm.cwLastProcessedNextUpContentIds } returns mutableSetOf()
        every { vm.cwProcessedOlderSeedContentIds } returns mutableSetOf()
        every { vm.discoveredOlderNextUpItems } returns mutableListOf()
        every { vm.cwPipelineRefreshTrigger } returns refresh
        vm.invalidateTvdbContinueWatching(setOf(office))
        assertEquals(setOf("series:other"), cache.keys)
        assertEquals(listOf(ContinueWatchingItem.InProgress(progress)), state.value.continueWatchingItems)
        assertEquals(1, refresh.value)
    }
}
