package com.nuvio.tv.data.local

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit

internal val continueTrailerInDetailsKey = booleanPreferencesKey("focused_poster_trailer_continue_in_details")
internal val trailerPlayInBackgroundKey = booleanPreferencesKey("trailer_play_in_background")
internal val trailerPauseOnScrollKey = booleanPreferencesKey("trailer_pause_on_scroll")

internal suspend fun DataStore<Preferences>.setTrailerLayoutSwitch(key: Preferences.Key<Boolean>, enabled: Boolean) {
    edit {
        it[key] = enabled
        // An account reset can clear an already-open store without rerunning its migrations.
        it[TrailerLayoutDefaultsMigration.appliedKey] = true
    }
}

internal class TrailerLayoutDefaultsMigration private constructor(
    val switches: List<Preferences.Key<Boolean>>
) : DataMigration<Preferences> {
    companion object {
        val appliedKey = booleanPreferencesKey("migration_trailer_layout_defaults_v1")
        private val home = TrailerLayoutDefaultsMigration(listOf(continueTrailerInDetailsKey))
        private val details = TrailerLayoutDefaultsMigration(listOf(trailerPlayInBackgroundKey, trailerPauseOnScrollKey))

        fun forFeature(feature: String): TrailerLayoutDefaultsMigration? = when (feature) {
            "layout_settings" -> home
            "trailer_settings" -> details
            else -> null
        }
    }

    override suspend fun shouldMigrate(currentData: Preferences) = currentData[appliedKey] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (!shouldMigrate(currentData)) return currentData
        return currentData.toMutablePreferences().apply {
            switches.forEach { this[it] = true }
            this[appliedKey] = true
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
