package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleHomeWorkTest {
    @Test fun `Sports stays independent and leaving Home cancels metadata children`() = runTest {
        val home = MutableStateFlow(false)
        val playing = MutableStateFlow(false)
        var runs = 0
        var children = 0
        val job = backgroundScope.launch {
            runVisibleHomeWork(home, playing) {
                runs++
                launch { children++; try { awaitCancellation() } finally { children-- } }
                awaitCancellation()
            }
        }
        runCurrent()
        assertEquals(0, runs)
        home.value = true
        runCurrent()
        assertEquals(1, children)
        home.value = false
        runCurrent()
        assertEquals(0, children)
        assertEquals(1, runs)
        home.value = true
        runCurrent()
        assertEquals(2, runs)
        playing.value = true
        runCurrent()
        assertEquals(0, children)
        playing.value = false
        runCurrent()
        assertEquals(3, runs)
        job.cancel()
        runCurrent()
        assertEquals(0, children)
    }

    @Test fun `returning Home uses latest snapshot and an unchanged snapshot can resume`() = runTest {
        val home = MutableStateFlow(true)
        val playing = MutableStateFlow(false)
        val progress = MutableStateFlow("first")
        val seen = mutableListOf<String>()
        backgroundScope.launch {
            runVisibleHomeWork(home, playing) {
                progress.collectLatest { seen += it; awaitCancellation() }
            }
        }
        runCurrent()
        home.value = false
        runCurrent()
        progress.value = "latest"
        runCurrent()
        assertEquals(listOf("first"), seen)
        home.value = true
        runCurrent()
        playing.value = true
        runCurrent()
        playing.value = false
        runCurrent()
        assertEquals(listOf("first", "latest", "latest"), seen)
    }
}
