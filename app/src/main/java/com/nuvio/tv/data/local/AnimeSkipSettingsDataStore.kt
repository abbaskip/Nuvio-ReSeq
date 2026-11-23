package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeSkipSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "animeskip_settings"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val enabledKey = booleanPreferencesKey("animeskip_enabled")
    private val clientIdKey = stringPreferencesKey("animeskip_client_id")

    val enabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[enabledKey] ?: false }
    }

    val clientId: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[clientIdKey] ?: "" }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    internal suspend fun profileClientId(profileId: Int) = factory.get(profileId, FEATURE).data.first()[clientIdKey]

    suspend fun setClientId(clientId: String) =
        setClientId(clientId, profileManager.activeProfileId.value)

    internal suspend fun setClientId(clientId: String, profileId: Int) {
        factory.get(profileId, FEATURE).edit { it[clientIdKey] = clientId.trim() }
    }
}
