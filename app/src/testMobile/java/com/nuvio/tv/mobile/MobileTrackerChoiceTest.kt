package com.nuvio.tv.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobileTrackerChoiceTest {
    @Test fun tapsRemainVisibleWhileWritingAndLatestChoiceWins() = runTest {
        val stored = MutableStateFlow<String?>("Trakt")
        val unblock = CompletableDeferred<Unit>()
        val writes = mutableListOf<String?>()
        val choice = MobileTrackerChoice(backgroundScope, MutableStateFlow(1), stored, stored.value) {
            writes += it
            unblock.await()
            stored.value = it
        }
        choice.select("Simkl")
        runCurrent()
        assertEquals("Simkl", choice.selected.value)
        choice.select("MDBList")
        choice.select(null)
        runCurrent()
        assertNull(choice.selected.value)
        unblock.complete(Unit)
        runCurrent()
        assertEquals(listOf("Simkl", null), writes)
        assertNull(stored.value)
        stored.value = "Trakt"
        runCurrent()
        assertEquals("Trakt", choice.selected.value)
    }

    @Test fun failedWriteShowsStoredChoiceAndProfileSwitchDropsQueuedRequest() = runTest {
        val id = MutableStateFlow(1)
        val choice = MobileTrackerChoice(backgroundScope, id, MutableStateFlow("Nuvio"), "Nuvio") { error("offline") }
        choice.select("Trakt")
        runCurrent()
        assertEquals("Nuvio", choice.selected.value)
        assertTrue(choice.hasError.value)
        choice.select("Simkl")
        id.value = 2
        runCurrent()
        assertEquals("Nuvio", choice.selected.value)
        assertFalse(choice.hasError.value)
    }
}
