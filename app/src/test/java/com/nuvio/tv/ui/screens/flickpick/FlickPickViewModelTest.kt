package com.nuvio.tv.ui.screens.flickpick

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.DiscoveryPage
import com.nuvio.tv.data.repository.DiscoveryRepository
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlickPickViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profile = MutableStateFlow(1)
    private val watched = MutableStateFlow(emptySet<String>())
    private val tabs = MutableStateFlow(listOf(LibraryListTab("mine", "Mine", LibraryListTab.Type.PERSONAL),
        LibraryListTab("other", "Other", LibraryListTab.Type.PERSONAL)))
    private val entries = MutableStateFlow(emptyList<LibraryEntry>())
    private val discovery = mockk<DiscoveryRepository>()
    private val aliases = mockk<TmdbService>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { discovery.genres(false, "en") } returns emptyList()
    }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun model() = FlickPickViewModel(discovery,
        mockk<TmdbSettingsDataStore> { every { settings } returns MutableStateFlow(TmdbSettings(language = "en")) },
        mockk<ProfileManager> { every { activeProfileId } returns profile },
        mockk<TmdbCredentialStore> { every { changes } returns MutableStateFlow(0L); every { revision(any()) } returns 0L },
        aliases,
        mockk<WatchProgressRepository> { every { observeWatchedMovieIds() } returns watched },
        mockk<LibraryRepository> {
            every { membershipListTabs } returns tabs
            coEvery { movieListItems(any(), any()) } coAnswers {
                val key = firstArg<LibraryListTab>().key
                entries.value.filter { it.type == "movie" && (key == com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY || key in it.listKeys) }
            }
        }, mockk<FlickPickHomeLists> { every { choices } returns MutableStateFlow(emptyList()) },
        mockk<FlickPickHistory>(relaxUnitFun = true) { coEvery { recent(any()) } returns emptyList() })

    private fun entry(id: String, list: String = "mine", type: String = "movie") =
        LibraryEntry(id = id, type = type, name = id, poster = null, background = null, logo = null,
            description = null, releaseInfo = null, imdbRating = 7f, genres = emptyList(), addonBaseUrl = null,
            listKeys = setOf(list))

    @Test fun listsPlayOnlyUnwatchedMoviesFromTheChosenListAndKeepNativeVideoIds() = runTest(dispatcher) {
        watched.value = setOf("tmdb:10", "imdb:tt20")
        entries.value = listOf(entry("tt1"), entry("tmdb:2", "other").copy(imdbId = "tt20"),
            entry("tt3", "other"), entry("series", "other", "series"), entry("native:server-movie", "other"))
        coEvery { aliases.imdbToTmdb("tt3", "movie") } returns 10
        val vm = model()
        runCurrent()
        vm.mode(true)
        runCurrent()
        vm.list("other")
        vm.play()
        advanceUntilIdle()
        assertEquals("native:server-movie", vm.takePick()?.first?.id)
        assertNull(vm.takePick())
        coVerify(exactly = 0) { discovery.discover(any(), any(), any(), any()) }

        entries.value = entries.value.filterNot { it.id == "native:server-movie" }
        runCurrent()
        vm.play()
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        assertNull(vm.takePick())
    }

    @Test fun localLibraryWorksWithoutProviderTabsOrADiscoveryKey() = runTest(dispatcher) {
        tabs.value = emptyList()
        entries.value = listOf(entry("native:film").copy(listKeys = emptySet()))
        val vm = model()
        runCurrent()
        vm.mode(true)
        runCurrent()
        assertNull(vm.state.value.selectedList)
        vm.list(vm.state.value.lists.single().key)
        vm.play()
        advanceUntilIdle()
        assertEquals("native:film", vm.takePick()?.first?.id)
    }

    @Test fun filteringAnImdbOnlyHistoryChecksDiscoveredTmdbMoviesAndHandsOffOnce() = runTest(dispatcher) {
        watched.value = setOf("imdb:tt12")
        val seen = entry("tmdb:12").toMetaPreview()
        val unseen = entry("tmdb:13").toMetaPreview()
        coEvery { discovery.discover(any(), "en", 1, emptySet()) } returns DiscoveryPage(listOf(seen), true, 30, 2)
        coEvery { discovery.discover(any(), "en", 2, emptySet()) } returns DiscoveryPage(listOf(unseen), false, 30, 2)
        coEvery { discovery.resolveImdbId(12, false) } returns "tt12"
        coEvery { discovery.resolveImdbId(13, false) } returns "tt13"
        val vm = model()
        runCurrent()
        assertFalse(vm.state.value.fromList)
        vm.play()
        vm.play()
        advanceUntilIdle()
        val pick = vm.takePick()
        assertEquals("tt13", pick?.first?.imdbId)
        assertEquals(1, pick?.second)
        assertNull(vm.takePick())
        coVerify(exactly = 1) { discovery.discover(any(), "en", 1, emptySet()) }
    }

    @Test fun sameMaximumAppliesToDiscoveryAndExplicitlySelectedLists() = runTest(dispatcher) {
        val system = MovieRatingSystem.forRegion("AU", emptyMap())
        val cap = MovieAgeLimit(system, "PG")
        coEvery { discovery.ratingSystem("AU") } returns system
        coEvery { discovery.allowsMovie(10, null, cap) } returns false
        coEvery { discovery.allowsMovie(11, null, cap) } returns true
        coEvery { discovery.discover(any(), "en", 1, emptySet(), "AU" to "PG") } returns
            DiscoveryPage(listOf(entry("tmdb:10").toMetaPreview(), entry("tmdb:11").toMetaPreview()), false, 2)
        coEvery { discovery.resolveImdbId(11, false) } returns "tt11"
        val vm = model()
        runCurrent()
        vm.configureRatings("AU")
        vm.maximumRating("PG")
        vm.play()
        advanceUntilIdle()
        assertEquals("tmdb:11", vm.takePick()?.first?.id)
        entries.value = listOf(entry("tt12", "other"))
        coEvery { discovery.allowsMovie(null, "tt12", cap) } returns false
        vm.mode(true)
        runCurrent()
        vm.list("other")
        vm.play()
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        vm.maximumRating("")
        vm.play()
        advanceUntilIdle()
        assertEquals("tt12", vm.takePick()?.first?.id)
    }

    @Test fun switchingProfilesDuringAPickCannotStartPlaybackForTheNextProfile() = runTest(dispatcher) {
        val response = CompletableDeferred<DiscoveryPage>()
        coEvery { discovery.discover(any(), any(), any(), any()) } coAnswers { response.await() }
        val vm = model()
        runCurrent()
        vm.play()
        runCurrent()
        assertTrue(vm.state.value.busy)
        profile.value = 2
        runCurrent()
        response.complete(DiscoveryPage(listOf(entry("tmdb:1").toMetaPreview()), false, 1))
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertNull(vm.takePick())
        coVerify(exactly = 0) { discovery.resolveImdbId(any(), any()) }
    }

    @Test fun cancellingTheSearchAllowsAnImmediateNewPick() = runTest(dispatcher) {
        val pending = CompletableDeferred<DiscoveryPage>()
        coEvery { discovery.discover(any(), any(), any(), any()) } coAnswers { pending.await() }
        val vm = model()
        runCurrent()
        vm.play()
        runCurrent()
        vm.cancelPick()
        assertFalse(vm.state.value.busy)
        pending.complete(DiscoveryPage(listOf(entry("tmdb:1").toMetaPreview()), false, 1))
        coEvery { discovery.resolveImdbId(1, false) } returns "tt1"
        runCurrent()
        assertNull(vm.takePick())
        vm.play()
        advanceUntilIdle()
        assertEquals("tt1", vm.takePick()?.first?.imdbId)
    }
}
