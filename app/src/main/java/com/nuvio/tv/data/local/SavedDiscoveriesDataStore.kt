package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.SavedDiscovery
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SavedDiscoveriesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val key = stringPreferencesKey("saved_discoveries")
    val discoveries = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, "advanced_discover").data.map { parse(it[key]) }
    }

    suspend fun save(discovery: SavedDiscovery, profileId: Int) {
        require(discovery.name.isNotBlank() && discovery.query.validationError() == null)
        factory.get(profileId, "advanced_discover").edit { prefs ->
            val current = parse(prefs[key])
            prefs[key] = gson.toJson(listOf(discovery) + current.filterNot { it.id == discovery.id })
        }
    }

    suspend fun delete(id: String, profileId: Int) {
        factory.get(profileId, "advanced_discover").edit { prefs ->
            prefs[key] = gson.toJson(parse(prefs[key]).filterNot { it.id == id })
        }
    }

    internal fun parse(raw: String?): List<SavedDiscovery> = runCatching {
        val type = object : TypeToken<List<SavedDiscovery>>() {}.type
        gson.fromJson<List<SavedDiscovery>>(raw ?: "[]", type).orEmpty().filter { saved ->
            runCatching { saved.id.isNotBlank() && saved.name.isNotBlank() && saved.query.validationError() == null }.getOrDefault(false)
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}
