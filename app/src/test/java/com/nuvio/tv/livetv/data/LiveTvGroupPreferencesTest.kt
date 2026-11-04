package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
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

class LiveTvGroupPreferencesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `favourite groups toggle persist and stay profile scoped`() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temp.newFolder()
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
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temp.newFolder()
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val prefs = LiveTvPreferences(context, profiles)
        prefs.setGroupOrder(listOf("hidden", "a", "b"))
        prefs.setGroupOrder(listOf("b", "a", "b"))
        assertEquals(listOf("b", "a", "hidden"), withTimeout(5000) { prefs.userState.first() }.groupOrder)
    }
}
