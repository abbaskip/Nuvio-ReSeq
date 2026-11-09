package com.nuvio.tv.data.local

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Seeds real preferences once. Ordinary defaults and existing installations stay intact. */
internal class FreshInstallDefaults(private val feature: String) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData.asMap().isEmpty()

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (currentData.asMap().isNotEmpty()) return currentData
        val prefs = currentData.toMutablePreferences()
        when (feature) {
            "player_settings" -> {
                prefs[stringPreferencesKey("stream_auto_play_mode")] = "FIRST_STREAM"
                prefs[stringPreferencesKey("stream_auto_play_source")] = "INSTALLED_ADDONS_ONLY"
                prefs[booleanPreferencesKey("stream_auto_play_addons_enabled")] = true
                prefs[booleanPreferencesKey("stream_auto_play_plugins_enabled")] = false
                prefs[booleanPreferencesKey("stream_auto_play_playlists_enabled")] = true
                prefs[intPreferencesKey("stream_auto_play_timeout_seconds")] = 8
                prefs[booleanPreferencesKey("stream_auto_play_any_source_fallback_enabled")] = true
                prefs[intPreferencesKey("stream_auto_play_any_source_fallback_seconds")] = 25
            }
            "layout_settings" -> {
                prefs[booleanPreferencesKey("hero_section_enabled")] = true
                prefs[booleanPreferencesKey("random_episode_enabled")] = true
                prefs[stringPreferencesKey("detail_imdb_ratings_visibility")] = "SHOW_ALL"
                prefs[booleanPreferencesKey("modern_hero_full_screen_backdrop")] = false
                prefs[booleanPreferencesKey("focused_poster_backdrop_expand_enabled")] = true
                prefs[intPreferencesKey("focused_poster_backdrop_expand_delay_seconds")] = 2
                prefs[booleanPreferencesKey("focused_poster_backdrop_trailer_enabled")] = true
                prefs[booleanPreferencesKey("focused_poster_backdrop_trailer_muted")] = false
                prefs[stringPreferencesKey("focused_poster_backdrop_trailer_playback_target")] = "HERO_MEDIA"
            }
            "trailer_settings" -> {
                prefs[booleanPreferencesKey("trailer_enabled")] = true
                prefs[intPreferencesKey("trailer_delay_seconds")] = 5
                prefs[booleanPreferencesKey("trailer_play_in_background")] = false
            }
        }
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
