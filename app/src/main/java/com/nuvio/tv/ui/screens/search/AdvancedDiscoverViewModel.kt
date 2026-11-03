package com.nuvio.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SavedDiscoveriesDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.data.remote.api.TmdbLanguage
import com.nuvio.tv.data.repository.DiscoveryException
import com.nuvio.tv.data.repository.DiscoveryFailure
import com.nuvio.tv.data.repository.DiscoveryRepository
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.domain.model.DiscoveryValidationError
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.SavedDiscovery
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import javax.inject.Inject

data class AdvancedDiscoverUiState(
    val draft: DiscoveryQuery = DiscoveryQuery(),
    val applied: DiscoveryQuery? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val languages: List<TmdbLanguage> = emptyList(),
    val referenceLoading: Boolean = false,
    val referenceError: DiscoveryFailure? = null,
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = false,
    val filteringWatched: Boolean = false,
    val page: Int = 0,
    val hasMore: Boolean = false,
    val totalResults: Int = 0,
    val error: DiscoveryFailure? = null,
    val validationError: DiscoveryValidationError? = null,
    val saved: List<SavedDiscovery> = emptyList(),
    val selectedSavedId: String? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val savedSuccessfully: Boolean = false,
    val resultRevision: Int = 0,
    val cardWidthDp: Int = 126,
    val cardCornerRadiusDp: Int = 12
)

@HiltViewModel
class AdvancedDiscoverViewModel @Inject constructor(
    private val repository: DiscoveryRepository,
    private val savedDiscoveries: SavedDiscoveriesDataStore,
    private val settings: TmdbSettingsDataStore,
    private val profileManager: ProfileManager,
    watchProgress: WatchProgressRepository,
    watchedSeries: WatchedSeriesStateHolder,
    credentials: TmdbCredentialStore,
    layout: LayoutPreferenceDataStore
) : ViewModel() {
    private val _state = MutableStateFlow(AdvancedDiscoverUiState())
    val state = _state.asStateFlow()
    private data class Results(val query: DiscoveryQuery = DiscoveryQuery(), val items: List<MetaPreview> = emptyList())
    private val results = MutableStateFlow(Results())
    private var request: Job? = null
    private var references: Job? = null
    private val aliasPermits = Semaphore(3)

    init {
        viewModelScope.launch {
            var previousProfile: Int? = null
            combine(profileManager.activeProfileId, credentials.changes) { id, _ -> id to credentials.revision(id) }
                .distinctUntilChanged().collectLatest { (profileId, _) ->
                    request?.cancel()
                    references?.cancel()
                    val previous = _state.value
                    _state.value = if (previousProfile == profileId) AdvancedDiscoverUiState(
                        draft = previous.draft, saved = previous.saved, selectedSavedId = previous.selectedSavedId,
                        cardWidthDp = previous.cardWidthDp, cardCornerRadiusDp = previous.cardCornerRadiusDp
                    ) else AdvancedDiscoverUiState()
                    previousProfile = profileId
                    results.value = Results()
                    reloadReferences()
            }
        }
        viewModelScope.launch { savedDiscoveries.discoveries.collect { saved -> _state.update { it.copy(saved = saved) } } }
        viewModelScope.launch { layout.posterCardWidthDp.collect { width -> _state.update { it.copy(cardWidthDp = width) } } }
        viewModelScope.launch { layout.posterCardCornerRadiusDp.collect { radius -> _state.update { it.copy(cardCornerRadiusDp = radius) } } }
        viewModelScope.launch {
            combine(results, watchProgress.observeWatchedMovieIds(), watchedSeries.fullyWatchedSeriesIds) { loaded, movies, shows ->
                loaded to if (loaded.query.television) shows else movies
            }.collectLatest { (loaded, watched) ->
                _state.update { it.copy(filteringWatched = loaded.query.hideWatched && loaded.items.isNotEmpty()) }
                val visible = if (loaded.query.hideWatched) filterWatched(loaded.items, watched, loaded.query.mediaType) else loaded.items
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(items = visible, filteringWatched = false) }
            }
        }
    }

    fun edit(query: DiscoveryQuery) {
        val changedType = query.television != _state.value.draft.television
        _state.update { it.copy(draft = query, validationError = null, savedSuccessfully = false) }
        if (changedType) reloadReferences()
    }

    fun changeType(television: Boolean) = edit(_state.value.draft.copy(
        television = television, genres = emptySet(), excludedGenres = emptySet(), seriesStatus = ""
    ))

    fun reset() {
        edit(DiscoveryQuery())
        _state.update { it.copy(selectedSavedId = null) }
    }

    fun showResults() {
        val query = _state.value.draft
        val error = query.validationError()
        _state.update { it.copy(validationError = error) }
        if (error != null) return
        request?.cancel()
        results.value = Results(query)
        _state.update { it.copy(applied = query, items = emptyList(), page = 0, hasMore = false,
            totalResults = 0, error = null, loading = false, resultRevision = it.resultRevision + 1) }
        loadMore()
    }

    fun loadMore() {
        val current = _state.value
        val query = current.applied ?: return
        if (current.loading || (current.page > 0 && !current.hasMore)) return
        // An error is retried only by the explicit Retry button, never by grid auto-pagination.
        if (current.error != null) return
        _state.update { it.copy(loading = true, error = null) }
        request = viewModelScope.launch {
            try {
                val language = settings.settings.first().language
                val nextPage = current.page + 1
                val page = repository.discover(query, language, nextPage)
                currentCoroutineContext().ensureActive()
                results.value = Results(query, (results.value.items + page.items).distinctBy { it.id })
                _state.update { it.copy(page = nextPage, hasMore = page.hasMore,
                    totalResults = page.totalResults, loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(loading = false, error = failure(e)) }
            }
        }
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    fun reloadReferences() {
        references?.cancel()
        val television = _state.value.draft.television
        _state.update { it.copy(genres = emptyList(), referenceLoading = true, referenceError = null) }
        references = viewModelScope.launch {
            try {
                val language = settings.settings.first().language
                val genres = repository.genres(television, language)
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(genres = genres) }
                val languages = repository.languages()
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(languages = languages, referenceLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(referenceLoading = false, referenceError = failure(e)) }
            }
        }
    }

    fun openSaved(saved: SavedDiscovery) {
        edit(saved.query)
        _state.update { it.copy(selectedSavedId = saved.id) }
        showResults()
    }

    fun save(name: String, replaceSelected: Boolean) {
        val current = _state.value
        if (current.saving || name.isBlank()) return
        val error = current.draft.validationError()
        _state.update { it.copy(validationError = error) }
        if (error != null) return
        val profileId = profileManager.activeProfileId.value
        val id = if (replaceSelected) current.selectedSavedId ?: UUID.randomUUID().toString() else UUID.randomUUID().toString()
        _state.update { it.copy(saving = true, saveFailed = false, savedSuccessfully = false) }
        viewModelScope.launch {
            try {
                savedDiscoveries.save(SavedDiscovery(id, name.trim(), current.draft), profileId)
                if (profileManager.activeProfileId.value == profileId) _state.update {
                    it.copy(selectedSavedId = id, saving = false, savedSuccessfully = true)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (profileManager.activeProfileId.value == profileId) _state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    fun deleteSaved(saved: SavedDiscovery) {
        val profileId = profileManager.activeProfileId.value
        viewModelScope.launch {
            try {
                savedDiscoveries.delete(saved.id, profileId)
                if (profileManager.activeProfileId.value == profileId) _state.update {
                    it.copy(selectedSavedId = it.selectedSavedId.takeUnless { id -> id == saved.id })
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(saveFailed = true) } }
        }
    }

    private suspend fun filterWatched(items: List<MetaPreview>, watched: Set<String>, mediaType: String): List<MetaPreview> = coroutineScope {
        val imdbIds = watched.map { it.removePrefix("imdb:") }.filter { it.startsWith("tt") }.toSet()
        items.map { item -> async {
            if (item.id in watched) return@async null
            // Trackers normally supply TMDB aliases. Local IMDb-only history needs an ID lookup,
            // only when Hide watched is enabled, using the same profile-local key and bounded concurrency.
            val imdbId = if (imdbIds.isNotEmpty()) aliasPermits.withPermit {
                item.id.removePrefix("tmdb:").toIntOrNull()?.let { repository.imdbId(it, mediaType == "tv") }
            } else null
            item.takeUnless { imdbId != null && imdbId in imdbIds }
        } }.awaitAll().filterNotNull()
    }

    private fun failure(e: Exception) = (e as? DiscoveryException)?.failure ?: DiscoveryFailure.NETWORK
}
