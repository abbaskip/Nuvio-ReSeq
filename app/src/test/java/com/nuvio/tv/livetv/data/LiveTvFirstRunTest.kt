package com.nuvio.tv.livetv.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        store.edit { it[intPreferencesKey("epg_refresh_hours")] = 17 }
        job.cancelAndJoin()
        val prefs = preferences(dir)
        assertEquals(17, withTimeout(5_000) { prefs.settings.first() }.epgRefreshHours)
    }
}
