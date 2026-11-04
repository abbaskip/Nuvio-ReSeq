package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TieredAutoPlaySettingsTest {
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        // Fix the unrelated subtitle locale so this storage test needs no Android resources.
        private val state = MutableStateFlow(initial.toMutablePreferences().apply {
            this[stringPreferencesKey("subtitle_preferred_language")] = "en"
        }.toPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }

    private class Harness(initial: Preferences = emptyPreferences()) {
        val profile = MutableStateFlow(1)
        private val stores = mutableMapOf(1 to MemoryStore(initial))
        private val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), "player_settings") } answers { synchronized(stores) { stores.getOrPut(firstArg()) { MemoryStore() } } }
        }
        private val manager = mockk<ProfileManager> { every { activeProfileId } returns profile }
        val settings = PlayerSettingsDataStore(factory, manager, mockk<Context>(relaxed = true))
        fun recreate() = PlayerSettingsDataStore(factory, manager, mockk<Context>(relaxed = true))
    }

    @Test fun `new tiers are off for existing installations`() = runTest {
        val saved = Harness().settings.playerSettings.first()
        assertFalse(saved.streamAutoPlayImmediateEnabled)
        assertTrue(saved.streamAutoPlayImmediateAddons.isEmpty())
        assertFalse(saved.streamAutoPlayAnySourceFallbackEnabled)
        assertEquals(30, saved.streamAutoPlayAnySourceFallbackSeconds)
    }

    @Test fun `immediate addons and both new toggles survive recreating settings`() = runTest {
        val harness = Harness()
        harness.settings.setStreamAutoPlayImmediateEnabled(true)
        harness.settings.setStreamAutoPlayImmediateAddons(setOf("AIOLists", "Other addon"))
        harness.settings.setStreamAutoPlayAnySourceFallbackEnabled(true)
        harness.settings.setStreamAutoPlayAnySourceFallbackSeconds(60)
        val saved = harness.recreate().playerSettings.first()
        assertTrue(saved.streamAutoPlayImmediateEnabled)
        assertEquals(setOf("AIOLists", "Other addon"), saved.streamAutoPlayImmediateAddons)
        assertTrue(saved.streamAutoPlayAnySourceFallbackEnabled)
        assertEquals(60, saved.streamAutoPlayAnySourceFallbackSeconds)
    }

    @Test fun `raising the approved timer automatically raises a shorter fallback timer`() = runTest {
        val harness = Harness()
        harness.settings.setStreamAutoPlayAnySourceFallbackSeconds(15)
        harness.settings.setStreamAutoPlayAnySourceFallbackEnabled(true)
        harness.settings.setStreamAutoPlayTimeoutSeconds(30)
        val saved = harness.settings.playerSettings.first()
        assertEquals(30, saved.streamAutoPlayTimeoutSeconds)
        assertEquals(45, saved.streamAutoPlayAnySourceFallbackSeconds)
        assertTrue(saved.streamAutoPlayAnySourceFallbackEnabled)
    }

    @Test fun `fallback setter cannot save an equal or shorter deadline`() = runTest {
        val harness = Harness()
        harness.settings.setStreamAutoPlayTimeoutSeconds(20)
        harness.settings.setStreamAutoPlayAnySourceFallbackSeconds(1)
        val saved = harness.settings.playerSettings.first()
        assertTrue(saved.streamAutoPlayAnySourceFallbackSeconds > saved.streamAutoPlayTimeoutSeconds)
    }

    @Test fun `unlimited approved waiting turns off timed fallback and prevents enabling it`() = runTest {
        val harness = Harness()
        harness.settings.setStreamAutoPlayAnySourceFallbackEnabled(true)
        harness.settings.setStreamAutoPlayTimeoutSeconds(PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED)
        assertFalse(harness.settings.playerSettings.first().streamAutoPlayAnySourceFallbackEnabled)
        harness.settings.setStreamAutoPlayAnySourceFallbackEnabled(true)
        assertFalse(harness.settings.playerSettings.first().streamAutoPlayAnySourceFallbackEnabled)
    }

    @Test fun `restored preferences cannot violate the timer ordering invariant`() = runTest {
        val restored = mutablePreferencesOf(
            intPreferencesKey("stream_auto_play_timeout_seconds") to 30,
            intPreferencesKey("stream_auto_play_any_source_fallback_seconds") to 5,
            booleanPreferencesKey("stream_auto_play_any_source_fallback_enabled") to true,
        )
        val saved = Harness(restored).settings.playerSettings.first()
        assertEquals(45, saved.streamAutoPlayAnySourceFallbackSeconds)
        assertTrue(saved.streamAutoPlayAnySourceFallbackEnabled)
    }

    @Test fun `new settings remain local to their selected profile`() = runTest {
        val harness = Harness()
        harness.settings.setStreamAutoPlayImmediateEnabled(true)
        harness.settings.setStreamAutoPlayImmediateAddons(setOf("AIOLists"))
        harness.settings.setStreamAutoPlayAnySourceFallbackEnabled(true)
        harness.settings.setStreamAutoPlayAnySourceFallbackSeconds(60)
        harness.profile.value = 2
        val other = harness.settings.playerSettings.first()
        assertFalse(other.streamAutoPlayImmediateEnabled)
        assertTrue(other.streamAutoPlayImmediateAddons.isEmpty())
        assertFalse(other.streamAutoPlayAnySourceFallbackEnabled)
        harness.profile.value = 1
        val original = harness.settings.playerSettings.first()
        assertTrue(original.streamAutoPlayImmediateEnabled)
        assertEquals(setOf("AIOLists"), original.streamAutoPlayImmediateAddons)
        assertTrue(original.streamAutoPlayAnySourceFallbackEnabled)
        assertEquals(60, original.streamAutoPlayAnySourceFallbackSeconds)
    }
}
