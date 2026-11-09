package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class FreshInstallDefaultsTest {
    @Test fun factorySeedsFreshInstallationButNotUpgrade() = runTest {
        for (fresh in listOf(true, false)) {
            val directory = java.nio.file.Files.createTempDirectory("install-defaults").toFile()
            val editor = io.mockk.mockk<android.content.SharedPreferences.Editor>(relaxed = true)
            io.mockk.every { editor.putBoolean(any(), any()) } returns editor
            io.mockk.every { editor.commit() } returns true
            val policy = io.mockk.mockk<android.content.SharedPreferences>(relaxed = true)
            io.mockk.every { policy.edit() } returns editor
            val manager = io.mockk.mockk<android.content.pm.PackageManager>()
            val info = android.content.pm.PackageInfo().apply {
                firstInstallTime = 100
                lastUpdateTime = if (fresh) 100 else 200
            }
            io.mockk.every { manager.getPackageInfo("test.defaults", 0) } returns info
            val context = io.mockk.mockk<android.content.Context>(relaxed = true)
            io.mockk.every { context.applicationContext } returns context
            io.mockk.every { context.filesDir } returns directory
            io.mockk.every { context.packageName } returns "test.defaults"
            io.mockk.every { context.packageManager } returns manager
            io.mockk.every { context.getSharedPreferences(any(), any()) } returns policy
            val factory = ProfileDataStoreFactory(context)
            val prefs = factory.get(1, "player_settings").data.first()
            assertEquals(if (fresh) 8 else null, prefs[intPreferencesKey("stream_auto_play_timeout_seconds")])
        }
    }

    @Test fun freshPlayerPreferencesKeepExplicitTierDeadlines() = runTest {
        val result = FreshInstallDefaults("player_settings").migrate(emptyPreferences())
        assertEquals("FIRST_STREAM", result[stringPreferencesKey("stream_auto_play_mode")])
        assertEquals("INSTALLED_ADDONS_ONLY", result[stringPreferencesKey("stream_auto_play_source")])
        assertEquals(true, result[booleanPreferencesKey("stream_auto_play_playlists_enabled")])
        assertEquals(false, result[booleanPreferencesKey("stream_auto_play_plugins_enabled")])
        assertEquals(8, result[intPreferencesKey("stream_auto_play_timeout_seconds")])
        assertEquals(25, result[intPreferencesKey("stream_auto_play_any_source_fallback_seconds")])
    }

    @Test fun restoredAndUserEditedStoresAreNotSeeded() = runTest {
        for (feature in listOf("player_settings", "layout_settings", "trailer_settings")) {
            val existing = preferencesOf(booleanPreferencesKey("user_choice") to false)
            val migration = FreshInstallDefaults(feature)
            assertFalse(migration.shouldMigrate(existing))
            assertEquals(existing, migration.migrate(existing))
        }
    }

    @Test fun trailerSurfacesAndTimingAreSeededIndependently() = runTest {
        val home = FreshInstallDefaults("layout_settings").migrate(emptyPreferences())
        val detail = FreshInstallDefaults("trailer_settings").migrate(emptyPreferences())
        assertEquals(2, home[intPreferencesKey("focused_poster_backdrop_expand_delay_seconds")])
        assertEquals("HERO_MEDIA", home[stringPreferencesKey("focused_poster_backdrop_trailer_playback_target")])
        assertEquals(false, home[booleanPreferencesKey("modern_hero_full_screen_backdrop")])
        assertEquals(6, detail[intPreferencesKey("trailer_delay_seconds")])
        assertEquals(false, detail[booleanPreferencesKey("trailer_play_in_background")])
        val edited = home.toMutablePreferences().apply {
            this[intPreferencesKey("focused_poster_backdrop_expand_delay_seconds")] = 9
        }.toPreferences()
        assertFalse(FreshInstallDefaults("layout_settings").shouldMigrate(edited))
        assertEquals(edited, FreshInstallDefaults("layout_settings").migrate(edited))
    }
}
