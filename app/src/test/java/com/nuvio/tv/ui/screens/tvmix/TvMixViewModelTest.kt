package com.nuvio.tv.ui.screens.tvmix

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.EpisodeShufflePlayback
import com.nuvio.tv.core.player.PlaybackShuffleState
import com.nuvio.tv.core.player.TvMixSession
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.repository.DiscoveryRepository
import com.nuvio.tv.data.local.TvMixStore
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.net.URLDecoder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TvMixViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profile = MutableStateFlow(1)
    private val episodes = (1..4).map { Video("tt123:1:$it", "Episode $it", "2020-01-01", null,
        season = 1, episode = it, overview = null) }
    private val mix = TvMix("mix", "Sitcoms", listOf(TvMixShow("tt123", "Example")))
    private val saved = MutableStateFlow(listOf(mix))
    private val store = mockk<TvMixStore> {
        every { mixes } returns saved
        every { observe(any()) } returns saved
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns profile }
    private val meta = mockk<Meta>(relaxed = true) {
        every { id } returns "tt123"
        every { apiType } returns "series"
        every { videos } returns episodes
        every { language } returns "en"
    }
    private val metadata = mockk<MetaRepository> {
        every { getMetaFromAllAddons(any(), any(), any()) } returns flowOf(NetworkResult.Success(meta))
    }
    private val shuffle = mockk<EpisodeShufflePlayback> {
        every { observe(any(), any(), any()) } returns flowOf(PlaybackShuffleState(EpisodeShuffleSettings(), emptySet(), emptyMap()))
    }
    private val progress = mockk<WatchProgressRepository> { every { isWatchedByVideoId(any(), any()) } returns false }
    private val discovery = mockk<DiscoveryRepository>()
    private val session = TvMixSession()
    private lateinit var vm: TvMixViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        vm = TvMixViewModel(store, profiles, metadata, shuffle, progress, discovery, session)
    }
    @After fun teardown() { vm.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun `preview is stable play starts session and autoplay advances without prompt`() = runTest(dispatcher) {
        vm.open("mix"); runCurrent()
        val first = vm.state.value.selection!!
        vm.open("mix"); runCurrent()
        assertEquals(first, vm.state.value.selection)
        vm.play()
        assertTrue(session.matches(1, "tt123", first.video.id))
        vm.completed(true); runCurrent()
        assertTrue(vm.state.value.autoStart)
        assertFalse(vm.state.value.continuePrompt)
        assertNotEquals(first.video.id, vm.state.value.selection!!.video.id)
        vm.play()
        assertFalse(vm.state.value.autoStart)
        assertNotNull(session.playing.value)
    }

    @Test fun `restored catalog shows resolve full episodes without a warm metadata cache`() = runTest(dispatcher) {
        val source = "https://fixture.invalid"
        saved.value = listOf(mix.copy(shows = listOf(mix.shows.single().copy(addonBaseUrl = source))))
        every { metadata.getMetaFromAllAddons("series", "tt123", source) } returns
            flowOf(NetworkResult.Error("Source addon sufficient", NetworkResult.SOURCE_SUFFICIENT_CODE))
        vm.open("mix"); runCurrent()
        assertNotNull(vm.state.value.selection)
        assertNull(vm.state.value.error)
        verify(exactly = 0) { metadata.getMetaFromAllAddons("series", "tt123", source) }
    }

    @Test fun `autoplay off previews next episode remix does not play and No ends session`() = runTest(dispatcher) {
        vm.open("mix"); runCurrent()
        val completed = vm.state.value.selection!!.video.id
        vm.play()
        vm.completed(false); runCurrent()
        assertTrue(vm.state.value.continuePrompt)
        assertFalse(vm.state.value.autoStart)
        assertNull(session.playing.value)
        val next = vm.state.value.selection!!.video.id
        vm.remix(); runCurrent()
        assertNotEquals(next, vm.state.value.selection!!.video.id)
        assertNotEquals(completed, vm.state.value.selection!!.video.id)
        repeat(30) {
            val preview = vm.state.value.selection!!.video.id
            vm.remix(); runCurrent()
            assertNotEquals(preview, vm.state.value.selection!!.video.id)
            assertNotEquals(completed, vm.state.value.selection!!.video.id)
        }
        assertTrue(vm.state.value.continuePrompt)
        assertNull(session.playing.value)
        vm.end(); runCurrent()
        assertNull(session.playing.value)
        assertFalse(vm.state.value.continuePrompt)
        assertNull(vm.state.value.mixId)
    }

    @Test fun `manual exit and profile switching never prompt or continue`() = runTest(dispatcher) {
        vm.open("mix"); runCurrent(); vm.play(); vm.end(); runCurrent()
        assertNull(session.playing.value)
        assertFalse(vm.state.value.continuePrompt)
        vm.open("mix"); runCurrent(); vm.play()
        profile.value = 2; runCurrent()
        assertNull(session.playing.value)
        assertNull(vm.state.value.selection)
    }

    @Test fun `unwatched filter uses existing local and remote watched state`() = runTest(dispatcher) {
        saved.value = listOf(mix.copy(unwatchedOnly = true))
        every { shuffle.observe(any(), any(), any()) } returns flowOf(PlaybackShuffleState(
            EpisodeShuffleSettings(), setOf(1 to 1, 1 to 2), emptyMap()))
        every { progress.isWatchedByVideoId("tt123:1:3", 3) } returns true
        vm.open("mix"); runCurrent()
        assertEquals(4, vm.state.value.selection!!.video.episode)
        saved.value = listOf(mix)
        every { shuffle.observe(any(), any(), any()) } returns flowOf(PlaybackShuffleState(
            EpisodeShuffleSettings(), (1..4).map { 1 to it }.toSet(), emptyMap()))
        vm.remix(); runCurrent()
        assertNotNull(vm.state.value.selection)
    }

    @Test fun `missing metadata terminates with explicit retry and no active playback`() = runTest(dispatcher) {
        every { metadata.getMetaFromAllAddons(any(), any(), any()) } returns flowOf(NetworkResult.Error("Unavailable"))
        vm.open("mix"); runCurrent()
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.error)
        assertNull(vm.play())
        verify(exactly = 1) { metadata.getMetaFromAllAddons(any(), any(), any()) }
    }

    @Test fun `TVDB selected source and tracker identity survive ordinary stream navigation`() = runTest(dispatcher) {
        val identity = EpisodeIdentity(901, 88, "dvd", EpisodeCoordinates(2, 7), EpisodeCoordinates(3, 4),
            streamVideoId = "tt123:2:7", streamCoordinates = EpisodeCoordinates(2, 7),
            canonicalEpisodeIds = listOf(501, 502), contentRuntimeMinutes = mapOf(501L to 22, 502L to 22),
            usesSelectedOrderForStreams = true)
        val episode = episodes.first().copy(id = "tt123:2:7", season = 2, episode = 7, episodeIdentity = identity)
        every { meta.videos } returns listOf(episode)
        vm.open("mix"); runCurrent()
        val selection = vm.play()!!
        assertSame(episode, selection.video)
        val route = selection.streamRoute(1)
        assertTrue(route.contains("season=2&episode=7"))
        assertTrue(route.contains("profileId=1"))
        assertTrue(route.contains("manualSelection=false"))
        assertTrue(route.contains("startFromBeginning=true"))
        val context = TvdbPlaybackContext.decode(URLDecoder.decode(route.substringAfter("tvdbContext="), "UTF-8"))!!
        assertEquals("dvd", context.orderType)
        assertEquals("tt123:2:7", context.requestVideoId)
        assertEquals(listOf(501L, 502L), context.episodeIds)
        assertEquals(44, context.expectedRuntimeMinutes)
    }
    @Test fun `Discovery shows use the existing profile local runtime identity lookup`() = runTest(dispatcher) {
        coEvery { discovery.imdbId(123, true) } returns "tt123"
        coEvery { store.add(1, "mix", any()) } just Runs
        val item = mix.preview().copy(id = "tmdb:123", type = ContentType.SERIES, rawType = "series", imdbId = null)
        vm.add(item, "mix", "") { }
        runCurrent()
        coVerify(exactly = 1) { store.add(1, "mix", match { it.id == "tt123" && it.imdbId == "tt123" }) }
        coVerify(exactly = 1) { discovery.imdbId(123, true) }
    }

}
