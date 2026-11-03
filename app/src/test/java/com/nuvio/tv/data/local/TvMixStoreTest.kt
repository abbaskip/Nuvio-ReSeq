package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.TvMix
import com.nuvio.tv.domain.model.TvMixShow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TvMixStoreTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private val active = MutableStateFlow(1)
    private val stores = mutableMapOf<Pair<Int, String>, MemoryPreferences>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>(), ::MemoryPreferences) }
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
    private fun store() = TvMixStore(factory, profiles)
    private val show = TvMixShow("tt123", "Example", "https://example.test/poster.jpg")
    private val mix = TvMix("mix", "Food & travel").adding(show)

    @Test fun `create add rename preference remove and delete survive store recreation`() = runTest {
        store().save(1, mix)
        store().add(1, mix.id, show)
        assertEquals(mix, store().observe(1).first().single())
        store().save(1, mix.copy(name = "Renamed", unwatchedOnly = true))
        assertTrue(store().observe(1).first().single().unwatchedOnly)
        assertEquals("Renamed", store().observe(1).first().single().name)
        store().save(1, mix.copy(shows = emptyList()))
        assertTrue(store().observe(1).first().single().shows.isEmpty())
        store().delete(1, mix.id)
        assertTrue(store().observe(1).first().isEmpty())
    }

    @Test fun `profile switch does not redirect an in flight write`() = runTest {
        active.value = 2
        store().save(1, mix)
        assertTrue(store().mixes.first().isEmpty())
        assertEquals(mix, store().observe(1).first().single())
        store().save(2, mix.copy(name = "Profile two"))
        assertEquals("Profile two", store().mixes.first().single().name)
        assertEquals(mix, store().observe(1).first().single())
    }

    @Test fun `backup roundtrip restores shows names artwork and unwatched mode`() = runTest {
        store().save(1, mix.copy(unwatchedOnly = true))
        val exported = store().export(1)
        store().restore(2, exported)
        assertEquals(store().observe(1).first(), store().observe(2).first())
        store().restore(2, emptyMap())
        assertTrue(store().observe(2).first().isEmpty())
        assertFalse(store().observe(1).first().isEmpty())
    }

    @Test fun `invalid backup is rejected before existing mixes are replaced`() = runTest {
        store().save(1, mix)
        try { store().restore(1, mapOf("mix:wrong" to store().export(1).values.single())); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(mix, store().observe(1).first().single())
        try { store().restore(1, mapOf("mix:mix" to "{}")); fail() }
        catch (_: Exception) { }
        assertEquals(mix, store().observe(1).first().single())
    }
}
