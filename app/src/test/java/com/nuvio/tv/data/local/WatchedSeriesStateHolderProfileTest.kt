package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class WatchedSeriesStateHolderProfileTest {

    private lateinit var factory: ProfileDataStoreFactory
    private lateinit var holder: WatchedSeriesStateHolder
    private val activeProfileFlow = MutableStateFlow(1)

    // Exercise the holder's committed preferences and per-profile reloads without
    // Windows raw-file readers racing DataStore's atomic file replacement.
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    @Before
    fun setUp() {
        val stores = ConcurrentHashMap<Pair<Int, String>, MemoryPreferences>()
        factory = mockk {
            every { get(any(), any()) } answers {
                stores.computeIfAbsent(firstArg<Int>() to secondArg<String>()) { MemoryPreferences() }
            }
        }
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfileFlow
        }
        holder = WatchedSeriesStateHolder(factory, profileManager)
    }

    @Test
    fun changedSeedIgnoresFreshValidationForThePreviousEpisode() = runTest {
        holder.loadFromDisk()
        holder.updateWithValidation(setOf("show"), setOf("show"), nextUpSeeds = mapOf("show" to "finale"))
        org.junit.Assert.assertTrue(holder.isSeedValidationFresh("show", "finale"))
        org.junit.Assert.assertFalse(holder.isSeedValidationFresh("show", "rewatch"))
        val store = factory.get(1, "watched_series_cache")
        val seedsKey = stringPreferencesKey("validated_next_up_seeds")
        // The holder writes asynchronously; wait for its committed preferences before
        // switching profiles and verifying that the seed survives a reload.
        withContext(Dispatchers.IO) {
            withTimeout(10_000L) {
                while (store.data.first()[seedsKey] == null) delay(10)
            }
        }
        holder.loadFromDisk(2)
        org.junit.Assert.assertFalse(holder.isSeedValidationFresh("show", "finale"))
        holder.loadFromDisk(1)
        org.junit.Assert.assertTrue(holder.isSeedValidationFresh("show", "finale"))
    }

    @Test
    fun `loading another profile replaces the previous profile's series`() = runTest {
        factory.get(1, "watched_series_cache").edit { it[stringSetPreferencesKey("fully_watched_ids")] = setOf("tt0000001") }

        holder.loadFromDisk(profileId = 1)
        assertEquals(setOf("tt0000001"), holder.fullyWatchedSeriesIds.value)

        activeProfileFlow.value = 2
        holder.loadFromDisk(profileId = 2)
        assertEquals(emptySet<String>(), holder.fullyWatchedSeriesIds.value)

        activeProfileFlow.value = 1
        holder.loadFromDisk(profileId = 1)
        assertEquals(setOf("tt0000001"), holder.fullyWatchedSeriesIds.value)
    }
}
