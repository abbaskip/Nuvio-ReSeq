package com.nuvio.tv.ui.screens.tvmix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.EpisodeShufflePlayback
import com.nuvio.tv.core.player.TvMixSession
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TvMixStore
import com.nuvio.tv.data.tvdb.reconcileTvdbVideoProgress
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

data class TvMixUiState(
    val mixId: String? = null,
    val selection: TvMixSelection? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val continuePrompt: Boolean = false,
    val autoStart: Boolean = false
)

@HiltViewModel
class TvMixViewModel @Inject constructor(
    private val store: TvMixStore,
    private val profiles: ProfileManager,
    private val metadata: MetaRepository,
    private val shuffle: EpisodeShufflePlayback,
    private val progress: WatchProgressRepository,
    private val discovery: com.nuvio.tv.data.repository.DiscoveryRepository,
    val session: TvMixSession
) : ViewModel() {
    val mixes = store.mixes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _state = MutableStateFlow(TvMixUiState())
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var previous: Pair<String, String>? = null
    private var lastPlayed: Pair<String, String>? = null
    init {
        viewModelScope.launch { profiles.activeProfileId.drop(1).collect {
            request?.cancel(); session.end(); previous = null; lastPlayed = null; _state.value = TvMixUiState()
        } }
    }

    fun open(id: String) {
        if (_state.value.mixId == id) return
        session.end(); previous = null; lastPlayed = null
        _state.value = TvMixUiState(mixId = id)
        remix()
    }

    fun remix() {
        val id = _state.value.mixId ?: return
        val profileId = profiles.activeProfileId.value
        previous = _state.value.selection?.key ?: previous
        request?.cancel()
        _state.update { it.copy(selection = null, loading = true, error = null) }
        request = viewModelScope.launch {
            try {
                val mix = store.observe(profileId).first().firstOrNull { it.id == id }
                val selected = mix?.let { selectTvMixEpisode(it.shows, previous, lastPlayed = lastPlayed) { show ->
                    // A failed provider is bounded; cancellation/profile switching still cancels the selection.
                    withTimeoutOrNull(20_000) {
                        try {
                            val lookup = lookupId(show.id, show.imdbId, show.type)
                            val result = metadata.getMetaFromAllAddons(show.type, lookup, show.addonBaseUrl)
                                .first { result -> result !is NetworkResult.Loading }
                            val meta = (result as? NetworkResult.Success)?.data ?: return@withTimeoutOrNull null
                            val state = shuffle.observe(profileId, meta.id, meta.apiType).first()
                            val reconciled = reconcileTvdbVideoProgress(meta.videos, state.progress)
                            val native = meta.videos.any { video -> video.episodeIdentity != null }
                            val watched = if (native) reconciled.filterValues { p -> p.isCompleted() }.keys else state.watched
                            val remote = if (mix.unwatchedOnly && !native) meta.videos.filter { video ->
                                video.episode?.let { ep -> progress.isWatchedByVideoId(video.id, ep) } == true
                            }.mapNotNull { video -> video.season?.let { season -> season to video.episode!! } }.toSet() else emptySet()
                            val picker = RandomEpisodePicker(meta.id, meta.videos, watched + remote, reconciled)
                            meta to picker.eligibleVideos(!mix.unwatchedOnly)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    }
                } }
                if (profiles.activeProfileId.value == profileId) _state.update { it.copy(
                    selection = selected, loading = false,
                    error = if (selected == null) "No eligible episodes available. Try another selection or change the mix." else null,
                    autoStart = it.autoStart && selected != null
                ) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(loading = false, autoStart = false, error = "Could not load this mix. Try again.") } }
        }
    }

    fun play(): TvMixSelection? {
        val s = _state.value
        val selected = s.selection ?: return null
        session.playing.value = TvMixSession.Playing(profiles.activeProfileId.value, s.mixId ?: return null, selected)
        _state.update { it.copy(continuePrompt = false, autoStart = false) }
        return selected
    }

    fun completed(autoplay: Boolean) {
        val playing = session.playing.value ?: return
        previous = playing.selection.key
        lastPlayed = playing.selection.key
        session.end()
        _state.update { it.copy(mixId = playing.mixId, selection = null, continuePrompt = !autoplay, autoStart = autoplay) }
        remix()
    }

    fun end() {
        request?.cancel(); session.end(); previous = null; lastPlayed = null; _state.value = TvMixUiState()
    }

    fun save(mix: TvMix, onSaved: () -> Unit = {}) = write { profile -> store.save(profile, mix); onSaved() }
    fun delete(id: String, onDeleted: () -> Unit) = write { profile -> store.delete(profile, id); onDeleted() }
    fun add(item: MetaPreview, mixId: String?, name: String, onSaved: () -> Unit) = write { profile ->
        val id = lookupId(item.id, item.imdbId, item.apiType)
        val show = TvMixShow(id, item.name, item.rawPosterUrl ?: item.poster, item.apiType,
            id.takeIf { it.startsWith("tt") } ?: item.imdbId, item.sourceAddonBaseUrl)
        if (mixId == null) store.save(profile, TvMix(UUID.randomUUID().toString(), name.trim()).adding(show))
        else store.add(profile, mixId, show)
        onSaved()
    }
    private suspend fun lookupId(id: String, imdb: String?, type: String): String {
        if (!imdb.isNullOrBlank()) return imdb
        if (!id.startsWith("tmdb:")) return id
        val numeric = id.substringAfter(':').substringBefore(':').toIntOrNull() ?: return id
        return withTimeoutOrNull(5_000) { discovery.imdbId(numeric, type in listOf("series", "tv", "anime")) }?.takeIf(String::isNotBlank) ?: id
    }
    private fun write(action: suspend (Int) -> Unit) {
        if (_state.value.saving) return
        val profile = profiles.activeProfileId.value
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try { action(profile) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (profiles.activeProfileId.value == profile) _state.update { it.copy(error = "Could not save changes. Please try again.") } }
            finally { if (profiles.activeProfileId.value == profile) _state.update { it.copy(saving = false) } }
        }
    }
}
