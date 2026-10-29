package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.tvdb.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TvdbNextUpPendingTest {
    private val effective = tvdbEpisodeOverlay(tvdbFixtureMeta(tvdbFixtureSource()), 78874, tvdbFixtureOrder())
    private val seed = WatchProgress(effective.id, "series", "Firefly", null, null, null,
        "${effective.id}:1:6", 1, 6, null, 1, 1, 1, source = WatchProgress.SOURCE_TRAKT_HISTORY)
    private val repository = mockk<WatchProgressRepository>()
    private val store = mockk<TvdbOrderStore> { coEvery { selected(1, any()) } returns TvdbOrderPreference(78874, "dvd") }
    private val vm = mockk<HomeViewModel>(relaxed = true) {
        every { profileManager } returns mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        every { tvdbOrderStore } returns store
        every { watchProgressRepository } returns repository
        every { layoutPreferenceDataStore } returns mockk<LayoutPreferenceDataStore> { every { nextUpFromFurthestEpisode } returns MutableStateFlow(false) }
        every { cwMetaCache } returns mutableMapOf("series:${effective.id}" to CwMetaSummary(
            effective.id, effective.name, null, null, null, null, emptyList(), null, null, null, null,
            effective.videos.map { CwVideoSummary(it.id, it.title, it.released, it.thumbnail, it.season, it.episode,
                it.overview, episodeIdentity = it.episodeIdentity) }, tvdbOrder = true))
    }

    @Test fun `canonical episode history does not replace the series display name with an ID`() = runTest {
        val video = effective.videos.single { it.title == "Shindig" }
        val native = seed.copy(name = effective.id, videoId = video.id, season = video.season, episode = video.episode,
            tvdbEpisodeId = video.episodeIdentity!!.trackingEpisodeId, tvdbContext = video.tvdbPlaybackContext(), lastWatched = 2)
        every { repository.getAllEpisodeProgress(effective.id) } returns flowOf(mapOf((1 to 4) to native))
        assertEquals("Firefly", vm.selectedOrderSeed(seed)!!.name)
    }

    @Test fun `pending canonical history is awaited and verified identity supplies the selected-order seed`() = runTest {
        val video = effective.videos.single { it.title == "Shindig" }
        val verified = seed.copy(videoId = video.id, season = video.season, episode = video.episode,
            tvdbEpisodeId = video.episodeIdentity!!.trackingEpisodeId, tvdbContext = video.tvdbPlaybackContext())
        every { repository.getAllEpisodeProgress(effective.id) } returns flow { delay(500); emit(mapOf((1 to 4) to verified)) }
        val result = vm.selectedOrderSeed(seed)!!
        assertEquals(1 to 4, result.season to result.episode)
        assertEquals(297994L, result.tvdbEpisodeId)
        assertEquals(seed.contentId, result.contentId)
    }

    @Test fun `unavailable identity returns pending rather than substituting the native numeric seed`() = runTest {
        every { repository.getAllEpisodeProgress(effective.id) } returns flow { awaitCancellation() }
        assertNull(vm.selectedOrderSeed(seed))
        // Pending must not mutate history, progress or the profile's episode-order preference.
        coVerify(exactly = 0) { store.save(any(), any(), any(), any()) }
        verify(exactly = 1) { repository.getAllEpisodeProgress(effective.id) }
        confirmVerified(repository)
    }

    @Test fun `Provider Default bypasses TVDB seed resolution unchanged`() = runTest {
        coEvery { store.selected(1, any()) } returns null
        assertSame(seed, vm.selectedOrderSeed(seed))
        confirmVerified(repository)
    }
    @Test fun `equal completion timestamps follow the selected sequence rather than map insertion order`() = runTest {
        val entries = effective.videos.map { video -> seed.copy(videoId = video.id, season = video.season,
            episode = video.episode, tvdbEpisodeId = video.episodeIdentity!!.trackingEpisodeId,
            tvdbContext = video.tvdbPlaybackContext(), lastWatched = 100) }
        for (ordered in listOf(entries, entries.reversed())) {
            every { repository.getAllEpisodeProgress(effective.id) } returns flowOf(ordered.associateBy { it.season!! to it.episode!! })
            assertEquals(4, vm.selectedOrderSeed(seed.copy(lastWatched = 0))!!.episode)
        }
    }

    @Test fun `a more recent special cannot replace a regular episode as the next-up seed`() = runTest {
        val episode = effective.videos.last()
        val special = episode.copy(id = "special", season = 0, episode = 1,
            episodeIdentity = episode.episodeIdentity!!.copy(tvdbEpisodeId = 999,
                displayed = EpisodeCoordinates(0, 1), canonicalEpisodeIds = listOf(999)))
        val summary = vm.cwMetaCache.getValue("series:${effective.id}")!!
        every { vm.cwMetaCache } returns mutableMapOf("series:${effective.id}" to summary.copy(videos = summary.videos +
            CwVideoSummary(special.id, special.title, special.released, special.thumbnail, 0, 1, null, episodeIdentity = special.episodeIdentity)))
        val regular = seed.copy(videoId = episode.id, season = episode.season, episode = episode.episode,
            tvdbContext = episode.tvdbPlaybackContext(), lastWatched = 100)
        val recentSpecial = regular.copy(videoId = special.id, season = 0, episode = 1,
            tvdbContext = special.tvdbPlaybackContext(), lastWatched = 200)
        every { repository.getAllEpisodeProgress(effective.id) } returns flowOf(mapOf((1 to 4) to regular, (0 to 1) to recentSpecial))
        assertEquals(regular.tvdbContext, vm.selectedOrderSeed(seed.copy(lastWatched = 0))!!.tvdbContext)
    }

    @Test fun `TVDB next-up cache separates identity and order while native keys remain unchanged`() {
        val context = effective.videos.first().tvdbPlaybackContext()!!
        val selected = seed.copy(tvdbContext = context)
        val key = buildNextUpSeedCacheKey(selected, false)
        assertNotEquals(key, buildNextUpSeedCacheKey(selected.copy(tvdbContext = context.copy(orderType = "alternate")), false))
        assertNotEquals(key, buildNextUpSeedCacheKey(selected.copy(tvdbContext = context.copy(episodeIds = listOf(999))), false))
        assertEquals("${seed.contentId}|1|6|unaired=false", buildNextUpSeedCacheKey(seed, false))
    }

}
