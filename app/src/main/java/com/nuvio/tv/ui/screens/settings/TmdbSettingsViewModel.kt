package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.remote.api.TmdbApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Named
import javax.inject.Inject

@HiltViewModel
class TmdbSettingsViewModel @Inject constructor(
    private val dataStore: TmdbSettingsDataStore,
    private val trailerService: TrailerService,
    private val metaRepository: MetaRepository,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val credentials: TmdbCredentialStore,
    private val profiles: ProfileManager,
    @param:Named("discoveryTmdb") private val tmdb: TmdbApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(TmdbSettingsUiState())
    val uiState: StateFlow<TmdbSettingsUiState> = _uiState.asStateFlow()
    private var keySaveJob: Job? = null

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
        viewModelScope.launch {
            profiles.activeProfileId.collectLatest { profileId ->
                keySaveJob?.cancel()
                _uiState.update { it.copy(profileId = profileId, hasApiKey = false, savingApiKey = false, apiKeyError = null) }
                credentials.changes.collectLatest {
                    val access = withContext(Dispatchers.IO) { credentials.access(profileId) }
                    if (credentials.revision(profileId) == access.revision) {
                        _uiState.update { it.copy(hasApiKey = access.apiKey.isNotBlank()) }
                    }
                }
            }
        }
    }

    fun saveApiKey(value: String, expectedProfileId: Int? = _uiState.value.profileId, onSaved: () -> Unit) {
        if (expectedProfileId != profiles.activeProfileId.value) return
        val key = value.trim()
        if (!key.matches(Regex("[a-fA-F0-9]{32}"))) {
            _uiState.update { it.copy(apiKeyError = TmdbApiKeyError.INVALID) }
            return
        }
        updateApiKey(key, onSaved)
    }

    fun clearApiKey(expectedProfileId: Int? = _uiState.value.profileId, onSaved: () -> Unit) {
        if (expectedProfileId == profiles.activeProfileId.value) updateApiKey(null, onSaved)
    }

    fun cancelApiKeySave() {
        keySaveJob?.cancel()
        _uiState.update { it.copy(savingApiKey = false, apiKeyError = null) }
    }

    private fun updateApiKey(key: String?, onSaved: () -> Unit) {
        if (_uiState.value.savingApiKey) return
        val profileId = profiles.activeProfileId.value
        _uiState.update { it.copy(savingApiKey = true, apiKeyError = null) }
        keySaveJob = viewModelScope.launch {
            try {
                if (key != null) {
                    val response = withContext(Dispatchers.IO) { tmdb.getMovieGenres(key, "en") }
                    currentCoroutineContext().ensureActive()
                    if (!response.isSuccessful || response.body() == null) {
                        val error = when (response.code()) {
                            401, 403 -> TmdbApiKeyError.INVALID
                            429 -> TmdbApiKeyError.RATE_LIMIT
                            else -> TmdbApiKeyError.NETWORK
                        }
                        _uiState.update { it.copy(apiKeyError = error) }
                        return@launch
                    }
                }
                currentCoroutineContext().ensureActive()
                if (profiles.activeProfileId.value != profileId) return@launch
                withContext(Dispatchers.IO) { credentials.save(profileId, key) }
                if (profiles.activeProfileId.value == profileId) {
                    _uiState.update { it.copy(hasApiKey = key != null) }
                    onSaved()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (profiles.activeProfileId.value == profileId) _uiState.update { it.copy(apiKeyError = TmdbApiKeyError.NETWORK) }
            } finally {
                if (profiles.activeProfileId.value == profileId) _uiState.update { it.copy(savingApiKey = false) }
            }
        }
    }

    fun onEvent(event: TmdbSettingsEvent) {
        when (event) {
            is TmdbSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is TmdbSettingsEvent.ToggleModernHomeEnabled -> {
                update { dataStore.setModernHomeEnabled(event.enabled) }
            }
            is TmdbSettingsEvent.ToggleEnrichContinueWatching -> {
                update { dataStore.setEnrichContinueWatching(event.enabled) }
            }
            is TmdbSettingsEvent.SetLanguage -> update {
                val newLanguage = event.language.ifBlank { "en" }
                val currentLanguage = _uiState.value.language.ifBlank { "en" }
                dataStore.setLanguage(newLanguage)
                if (!newLanguage.equals(currentLanguage, ignoreCase = true)) {
                    trailerService.clearCache()
                }
            }
            is TmdbSettingsEvent.ToggleArtwork -> update { dataStore.setUseArtwork(event.enabled) }
            is TmdbSettingsEvent.ToggleBasicInfo -> update { dataStore.setUseBasicInfo(event.enabled) }
            is TmdbSettingsEvent.ToggleDetails -> update { dataStore.setUseDetails(event.enabled) }
            is TmdbSettingsEvent.ToggleCredits -> update { dataStore.setUseCredits(event.enabled) }
            is TmdbSettingsEvent.ToggleProductions -> update { dataStore.setUseProductions(event.enabled) }
            is TmdbSettingsEvent.ToggleNetworks -> update { dataStore.setUseNetworks(event.enabled) }
            is TmdbSettingsEvent.ToggleEpisodes -> update { dataStore.setUseEpisodes(event.enabled) }
            is TmdbSettingsEvent.ToggleTrailers -> update { dataStore.setUseTrailers(event.enabled) }
            is TmdbSettingsEvent.ToggleMoreLikeThis -> update { dataStore.setUseMoreLikeThis(event.enabled) }
            is TmdbSettingsEvent.ToggleCollections -> update { dataStore.setUseCollections(event.enabled) }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}

enum class TmdbApiKeyError { INVALID, NETWORK, RATE_LIMIT }

data class TmdbSettingsUiState(
    val profileId: Int? = null,
    val hasApiKey: Boolean = false,
    val savingApiKey: Boolean = false,
    val apiKeyError: TmdbApiKeyError? = null,
    val enabled: Boolean = false,
    val modernHomeEnabled: Boolean = false,
    val enrichContinueWatching: Boolean = true,
    val language: String = "en",
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useTrailers: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useCollections: Boolean = true
) {
    fun fromSettings(settings: TmdbSettings): TmdbSettingsUiState = copy(
        enabled = settings.enabled,
        modernHomeEnabled = settings.modernHomeEnabled,
        enrichContinueWatching = settings.enrichContinueWatching,
        language = settings.language,
        useArtwork = settings.useArtwork,
        useBasicInfo = settings.useBasicInfo,
        useDetails = settings.useDetails,
        useCredits = settings.useCredits,
        useProductions = settings.useProductions,
        useNetworks = settings.useNetworks,
        useEpisodes = settings.useEpisodes,
        useTrailers = settings.useTrailers,
        useMoreLikeThis = settings.useMoreLikeThis,
        useCollections = settings.useCollections
    )
}

sealed class TmdbSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleModernHomeEnabled(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleEnrichContinueWatching(val enabled: Boolean) : TmdbSettingsEvent()
    data class SetLanguage(val language: String) : TmdbSettingsEvent()
    data class ToggleArtwork(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleBasicInfo(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleDetails(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCredits(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleProductions(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleNetworks(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleEpisodes(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleTrailers(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleMoreLikeThis(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCollections(val enabled: Boolean) : TmdbSettingsEvent()
}
