package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WatchedSeriesStateHolderProfileTest {

    private lateinit var tempDir: File
    private lateinit var factory: ProfileDataStoreFactory
    private lateinit var holder: WatchedSeriesStateHolder
    private val activeProfileFlow = MutableStateFlow(1)

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("watched_series_holder_test").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns tempDir
        }
        factory = ProfileDataStoreFactory(context)
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfileFlow
        }
        holder = WatchedSeriesStateHolder(factory, profileManager)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun changedSeedIgnoresFreshValidationForThePreviousEpisode() = runTest {
        holder.loadFromDisk()
        holder.updateWithValidation(setOf("show"), setOf("show"), nextUpSeeds = mapOf("show" to "finale"))
        org.junit.Assert.assertTrue(holder.isSeedValidationFresh("show", "finale"))
        org.junit.Assert.assertFalse(holder.isSeedValidationFresh("show", "rewatch"))
        factory.get(1, "watched_series_cache").data.first {
            it[androidx.datastore.preferences.core.stringPreferencesKey("validated_next_up_seeds")] != null
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
