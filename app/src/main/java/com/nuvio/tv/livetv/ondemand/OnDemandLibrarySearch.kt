package com.nuvio.tv.livetv.ondemand

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.parental.ParentalControls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject

internal const val VOD_SEARCH_PAGE_SIZE = 50

data class VodSearchPlaylist(val id: String, val name: String)
data class VodSearchRequest(
    val query: String,
    val kind: VodKind?,
    val playlists: List<VodSearchPlaylist>,
    val hidden: Set<String>,
    val merge: Boolean
)
data class VodSearchRow(
    val playlist: VodSearchPlaylist,
    val items: List<VodItem> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false
)
data class VodLibrarySearchState(
    val query: String = "",
    val kind: VodKind? = null,
    val rows: List<VodSearchRow> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false
)

/** Local catalogue search: never depends on an addon recognizing a provider's title. */
class OnDemandLibrarySearch @Inject constructor(
    private val repository: OnDemandRepository,
    private val preferences: LiveTvPreferences
) {
    suspend fun request(query: String, kind: VodKind?): VodSearchRequest {
        val playlists = preferences.playlists.first().filter { it.enabled && it.importVod && it.isXtream }
        val user = preferences.userState.first()
        val settings = preferences.settings.first()
        val categories = repository.categories(VodKind.MOVIE) + repository.categories(VodKind.SERIES)
        val hidden = user.vodHiddenCategories + categories.filter {
            ParentalControls.isLocked("vod:${it.uid}", it.name, settings, user.lockedGroups)
        }.map { it.uid }
        return VodSearchRequest(query.trim(), kind, playlists.map { VodSearchPlaylist(it.id, it.name) }, hidden, settings.vodMergeDuplicates)
    }

    suspend fun page(request: VodSearchRequest, playlist: VodSearchPlaylist, offset: Int): VodSearchRow {
        val items = repository.searchPlaylist(playlist.id, request.kind, request.query, request.hidden,
            request.merge, VOD_SEARCH_PAGE_SIZE + 1, offset)
        return VodSearchRow(playlist, items.take(VOD_SEARCH_PAGE_SIZE), items.size > VOD_SEARCH_PAGE_SIZE)
    }
}

@HiltViewModel
class OnDemandLibrarySearchViewModel @Inject constructor(
    private val search: OnDemandLibrarySearch,
    private val repository: OnDemandRepository,
    private val preferences: LiveTvPreferences
) : ViewModel() {
    private val _ui = MutableStateFlow(VodLibrarySearchState())
    val ui = _ui.asStateFlow()
    private var searchJob: Job? = null
    private val pageJobs = mutableMapOf<String, Job>()
    private var generation = 0L
    private var request: VodSearchRequest? = null

    init {
        repository.start()
        viewModelScope.launch {
            repository.artworkUpdates.collect { urls ->
                _ui.update { state -> state.copy(rows = state.rows.map { row -> row.copy(items = row.items.map { item ->
                    if (urls.containsKey(item.uid)) item.copy(icon = urls[item.uid]) else item
                }) }) }
            }
        }
        viewModelScope.launch {
            // Re-run when imports, playlist eligibility, visibility or PIN access change.
            combine(repository.version, preferences.playlists, preferences.settings, preferences.userState,
                ParentalControls.unlocked) { _, _, _, _, _ -> Unit }.collect { restart() }
        }
    }

    fun queryChanged(query: String) {
        if (query == _ui.value.query) return
        _ui.update { it.copy(query = query) }
        restart(debounce = true)
    }

    fun selectKind(kind: VodKind?) {
        if (kind == _ui.value.kind) return
        _ui.update { it.copy(kind = kind) }
        restart()
    }

    fun submit() = restart()

    private fun restart(debounce: Boolean = false) {
        val run = ++generation
        searchJob?.cancel()
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        request = null
        val state = _ui.value
        val query = state.query.trim()
        _ui.update { it.copy(rows = emptyList(), loading = query.length >= 2, error = false) }
        if (query.length < 2) return
        searchJob = viewModelScope.launch {
            try {
                if (debounce) delay(300)
                val captured = search.request(query, state.kind)
                if (run != generation) return@launch
                request = captured
                _ui.update { it.copy(rows = captured.playlists.map { playlist -> VodSearchRow(playlist, loading = true) }) }
                coroutineScope {
                    captured.playlists.map { playlist -> launch {
                        try {
                            val page = search.page(captured, playlist, 0)
                            if (run == generation) replaceRow(page)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (run == generation) {
                                replaceRow(VodSearchRow(playlist))
                                _ui.update { it.copy(error = true) }
                            }
                        }
                    } }.joinAll()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (run == generation) _ui.update { it.copy(error = true) }
            } finally {
                if (run == generation) _ui.update { it.copy(loading = false) }
            }
        }
    }

    fun loadMore(playlistId: String) {
        val captured = request ?: return
        val row = _ui.value.rows.firstOrNull { it.playlist.id == playlistId } ?: return
        if (row.loading || !row.hasMore) return
        val run = generation
        replaceRow(row.copy(loading = true))
        pageJobs[playlistId] = viewModelScope.launch {
            try {
                val page = search.page(captured, row.playlist, row.items.size)
                if (run == generation) replaceRow(page.copy(items = row.items + page.items))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (run == generation) {
                    replaceRow(row.copy(loading = false, hasMore = false))
                    _ui.update { it.copy(error = true) }
                }
            }
        }
    }

    private fun replaceRow(row: VodSearchRow) {
        _ui.update { state -> state.copy(rows = state.rows.map { if (it.playlist.id == row.playlist.id) row else it }) }
    }
}
