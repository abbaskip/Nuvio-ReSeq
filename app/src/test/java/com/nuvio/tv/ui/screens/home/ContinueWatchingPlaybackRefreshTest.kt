package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingPlaybackRefreshTest {
    @Test fun `closing player refreshes unchanged provider snapshot and releases latest held update`() = runTest {
        val snapshots = MutableStateFlow("cached")
        val open = MutableStateFlow(false)
        val rendered = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshots.holdContinueWatchingWhilePlayerOpen(open).collect { rendered += it }
        }
        runCurrent()
        open.value = true
        runCurrent()
        open.value = false
        runCurrent()
        assertEquals(listOf("cached", "cached"), rendered)
        open.value = true
        runCurrent()
        snapshots.value = "new progress"
        runCurrent()
        assertEquals(listOf("cached", "cached"), rendered)
        open.value = false
        runCurrent()
        assertEquals(listOf("cached", "cached", "new progress"), rendered)
    }
}
