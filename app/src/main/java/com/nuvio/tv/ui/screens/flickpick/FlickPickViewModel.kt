package com.nuvio.tv.ui.screens.flickpick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.data.repository.DiscoveryException
import com.nuvio.tv.data.repository.DiscoveryFailure
import com.nuvio.tv.data.repository.DiscoveryRepository
import com.nuvio.tv.domain.model.MovieAgeLimit
import com.nuvio.tv.domain.model.MovieRatingSystem
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

internal data class FlickPickState(
    val fromList: Boolean = false,
    val filters: FlickPickFilters = FlickPickFilters(),
    val ratingSystem: MovieRatingSystem? = null,
    val maximumRating: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val genreError: DiscoveryFailure? = null,
    val lists: List<FlickPickList> = emptyList(),
    val selectedList: String? = null,
    val libraryLoaded: Boolean = false,
    val busy: Boolean = false,
    val empty: Boolean = false,
    val failure: DiscoveryFailure? = null,
    val pick: MetaPreview? = null
)

@HiltViewModel
internal class FlickPickViewModel @Inject constructor(
    private val discovery: DiscoveryRepository,
    private val settings: TmdbSettingsDataStore,
    private val profiles: ProfileManager,
    private val credentials: TmdbCredentialStore,
    private val libraryIds: TmdbService,
    private val history: WatchProgressRepository,
    private val library: LibraryRepository,
    private val homeLists: FlickPickHomeLists,
    private val offers: FlickPickHistory
) : ViewModel() {
    private val mutable = MutableStateFlow(FlickPickState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var references: Job? = null
    private var listJob: Job? = null
    private var activeProfile = profiles.activeProfileId.value

    init {
        viewModelScope.launch {
            combine(profiles.activeProfileId, credentials.changes) { id, _ -> id to credentials.revision(id) }
                .distinctUntilChanged().collectLatest { (profile, _) ->
                    cancelPick()
                    if (profile != activeProfile) {
                        activeProfile = profile
                        listJob?.cancel()
                        mutable.value = FlickPickState(ratingSystem = mutable.value.ratingSystem)
                    }
                    loadGenres()
                }
        }
    }

    fun loadGenres() {
        references?.cancel()
        mutable.update { it.copy(genreError = null) }
        references = viewModelScope.launch {
            try {
                val genres = discovery.genres(false, settings.settings.first().language)
                mutable.update { it.copy(genres = genres) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(genreError = e.failure()) } }
        }
    }

    fun mode(fromList: Boolean) {
        if (fromList == mutable.value.fromList) return
        cancelPick()
        mutable.update { it.copy(fromList = fromList, empty = false, failure = null) }
        if (fromList && listJob?.isActive != true) loadLists()
    }

    fun loadLists() {
        listJob?.cancel()
        mutable.update { it.copy(failure = null, libraryLoaded = false) }
        listJob = viewModelScope.launch {
            try {
                val saved = FlickPickList(LibraryListTab(LOCAL_LIBRARY_LIST_KEY, "", LibraryListTab.Type.PERSONAL))
                combine(library.membershipListTabs.onStart { emit(emptyList()) },
                    homeLists.choices.onStart { emit(emptyList()) }) { tabs, home ->
                    listOf(saved) + tabs.filter { it.supportedContentTypes?.contains("movie") != false }
                        .map { FlickPickList(it) } + home
                }.collect { choices ->
                    val byKey = choices.associateBy { it.key }
                    mutable.update { previous ->
                        val existing = previous.lists.mapNotNull { byKey[it.key] }
                        val existingKeys = existing.mapTo(hashSetOf()) { it.key }
                        // Sources can finish loading while the remote highlight is in the picker.
                        // Hold existing positions as new lists arrive.
                        val ordered = existing + byKey.values.filterNot { it.key in existingKeys }
                        previous.copy(lists = ordered, selectedList = previous.selectedList?.takeIf(byKey::containsKey),
                            libraryLoaded = true)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(lists = emptyList(), selectedList = null, failure = e.failure(), libraryLoaded = true) }
            }
        }
    }

    fun filters(value: FlickPickFilters) {
        cancelPick()
        mutable.update { it.copy(filters = value, empty = false, failure = null) }
    }

    fun reset() {
        cancelPick()
        listJob?.cancel()
        mutable.update { FlickPickState(genres = it.genres, genreError = it.genreError, ratingSystem = it.ratingSystem) }
    }

    suspend fun configureRatings(region: String) {
        val system = try { discovery.ratingSystem(region) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { MovieRatingSystem.forRegion(region, emptyMap()) }
        mutable.update { it.copy(ratingSystem = system,
            maximumRating = it.maximumRating?.takeIf { rating -> it.ratingSystem?.country == system.country && rating in system.ranks }) }
    }

    fun maximumRating(value: String) {
        cancelPick()
        mutable.update { it.copy(maximumRating = value.takeIf { rating -> rating in it.ratingSystem?.ranks.orEmpty() },
            empty = false, failure = null) }
    }

    fun list(key: String) {
        cancelPick()
        mutable.update { it.copy(selectedList = key, empty = false, failure = null) }
    }

    fun cancelPick() {
        request?.cancel()
        mutable.update { it.copy(busy = false, pick = null) }
    }

    fun play() {
        val input = state.value
        if (input.busy || input.pick != null || activeProfile != profiles.activeProfileId.value) return
        if (!input.fromList && input.filters.query().validationError() != null) return
        val profile = activeProfile
        val limit = input.maximumRating?.let { MovieAgeLimit(requireNotNull(input.ratingSystem), it) }
        val choice = input.lists.firstOrNull { it.key == input.selectedList }
        if (input.fromList && choice == null) return
        mutable.update { it.copy(busy = true, empty = false, failure = null) }
        request = viewModelScope.launch {
            try {
                val watched = history.observeWatchedMovieIds().first().mapTo(hashSetOf()) { it.removePrefix("imdb:") }
                val pick = if (input.fromList) {
                    val hasTmdbHistory = watched.any { it.startsWith("tmdb:") }
                    if (choice!!.sources.isEmpty()) {
                        library.movieListItems(choice.tab, profile).shuffled().firstNotNullOfOrNull { entry ->
                            if (entry.hasWatchedId(watched)) null else libraryPick(entry.toMetaPreview(), watched, hasTmdbHistory, limit,
                                entry.tmdbId, entry.imdbId)
                        }
                    } else homeLists.moviePages(choice).map { movies ->
                        movies.firstNotNullOfOrNull { movie -> libraryPick(movie, watched, hasTmdbHistory, limit) }
                    }.filterNotNull().firstOrNull()
                } else {
                    val language = settings.settings.first().language
                    pickDiscoveredMovie(input.filters, LocalDate.now(),
                        load = { query, page, any -> discovery.discover(query, language, page, any,
                            limit?.let { it.system.country to it.maximum }) },
                        unwatched = { movie ->
                            if (movie.id in watched) null else {
                                val tmdb = movie.id.removePrefix("tmdb:").toInt()
                                if (limit != null && !discovery.allowsMovie(tmdb, null, limit)) return@pickDiscoveredMovie null
                                val imdb = discovery.resolveImdbId(tmdb, television = false)
                                movie.copy(imdbId = imdb).takeUnless { imdb != null && imdb in watched }
                            }
                        }, recent = offers.recent(profile))
                }
                currentCoroutineContext().ensureActive()
                if (profile == profiles.activeProfileId.value) {
                    if (!input.fromList && pick != null) offers.record(profile, pick.id)
                    currentCoroutineContext().ensureActive()
                    if (profile == profiles.activeProfileId.value) mutable.update { it.copy(pick = pick, empty = pick == null) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(failure = e.failure()) } }
            finally {
                if (currentCoroutineContext()[Job]?.isActive == true) mutable.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun libraryPick(movie: MetaPreview, watched: Set<String>, hasTmdbHistory: Boolean, limit: MovieAgeLimit?,
        tmdbId: Int? = null, imdbId: String? = movie.imdbId): MetaPreview? {
        if (movie.id.removePrefix("imdb:") in watched) return null
        val tmdb = tmdbId ?: movie.id.takeIf { it.startsWith("tmdb:") }?.substringAfter(':')?.toIntOrNull()
        val imdb = imdbId?.removePrefix("imdb:") ?: movie.id.removePrefix("imdb:").takeIf { it.startsWith("tt") }
            ?: tmdb?.let { libraryIds.tmdbToImdb(it, "movie") }
        if (imdb != null && imdb in watched) return null
        if (tmdb == null && imdb != null && hasTmdbHistory) {
            val resolved = libraryIds.imdbToTmdb(imdb, "movie")
            if (resolved != null && "tmdb:$resolved" in watched) return null
        }
        if (limit != null && !discovery.allowsMovie(tmdb, imdb, limit)) return null
        return movie.copy(imdbId = imdb)
    }

    fun takePick(): Pair<MetaPreview, Int>? {
        val movie = mutable.value.pick ?: return null
        mutable.update { it.copy(pick = null) }
        return if (activeProfile == profiles.activeProfileId.value) movie to activeProfile else null
    }

    private fun Exception.failure() = (this as? DiscoveryException)?.failure ?: DiscoveryFailure.NETWORK
}
