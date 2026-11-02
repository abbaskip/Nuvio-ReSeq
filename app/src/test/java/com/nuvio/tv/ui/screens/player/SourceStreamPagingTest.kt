package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test

class SourceStreamPagingTest {
    @Test fun switchingAddonResetsThePageAndExpansionUsesOnlyThatAddon() {
        val all = streams("A", 250) + streams("B", 130)
        val state = PlayerUiState().withSourceStreams(all).withExpandedSourcePage()
            .withSourceAddonFilter("B")
        assertEquals(100, state.sourceFilteredStreams.size)
        assertEquals(130, state.sourceFilterFullList.size)
        val expanded = state.withExpandedSourcePage()
        assertEquals(130, expanded.sourceFilteredStreams.size)
        assertTrue(expanded.sourceFilteredStreams.all { it.addonName == "B" })
        assertSame(expanded, expanded.withExpandedSourcePage())
    }

    @Test fun preparedLinksRetainTheExpandedPageAndUpdateTheNextPage() {
        val state = PlayerUiState().withSourceStreams(streams("A", 250)).withExpandedSourcePage()
        val prepared = state.withSourceStreams(state.sourceAllStreams.map { it.copy(url = "https://prepared.example/video") })
        assertEquals(200, prepared.sourceFilteredStreams.size)
        assertEquals(250, prepared.withExpandedSourcePage().sourceFilteredStreams.size)
        assertTrue(prepared.withExpandedSourcePage().sourceFilteredStreams.all { it.url == "https://prepared.example/video" })
        assertEquals(250, prepared.withSourceAddonFilter(null).sourceFilterFullList.size)
    }

    @Test fun shrinkingResultsClampBothThePageAndCache() {
        val state = PlayerUiState().withSourceStreams(streams("A", 250)).withExpandedSourcePage()
        val shrunk = state.withSourceStreams(streams("A", 3))
        assertEquals(3, shrunk.sourceFilteredStreams.size)
        assertSame(shrunk.sourceFilterFullList, shrunk.sourceFilteredStreams)
        val empty = shrunk.withSourceAddonFilter("missing")
        assertTrue(empty.sourceFilterFullList.isEmpty())
        assertTrue(empty.sourceFilteredStreams.isEmpty())
    }

    @Test fun backgroundPreparationRetriesAgainstTheLatestAddonSelection() {
        val states = MutableStateFlow(PlayerUiState().withSourceStreams(streams("A", 150) + streams("B", 130)))
        val prepared = CountDownLatch(1)
        val filterChanged = CountDownLatch(1)
        val firstUpdate = AtomicBoolean(true)
        val worker = thread(isDaemon = true) {
            states.update { current ->
                val next = current.withSourceStreams(current.sourceAllStreams.map { it.copy(url = "https://prepared.example/video") })
                if (firstUpdate.compareAndSet(true, false)) {
                    prepared.countDown()
                    check(filterChanged.await(5, TimeUnit.SECONDS))
                }
                next
            }
        }
        try {
            assertTrue(prepared.await(5, TimeUnit.SECONDS))
            states.update { it.withSourceAddonFilter("B") }
        } finally {
            filterChanged.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        val final = states.value.withExpandedSourcePage()
        assertEquals("B", final.sourceSelectedAddonFilter)
        assertEquals(130, final.sourceFilteredStreams.size)
        assertTrue(final.sourceFilterFullList.all { it.addonName == "B" && it.url == "https://prepared.example/video" })
        assertEquals(final.sourceFilterFullList, final.sourceFilteredStreams)
    }

    private fun streams(addon: String, count: Int) = List(count) { index ->
        Stream(name = "$addon-$index", title = null, description = null, url = "https://example.test/$addon/$index",
            ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
            addonName = addon, addonLogo = null)
    }
}
