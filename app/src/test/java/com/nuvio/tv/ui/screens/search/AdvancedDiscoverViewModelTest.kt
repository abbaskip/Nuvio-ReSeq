package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SavedDiscoveriesDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.DiscoveryException
import com.nuvio.tv.data.repository.DiscoveryFailure
import com.nuvio.tv.data.repository.DiscoveryPage
import com.nuvio.tv.data.repository.DiscoveryRepository
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedDiscovery
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedDiscoverViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<DiscoveryRepository>()
    private val saved = mockk<SavedDiscoveriesDataStore>()
    private val profiles = MutableStateFlow(1)
    private val movies = MutableStateFlow<Set<String>>(emptySet())
    private val shows = MutableStateFlow<Set<String>>(emptySet())
    private val savedFlow = MutableStateFlow<List<SavedDiscovery>>(emptyList())
    private val credentialChanges = MutableStateFlow(0L)
    private val credentials = mockk<TmdbCredentialStore> {
        every { changes } returns credentialChanges
        every { revision(any()) } returns 0L
    }
    private val layout = mockk<LayoutPreferenceDataStore> {
        every { posterCardWidthDp } returns MutableStateFlow(126)
        every { posterCardCornerRadiusDp } returns MutableStateFlow(12)
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { saved.discoveries } returns savedFlow
        coEvery { repository.genres(any(), any()) } returns emptyList()
        coEvery { repository.languages() } returns emptyList()
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun viewModel() = AdvancedDiscoverViewModel(repository, saved,
        mockk<TmdbSettingsDataStore> { every { settings } returns MutableStateFlow(TmdbSettings(language = "en")) },
        mockk<ProfileManager> { every { activeProfileId } returns this@AdvancedDiscoverViewModelTest.profiles },
        mockk<WatchProgressRepository> { every { observeWatchedMovieIds() } returns movies },
        mockk<WatchedSeriesStateHolder> { every { fullyWatchedSeriesIds } returns shows }, credentials, layout)

    @Test
    fun `title filter is explicitly applied saved restored reset and cleared across profiles`() = runTest(dispatcher) {
        coEvery { repository.discover(any(), any(), any()) } returns DiscoveryPage(emptyList(), false, 0)
        coEvery { saved.save(any(), any()) } returns Unit
        val vm = viewModel()
        runCurrent()
        val query = DiscoveryQuery(titleContains = "Alien", minimumRating = "7", hideWatched = true)
        vm.edit(query)
        coVerify(exactly = 0) { repository.discover(any(), any(), any()) }
        vm.save("Alien films", false)
        advanceUntilIdle()
        coVerify { saved.save(match { it.query == query }, 1) }
        vm.openSaved(SavedDiscovery("one", "Alien films", query))
        advanceUntilIdle()
        assertEquals(query, vm.state.value.applied)
        coVerify { repository.discover(query, "en", 1) }
        vm.reset()
        assertEquals("", vm.state.value.draft.titleContains)
        vm.edit(query)
        profiles.value = 2
        advanceUntilIdle()
        assertEquals("", vm.state.value.draft.titleContains)
        assertNull(vm.state.value.applied)
    }

    @Test
    fun `no title matches on a page still allows explicit loading of later results`() = runTest(dispatcher) {
        val query = DiscoveryQuery(titleContains = "Title")
        coEvery { repository.discover(query, "en", 1) } returns DiscoveryPage(emptyList(), true, 21)
        coEvery { repository.discover(query, "en", 2) } returns DiscoveryPage(listOf(item(2)), false, 21)
        val vm = viewModel()
        runCurrent()
        vm.edit(query)
        vm.showResults()
        advanceUntilIdle()
        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(vm.state.value.hasMore)
        coVerify(exactly = 0) { repository.discover(query, "en", 2) }
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("tmdb:2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun `editing filters makes no result requests until explicitly applied`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.edit(DiscoveryQuery(minimumVotes = "500"))
        advanceUntilIdle()
        assertNull(vm.state.value.applied)
        coVerify(exactly = 0) { repository.discover(any(), any(), any()) }
        coEvery { repository.discover(any(), any(), any()) } returns DiscoveryPage(listOf(item(1)), false, 1)
        vm.showResults()
        advanceUntilIdle()
        vm.edit(vm.state.value.draft.copy(minimumVotes = "1000"))
        assertEquals("500", vm.state.value.applied?.minimumVotes)
        assertEquals(listOf("tmdb:1"), vm.state.value.items.map { it.id })
        coVerify(exactly = 1) { repository.discover(any(), any(), any()) }
    }

    @Test
    fun `replacing the local key clears results but preserves this profiles draft and saved discoveries`() = runTest(dispatcher) {
        every { credentials.revision(any()) } answers { credentialChanges.value }
        coEvery { repository.discover(any(), any(), any()) } returns DiscoveryPage(listOf(item(1)), false, 1)
        val vm = viewModel()
        runCurrent()
        val query = DiscoveryQuery(minimumVotes = "1000")
        val saved = SavedDiscovery("one", "Rated films", query)
        savedFlow.value = listOf(saved)
        vm.edit(query)
        vm.showResults()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.items.size)
        credentialChanges.value = 1
        advanceUntilIdle()
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(query, vm.state.value.draft)
        assertEquals(listOf(saved), vm.state.value.saved)
        assertNull(vm.state.value.applied)
    }

    @Test
    fun `pagination deduplicates while retaining provider order and retries only on request`() = runTest(dispatcher) {
        coEvery { repository.discover(any(), any(), 1) } returns DiscoveryPage(listOf(item(3), item(1)), true, 3)
        coEvery { repository.discover(any(), any(), 2) } throws DiscoveryException(DiscoveryFailure.RATE_LIMIT)
        val vm = viewModel()
        runCurrent()
        vm.showResults()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.page)
        assertEquals(DiscoveryFailure.RATE_LIMIT, vm.state.value.error)
        vm.loadMore()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.discover(any(), any(), 2) }
        coEvery { repository.discover(any(), any(), 2) } returns DiscoveryPage(listOf(item(1), item(2)), false, 3)
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf("tmdb:3", "tmdb:1", "tmdb:2"), vm.state.value.items.map { it.id })
        assertEquals(2, vm.state.value.page)
        assertFalse(vm.state.value.hasMore)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `slow superseded query cannot overwrite current results`() = runTest(dispatcher) {
        coEvery { repository.discover(any(), any(), any()) } coAnswers {
            if (firstArg<DiscoveryQuery>().minimumVotes == "100") {
                withContext(NonCancellable) { delay(1000) }
                DiscoveryPage(listOf(item(1)), false, 1)
            } else DiscoveryPage(listOf(item(2)), false, 1)
        }
        val vm = viewModel()
        runCurrent()
        vm.edit(DiscoveryQuery(minimumVotes = "100"))
        vm.showResults()
        runCurrent()
        vm.edit(DiscoveryQuery(minimumVotes = "500"))
        vm.showResults()
        advanceUntilIdle()
        assertEquals(listOf("tmdb:2"), vm.state.value.items.map { it.id })
        assertEquals("500", vm.state.value.applied?.minimumVotes)
    }

    @Test
    fun `watched filtering handles direct and IMDb-only history without reordering`() = runTest(dispatcher) {
        movies.value = setOf("tmdb:1", "tt0002")
        coEvery { repository.discover(any(), any(), any()) } returns DiscoveryPage(listOf(item(1), item(2), item(3)), true, 4)
        coEvery { repository.imdbId(2, false) } returns "tt0002"
        coEvery { repository.imdbId(3, false) } returns "tt0003"
        val vm = viewModel()
        runCurrent()
        vm.showResults()
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.imdbId(any(), any()) }
        assertEquals(3, vm.state.value.items.size)
        vm.edit(DiscoveryQuery(hideWatched = true))
        vm.showResults()
        advanceUntilIdle()
        assertEquals(listOf("tmdb:3"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.hasMore)
        coVerify(exactly = 0) { repository.imdbId(1, any()) }
    }

    @Test
    fun `TV watched filtering uses fully watched shows rather than movie ids`() = runTest(dispatcher) {
        movies.value = setOf("tmdb:2")
        shows.value = setOf("tmdb:1")
        coEvery { repository.discover(any(), any(), any()) } returns DiscoveryPage(listOf(item(1), item(2)), false, 2)
        val vm = viewModel()
        runCurrent()
        vm.edit(DiscoveryQuery(television = true, hideWatched = true))
        vm.showResults()
        advanceUntilIdle()
        assertEquals(listOf("tmdb:2"), vm.state.value.items.map { it.id })
    }

    @Test
    fun `invalid query cannot request or save and valid save captures current profile`() = runTest(dispatcher) {
        coEvery { saved.save(any(), any()) } returns Unit
        val vm = viewModel()
        runCurrent()
        vm.edit(DiscoveryQuery(minimumRating = "11"))
        vm.showResults()
        vm.save("Invalid", false)
        advanceUntilIdle()
        assertTrue(vm.state.value.validationError != null)
        coVerify(exactly = 0) { repository.discover(any(), any(), any()) }
        coVerify(exactly = 0) { saved.save(any(), any()) }
        profiles.value = 2
        runCurrent()
        vm.edit(DiscoveryQuery(minimumVotes = "500", hideWatched = true))
        vm.save("My discovery", false)
        advanceUntilIdle()
        coVerify(exactly = 1) { saved.save(match { it.name == "My discovery" && it.query.minimumVotes == "500" && it.query.hideWatched }, 2) }
    }

    @Test
    fun `profile switch clears previous filters and results and cancels in-flight work`() = runTest(dispatcher) {
        coEvery { repository.discover(any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { delay(1000) }
            DiscoveryPage(listOf(item(1)), false, 1)
        }
        val vm = viewModel()
        runCurrent()
        vm.edit(DiscoveryQuery(minimumVotes = "500"))
        vm.showResults()
        runCurrent()
        profiles.value = 2
        advanceUntilIdle()
        assertEquals(DiscoveryQuery(), vm.state.value.draft)
        assertNull(vm.state.value.applied)
        assertTrue(vm.state.value.items.isEmpty())
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `opening saved discovery restores every filter and executes a new first page`() = runTest(dispatcher) {
        coEvery { repository.discover(any(), any(), any()) } returns DiscoveryPage(emptyList(), false, 0)
        val vm = viewModel()
        runCurrent()
        val query = DiscoveryQuery(television = true, genres = setOf(18), minimumVotes = "100", seriesStatus = "3", sort = "release_date.asc")
        vm.openSaved(SavedDiscovery("one", "Ended dramas", query))
        advanceUntilIdle()
        assertEquals(query, vm.state.value.applied)
        assertEquals(query, vm.state.value.draft)
        assertEquals("one", vm.state.value.selectedSavedId)
        coVerify(exactly = 1) { repository.discover(query, "en", 1) }
    }

    private fun item(id: Int) = MetaPreview("tmdb:$id", ContentType.MOVIE, name = "Title $id", poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null, releaseInfo = "2020", imdbRating = 7f, genres = emptyList())
}
