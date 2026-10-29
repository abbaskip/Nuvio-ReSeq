package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.tvdb.TvdbClient
import com.nuvio.tv.data.tvdb.TvdbCredentialStore
import com.nuvio.tv.data.tvdb.TvdbException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TvdbConnectionState(val configured: Boolean = false, val busy: Boolean = false, val message: String? = null)

@HiltViewModel
class TvdbSettingsViewModel @Inject constructor(
    private val credentials: TvdbCredentialStore,
    private val client: TvdbClient,
    private val profiles: ProfileManager
) : ViewModel() {
    private val mutableState = MutableStateFlow(TvdbConnectionState())
    private var generation = 0L
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            profiles.activeProfileId.collect { id ->
                generation++
                val configured = withContext(Dispatchers.IO) { credentials.read(id) != null }
                mutableState.value = TvdbConnectionState(configured)
            }
        }
    }

    fun save(value: String?) = operation { profileId ->
        withContext(Dispatchers.IO) { credentials.save(profileId, value) }
        TvdbConnectionState(!value.isNullOrBlank(), message = if (value.isNullOrBlank()) "API key removed" else "API key saved. Test the connection to verify it.")
    }

    fun test() = operation { profileId ->
        client.testConnection(profileId)
        TvdbConnectionState(true, message = "Connected to TheTVDB")
    }

    private fun operation(action: suspend (Int) -> TvdbConnectionState) {
        if (state.value.busy) return
        val profileId = profiles.activeProfileId.value
        val requestGeneration = generation
        mutableState.value = state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val result = try { action(profileId) }
            catch (e: CancellationException) { throw e }
            catch (e: TvdbException) {
                state.value.copy(busy = false, message = when (e.reason) {
                    TvdbException.Reason.NO_KEY -> "Save a TVDB API key first."
                    TvdbException.Reason.AUTHENTICATION -> "TVDB rejected the key. Check its API access or replace it. Some keys may require subscriber access."
                    TvdbException.Reason.RATE_LIMIT -> "TVDB rate limit reached. Try again later."
                    TvdbException.Reason.CREDENTIALS_CHANGED -> "Credentials changed. Test the saved key again."
                    else -> "Unable to connect to TVDB. Check your connection and try again."
                })
            } catch (_: Exception) {
                state.value.copy(busy = false, message = "Unable to access TVDB credentials. Try saving the key again.")
            }
            if (profiles.activeProfileId.value == profileId && generation == requestGeneration) mutableState.value = result
        }
    }
}
