package com.nuvio.tv.ui.screens.flickpick

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import kotlinx.coroutines.flow.first

internal class FlickPickHistory @Inject constructor(private val stores: ProfileDataStoreFactory) {
    private fun key(television: Boolean) = stringPreferencesKey(if (television) "recent_series" else "recent_discoveries")
    suspend fun recent(profile: Int, television: Boolean = false): List<String> =
        stores.get(profile, "flick_pick").data.first()[key(television)].orEmpty().split(',').filter { it.isNotEmpty() }

    suspend fun record(profile: Int, tmdbId: String, television: Boolean = false) {
        val key = key(television)
        stores.get(profile, "flick_pick").edit { prefs ->
            val previous = prefs[key].orEmpty().split(',').filter { it.isNotEmpty() && it != tmdbId }
            prefs[key] = (listOf(tmdbId) + previous.take(19)).joinToString(",")
        }
    }
}
