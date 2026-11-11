package com.nuvio.tv.livetv.data

import android.content.Context
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.buffer
import okio.sink

class LiveTvFirstRunTest {
    @get:Rule val temp = TemporaryFolder()

    private fun preferences(dir: java.io.File): LiveTvPreferences {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns dir
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        return LiveTvPreferences(context, profiles)
    }

    @Test fun `first launch reads both missing IPTV preference stores`() = runBlocking {
        val dir = temp.newFolder()
        val prefs = preferences(dir)
        withTimeout(5_000) { prefs.settings.first(); prefs.userState.first() }
        assertTrue(java.io.File(dir, "datastore/live_tv.preferences_pb").isFile)
        assertTrue(java.io.File(dir, "datastore/live_tv_device.preferences_pb").isFile)
    }

    @Test fun `initializing IPTV retains saved preferences`() = runBlocking {
        val dir = temp.newFolder()
        val file = java.io.File(dir, "datastore/live_tv.preferences_pb")
        file.parentFile!!.mkdirs()
        // Seed a persisted file without a second live DataStore/coordinator
        // retaining Windows file handles while the production store opens it.
        file.sink().buffer().use { sink ->
            PreferencesSerializer.writeTo(preferencesOf(intPreferencesKey("epg_refresh_hours") to 17), sink)
        }
        val prefs = preferences(dir)
        assertEquals(17, withTimeout(5_000) { prefs.settings.first() }.epgRefreshHours)
        assertFalse(prefs.currentSettings().updatePlaylistsOnOpen)
        prefs.updateSettings { it.copy(updatePlaylistsOnOpen = true) }
        prefs.updateSettings { it.copy(playlistRefreshHours = 6) }
        assertTrue(prefs.currentSettings().updatePlaylistsOnOpen)
        prefs.updateSettings { it.copy(updatePlaylistsOnOpen = false) }
        assertFalse(prefs.currentSettings().updatePlaylistsOnOpen)
    }
}
