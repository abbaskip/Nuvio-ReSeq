package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.ChannelGroup
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GuideFocusSelectionTest {
    private val groups = listOf(ChannelGroup("a", "A", 1), ChannelGroup("b", "B", 1), ChannelGroup("c", "C", 1))
    private fun state(id: String) = LiveTvUiState(groups = groups, selectedGroupId = id)

    @Test fun waitsForRequestedPlaylistInsteadOfFocusingOldGroup() = runTest {
        val ui = MutableStateFlow(state("a"))
        val requested = MutableStateFlow<String?>("b")
        val result = async { awaitGuideSelection(ui, requested) }
        runCurrent()
        assertFalse(result.isCompleted)
        ui.value = state("b")
        runCurrent()
        assertEquals("b", result.await()?.selectedGroupId)
    }

    @Test fun rapidNewSelectionSupersedesAnOlderPendingModel() = runTest {
        val ui = MutableStateFlow(state("a"))
        val requested = MutableStateFlow<String?>("b")
        val result = async { awaitGuideSelection(ui, requested) }
        runCurrent()
        requested.value = "c"
        ui.value = state("b")
        runCurrent()
        assertFalse(result.isCompleted)
        ui.value = state("c")
        runCurrent()
        assertEquals("c", result.await()?.selectedGroupId)
    }

    @Test fun timeoutLeavesFocusInPlaceInsteadOfSelectingAStaleGroup() = runTest {
        val ui = MutableStateFlow(state("a"))
        val requested = MutableStateFlow<String?>("b")
        val result = async { awaitGuideSelection(ui, requested, timeoutMs = 1000) }
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertNull(result.await())
    }

    @Test fun removedGroupAllowsTheResolvedFallback() = runTest {
        assertEquals("a", awaitGuideSelection(MutableStateFlow(state("a")), MutableStateFlow("removed"))?.selectedGroupId)
    }
}
