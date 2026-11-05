package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.sync.driveBackupData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File

class LiveTvGroupPreferencesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context(): Context {
        val files = temp.newFolder()
        // Initialise empty fixture files before asynchronous startup collectors read them.
        // Windows createNewFile briefly takes an exclusive handle; racing it with the
        // first DataStore read otherwise makes this persistence test nondeterministic.
        val directory = File(files, "datastore").apply { mkdirs() }
        listOf("live_tv", "live_tv_device").forEach {
            File(directory, "$it.preferences_pb").createNewFile()
        }
        return mockk<Context>().also { context ->
            every { context.applicationContext } returns context
            every { context.filesDir } returns files
        }
    }
    @Test fun `storage import policy and favourite group channel orders survive one backup restore`() = runBlocking {
        val context = context()
        val active = MutableStateFlow(1)
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns active
        val prefs = LiveTvPreferences(context, profiles)
        val source = PlaylistSource("fixture", "Fixture", "https://example.invalid/list",
            importVod = true, vodMovies = false, vodSourceOnly = true, vodSeriesCategories = setOf("en"))
        val group = "fixture::Sport"
        prefs.updatePlaylists { listOf(source) }
        prefs.toggleFavoriteGroup(group)
        prefs.setGroupOrder(listOf(group, "unavailable-group"))
        prefs.setChannelOrder(group, listOf("channel-b", "channel-a"))
        val data = driveBackupData(JSONObject().put("format", 1).put("data", prefs.exportForSync()))
        prefs.importFromSync(JSONObject())
        assertTrue(prefs.currentPlaylists().isEmpty())
        assertTrue(prefs.userState.first().favoriteGroups.isEmpty())
        prefs.importFromSync(data)
        assertEquals(source.vodImportPolicy(), prefs.currentPlaylists().single().vodImportPolicy())
        val restored = prefs.userState.first()
        assertEquals(setOf(group), restored.favoriteGroups)
        assertEquals(listOf(group, "unavailable-group"), restored.groupOrder)
        assertEquals(listOf("channel-b", "channel-a"), restored.channelOrder[group])
        active.value = 2
        prefs.toggleFavoriteGroup("second-profile")
        active.value = 1
        assertEquals(setOf(group), prefs.userState.first().favoriteGroups)
        assertEquals(source.vodImportPolicy(), prefs.currentPlaylists().single().vodImportPolicy())
    }
    @Test fun `favourite groups toggle persist and stay profile scoped`() = runBlocking {
        val context = context()
        val active = MutableStateFlow(1)
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns active
        val prefs = LiveTvPreferences(context, profiles)
        prefs.toggleFavoriteGroup("playlist-a:sport")
        assertEquals(setOf("playlist-a:sport"), withTimeout(5000) { prefs.userState.first() }.favoriteGroups)
        active.value = 2
        prefs.toggleFavoriteGroup("playlist-b:sport")
        assertEquals(setOf("playlist-a:sport", "playlist-b:sport"), withTimeout(5000) { prefs.userState.first() }.favoriteGroups)
        active.value = 1
        assertEquals(setOf("playlist-a:sport"), withTimeout(5000) { prefs.userState.first() }.favoriteGroups)
        prefs.toggleFavoriteGroup("playlist-a:sport")
        assertTrue(prefs.userState.first().favoriteGroups.isEmpty())
    }
    @Test fun `saving group order retains unavailable groups and removes duplicates`() = runBlocking {
        val context = context()
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val prefs = LiveTvPreferences(context, profiles)
        prefs.setGroupOrder(listOf("hidden", "a", "b"))
        prefs.setGroupOrder(listOf("b", "a", "b"))
        assertEquals(listOf("b", "a", "hidden"), withTimeout(5000) { prefs.userState.first() }.groupOrder)
    }

    @Test fun `unfavouriting a legacy single-playlist group removes the old alias`() = runBlocking {
        val context = context()
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val prefs = LiveTvPreferences(context, profiles)
        prefs.toggleFavoriteGroup("Sport")
        prefs.toggleFavoriteGroup("one::Sport", "Sport")
        assertTrue(prefs.userState.first().favoriteGroups.isEmpty())
        prefs.toggleFavoriteGroup("one::Sport", "Sport")
        assertEquals(setOf("one::Sport"), prefs.userState.first().favoriteGroups)
    }
}
