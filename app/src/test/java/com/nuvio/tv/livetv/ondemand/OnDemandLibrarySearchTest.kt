package com.nuvio.tv.livetv.ondemand

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnDemandLibrarySearchTest {
    private val repository = mockk<OnDemandRepository>(relaxed = true)
    private val preferences = mockk<LiveTvPreferences>()
    private fun playlist(id: String) = PlaylistSource(id, "Playlist $id", "", xtreamServer = "https://$id.example.test", importVod = true)
    private val playlists = MutableStateFlow(listOf(playlist("one"), playlist("two")))
    private val settings = MutableStateFlow(LiveTvSettings())
    private val user = MutableStateFlow(LiveUserState())
    private val version = MutableStateFlow(0)
    private val store = ViewModelStore()
    private val search = OnDemandLibrarySearch(repository, preferences)

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { repository.version } returns version
        every { repository.artworkUpdates } returns MutableSharedFlow<Map<String, String?>>()
        every { preferences.playlists } returns playlists
        every { preferences.settings } returns settings
        every { preferences.userState } returns user
        coEvery { repository.categories(any()) } returns emptyList()
        coEvery { repository.searchPlaylist(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
    }

    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun viewModel() = OnDemandLibrarySearchViewModel(search, repository, preferences).also { store.put("search", it) }
    private fun item(playlist: String, id: Int, kind: VodKind = VodKind.MOVIE, category: String = "movies") =
        VodItem(playlist, kind, "$id", "Title $id", "https://media.example.test/$id.jpg", category, "mkv", "$id", 2022, null, 0)

    @Test fun `search uses only enabled imported IPTV libraries and excludes hidden and locked groups`() = runTest {
        playlists.value = listOf(playlist("one"), playlist("disabled").copy(enabled = false),
            playlist("live-only").copy(importVod = false), PlaylistSource("m3u", "M3U", "https://media.example.test/list"))
        settings.value = settings.value.copy(parentalPin = "1234", lockAdultContent = true)
        user.value = user.value.copy(vodHiddenCategories = setOf("movie:one:hidden"), lockedGroups = setOf("vod:series:one:locked"))
        coEvery { repository.categories(VodKind.MOVIE) } returns listOf(VodCategory("one", VodKind.MOVIE, "adult", "Adult", 10))
        coEvery { repository.categories(VodKind.SERIES) } returns listOf(VodCategory("one", VodKind.SERIES, "locked", "Shows", 10))

        val request = search.request("  Tulsa King  ", null)
        assertEquals("Tulsa King", request.query)
        assertEquals(listOf("one"), request.playlists.map { it.id })
        assertEquals(setOf("movie:one:hidden", "movie:one:adult", "series:one:locked"), request.hidden)
        search.page(request, request.playlists.single(), 0)
        coVerify { repository.searchPlaylist("one", null, "Tulsa King", request.hidden, true, 51, 0) }
    }

    @Test fun `each playlist retains its own row even when the same title exists in both`() = runTest {
        coEvery { repository.searchPlaylist(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            listOf(item(firstArg(), 1), item(firstArg(), 1, VodKind.SERIES))
        }
        val vm = viewModel()
        runCurrent()
        vm.queryChanged("Title")
        advanceUntilIdle()
        assertFalse(vm.ui.value.loading)
        assertEquals(listOf("one", "two"), vm.ui.value.rows.map { it.playlist.id })
        assertEquals(4, vm.ui.value.rows.sumOf { it.items.size })
        assertEquals(4, vm.ui.value.rows.flatMap { it.items }.map { it.uid }.distinct().size)
    }

    @Test fun `paging reaches matches beyond the old three hundred result limit`() = runTest {
        playlists.value = listOf(playlist("one"))
        val catalogue = (1..370).map { item("one", it) }
        val offsets = mutableListOf<Int>()
        coEvery { repository.searchPlaylist("one", any(), any(), any(), any(), any(), any()) } coAnswers {
            offsets += arg<Int>(6)
            catalogue.drop(arg(6)).take(arg(5))
        }
        val vm = viewModel()
        runCurrent()
        vm.queryChanged("Title")
        advanceUntilIdle()
        assertEquals(50, vm.ui.value.rows.single().items.size)
        while (vm.ui.value.rows.single().hasMore) {
            vm.loadMore("one")
            advanceUntilIdle()
        }
        assertEquals(catalogue, vm.ui.value.rows.single().items)
        assertEquals((0..350 step 50).toList(), offsets)
    }

    @Test fun `a late old query cannot replace newer results`() = runTest {
        playlists.value = listOf(playlist("one"))
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.searchPlaylist("one", any(), "Old", any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { gate.await() }
            listOf(item("one", 1))
        }
        coEvery { repository.searchPlaylist("one", any(), "New", any(), any(), any(), any()) } returns listOf(item("one", 2))
        val vm = viewModel()
        runCurrent()
        vm.queryChanged("Old")
        advanceUntilIdle()
        vm.queryChanged("New")
        advanceUntilIdle()
        assertEquals(listOf(item("one", 2)), vm.ui.value.rows.single().items)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("New", vm.ui.value.query)
        assertEquals(listOf(item("one", 2)), vm.ui.value.rows.single().items)
        assertFalse(vm.ui.value.loading)
    }

    @Test fun `movies and series filters start a fresh search from the first page`() = runTest {
        playlists.value = listOf(playlist("one"))
        coEvery { repository.searchPlaylist("one", any(), any(), any(), any(), any(), any()) } coAnswers {
            listOf(item("one", 1, arg<VodKind?>(1) ?: VodKind.MOVIE))
        }
        val vm = viewModel()
        runCurrent()
        vm.queryChanged("Title")
        advanceUntilIdle()
        vm.selectKind(VodKind.SERIES)
        advanceUntilIdle()
        assertEquals(VodKind.SERIES, vm.ui.value.rows.single().items.single().kind)
        coVerify { repository.searchPlaylist("one", VodKind.SERIES, "Title", any(), any(), 51, 0) }
    }

    @Test fun `changing visibility or disabling a playlist removes its existing results`() = runTest {
        coEvery { repository.searchPlaylist(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val result = item(firstArg(), 1)
            if ("movie:${result.playlistId}:movies" in arg<Set<String>>(3)) emptyList() else listOf(result)
        }
        val vm = viewModel()
        runCurrent()
        vm.queryChanged("Title")
        advanceUntilIdle()
        user.value = user.value.copy(vodHiddenCategories = setOf("movie:one:movies"))
        playlists.value = playlists.value.map { if (it.id == "two") it.copy(enabled = false) else it }
        advanceUntilIdle()
        assertEquals(listOf("one"), vm.ui.value.rows.map { it.playlist.id })
        assertTrue(vm.ui.value.rows.single().items.isEmpty())
        version.value++
        advanceUntilIdle()
        assertTrue(vm.ui.value.rows.single().items.isEmpty())
    }

    @Test fun `one failing playlist does not hide another playlists results`() = runTest {
        coEvery { repository.searchPlaylist("one", any(), any(), any(), any(), any(), any()) } throws IllegalStateException("Unavailable")
        coEvery { repository.searchPlaylist("two", any(), any(), any(), any(), any(), any()) } returns listOf(item("two", 1))
        val vm = viewModel()
        runCurrent()
        vm.queryChanged("Title")
        advanceUntilIdle()
        assertTrue(vm.ui.value.error)
        assertFalse(vm.ui.value.loading)
        assertEquals(listOf(item("two", 1)), vm.ui.value.rows.flatMap { it.items })
    }

    @Test fun `typing is debounced and a short query clears previous matches`() = runTest {
        val vm = viewModel()
        runCurrent()
        vm.queryChanged("Ti")
        advanceTimeBy(100)
        vm.queryChanged("Tit")
        advanceTimeBy(100)
        vm.queryChanged("Title")
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.searchPlaylist(any(), any(), "Ti", any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.searchPlaylist(any(), any(), "Tit", any(), any(), any(), any()) }
        coVerify(exactly = 2) { repository.searchPlaylist(any(), any(), "Title", any(), any(), any(), any()) }
        vm.queryChanged("T")
        advanceUntilIdle()
        assertTrue(vm.ui.value.rows.isEmpty())
        assertFalse(vm.ui.value.loading)
    }
}
