package com.nuvio.tv.data.cw

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.inject.Provider

class ContinueWatchingRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val profile = MutableStateFlow(1)
    private val source = MutableStateFlow(WatchProgressSource.TRAKT)
    private val remote = MutableStateFlow(emptyList<WatchProgress>())
    private val seeds = MutableStateFlow(emptyList<WatchProgress>())
    private val profiles = mockk<ProfileManager> { every { resolvedActiveProfileId } returns profile; every { activeProfileId } returns profile }
    private val settings = mockk<TraktSettingsDataStore> {
        coEvery { getWatchProgressSource(any()) } answers { source.value }
        every { watchProgressSource } returns source
    }
    private val progress = mockk<WatchProgressRepository> {
        every { allProgress } returns remote
        every { observeNextUpSeeds() } returns seeds
        every { isDroppedShow(any()) } returns false
    }
    private fun repository() = ContinueWatchingRepository(
        mockk<Context> { every { filesDir } returns folder.root }, profiles, settings, Provider { progress })
    private fun p(episode: Int = 1, time: Long = 1_000) = WatchProgress("show", "series", "Show", null, null,
        null, "show:1:$episode", 1, episode, null, 20, 100, time)

    @Test fun `completion without Home survives stale return refreshes and process restart`() = runTest {
        val owner = repository()
        owner.snapshot()
        owner.committedProgress(1, listOf(p()))
        val staleMetadataLease = owner.snapshot().lease
        owner.committedProgress(1, listOf(p(time = 2_000).copy(position = 100)))
        remote.value = listOf(p())
        repeat(3) { owner.refreshProjection() } // close, external result absent, foreground resume
        owner.enrich(staleMetadataLease, inProgress = listOf(p().cached()))
        assertTrue(owner.snapshot().inProgress.isEmpty())
        assertTrue(owner.snapshot().nextUp.single().chooseEpisode)
        val restored = repository().snapshot()
        assertTrue(restored.nextUp.single().chooseEpisode)
        assertTrue(restored.members.getValue("show").progress!!.isCompleted())
    }

    @Test fun `profile source and order changes reject in flight enrichment without clearing old namespace`() = runTest {
        val owner = repository()
        owner.snapshot()
        owner.committedProgress(1, listOf(p()))
        val original = owner.snapshot()
        profile.value = 2
        assertTrue(owner.snapshot().members.isEmpty())
        owner.enrich(original.lease, inProgress = listOf(p().cached()))
        assertTrue(owner.snapshot().members.isEmpty())
        profile.value = 1
        assertEquals(listOf(p().cached()), owner.snapshot().inProgress)
        source.value = WatchProgressSource.SIMKL
        assertTrue(owner.snapshot().members.isEmpty())
        source.value = WatchProgressSource.TRAKT
        val beforeOrder = owner.snapshot()
        owner.invalidate(1, setOf("show"))
        owner.enrich(beforeOrder.lease, inProgress = listOf(p().cached().copy(name = "Stale order")))
        assertEquals("Show", owner.snapshot().inProgress.single().name)
    }

    @Test fun `inactive profile completion writes only that namespace`() = runTest {
        val owner = repository()
        owner.snapshot()
        profile.value = 2
        owner.snapshot()
        owner.committedProgress(1, listOf(p().copy(position = 100)))
        assertTrue(owner.snapshot().members.isEmpty())
        profile.value = 1
        assertTrue(owner.snapshot().nextUp.single().chooseEpisode)
    }

    @Test fun `restart replays committed progress after crash before projection write`() = runTest {
        val owner = repository()
        owner.snapshot()
        owner.committedProgress(1, listOf(p()))
        // Represents the existing progress store's commit; no CW callback ran before death.
        remote.value = listOf(p(time = 2_000).copy(position = 100))
        val restarted = repository()
        restarted.refreshProjection()
        assertTrue(restarted.snapshot().inProgress.isEmpty())
        assertTrue(restarted.snapshot().nextUp.single().chooseEpisode)
    }

    @Test fun `new support wins over a delayed successful sync deletion proof`() = runTest {
        source.value = WatchProgressSource.NUVIO_SYNC
        val owner = repository()
        val refreshStarted = owner.snapshot().lease
        owner.committedProgress(1, listOf(p()))
        owner.pruneAfterSync(refreshStarted, emptySet())
        assertEquals(setOf("show"), owner.snapshot().members.keys)
        owner.pruneAfterSync(owner.snapshot().lease, emptySet())
        assertTrue(owner.snapshot().members.isEmpty())
    }

    @Test fun `removing the only watched episode drops the card and rejects its stale replay`() = runTest {
        val owner = repository()
        owner.snapshot()
        owner.committedProgress(1, listOf(p().copy(position = 100)))
        owner.historyRemoved(1, "show", setOf(1 to 1))
        remote.value = listOf(p().copy(position = 100))
        owner.refreshProjection()
        assertTrue(owner.snapshot().members.isEmpty())
        assertTrue(repository().snapshot().members.isEmpty())
    }

    @Test fun `latest unwatch rehydrates older committed history and persists its recomputed offer`() = runTest {
        val owner = repository()
        owner.snapshot()
        val earlier = p(4, 1000).copy(position = 100, videoId = "show")
        val latest = p(5, 2000).copy(position = 100, videoId = "show")
        owner.committedProgress(1, listOf(earlier, latest))
        val oldLease = owner.snapshot().lease
        owner.historyRemoved(1, "show", setOf(1 to 5))
        seeds.value = listOf(earlier)
        remote.value = listOf(latest) // delayed provider replay of the revoked episode
        owner.refreshProjection()
        assertEquals(earlier, owner.snapshot().members.getValue("show").progress)
        val offer = earlier.seriesChoice().copy(videoId = "show:1:5", season = 1, episode = 5, chooseEpisode = false)
        owner.enrich(oldLease, nextUp = listOf(offer.copy(episode = 6)))
        assertTrue(owner.snapshot().nextUp.single().chooseEpisode)
        owner.enrich(owner.snapshot().lease, nextUp = listOf(offer))
        assertEquals(offer, repository().snapshot().nextUp.single())
    }

    @Test fun `selected TVDB order without verified identity retains series choice until compatible progress arrives`() = runTest {
        val orders = mockk<com.nuvio.tv.data.tvdb.TvdbOrderStore> {
            coEvery { exportSelections(1) } returns mapOf("alias:show" to "123", "order:123" to "dvd")
        }
        val owner = ContinueWatchingRepository(mockk<Context> { every { filesDir } returns folder.root },
            profiles, settings, Provider { progress }, tvdbOrders = orders)
        owner.snapshot()
        owner.committedProgress(1, listOf(p()))
        assertTrue(owner.snapshot().inProgress.single().chooseEpisode)
        val identified = p(time = 2_000).copy(tvdbContext = com.nuvio.tv.domain.model.TvdbPlaybackContext(
            123, "dvd", 44, 2, 5, listOf(44), "show:1:1"))
        owner.committedProgress(1, listOf(identified))
        assertFalse(owner.snapshot().inProgress.single().chooseEpisode)
        assertEquals(identified.tvdbContext, owner.snapshot().inProgress.single().tvdbContext)
    }
}
