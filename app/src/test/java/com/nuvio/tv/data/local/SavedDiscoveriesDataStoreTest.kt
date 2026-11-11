package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.domain.model.SavedDiscovery
import io.mockk.mockk
import io.mockk.every
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedDiscoveriesDataStoreTest {
    private val profileId = MutableStateFlow(1)
    private val firstProfile = MemoryPreferences()
    private val secondProfile = MemoryPreferences()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(1, "advanced_discover") } returns firstProfile
        every { get(2, "advanced_discover") } returns secondProfile
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns profileId }
    private val store = SavedDiscoveriesDataStore(factory, profiles)

    @Test
    fun `save update and delete are persisted only to the captured profile`() = runTest {
        val one = SavedDiscovery("one", "Movies", DiscoveryQuery())
        val two = SavedDiscovery("two", "Shows", DiscoveryQuery(television = true))
        store.save(one, 1)
        profileId.value = 2
        assertTrue(store.discoveries.first().isEmpty())
        store.save(two, 2)
        assertEquals(listOf(two), store.discoveries.first())
        val updated = one.copy(name = "Highly rated movies", query = one.query.copy(minimumRating = "8"))
        store.save(updated, 1)
        store.delete("one", 2)
        assertEquals(listOf(two), store.discoveries.first())
        profileId.value = 1
        assertEquals(listOf(updated), store.discoveries.first())
        store.delete("one", 1)
        assertTrue(store.discoveries.first().isEmpty())
    }

    @Test
    fun `all filters survive a saved discovery round trip`() {
        val saved = SavedDiscovery("one", "Short crime films", DiscoveryQuery(genres = setOf(80, 53), excludedGenres = setOf(27),
            matchAllGenres = true, minimumVotes = "1000", minimumRating = "7", originalLanguage = "en",
            dateFrom = "2015-01-01", dateTo = "2026-11-03", runtimeFrom = "60", runtimeTo = "120",
            certificationCountry = "AU", certification = "PG", hideWatched = true, sort = "release_date.asc", titleContains = "Alien"))
        assertEquals(listOf(saved), store.parse(Gson().toJson(listOf(saved))))
    }

    @Test
    fun `older saved searches without certification fields remain readable`() {
        val saved = SavedDiscovery("old", "Old movies", DiscoveryQuery())
        val json = Gson().toJson(listOf(saved))
            .replace("\"certificationCountry\":\"AU\",", "")
            .replace("\"certification\":\"\",", "")
        assertEquals(listOf(saved), store.parse(json))
    }

    @Test
    fun `older saved discoveries without title text still match any title`() {
        val saved = SavedDiscovery("old", "Old movies", DiscoveryQuery())
        val json = Gson().toJson(listOf(saved)).replace(",\"titleContains\":\"\"", "")
        val parsed = store.parse(json)
        assertEquals(listOf(saved), parsed)
        assertTrue(parsed.single().query.matchesTitle("Anything"))
    }

    @Test
    fun `title filter is persisted and remains profile local`() = runTest {
        val saved = SavedDiscovery("one", "Alien films", DiscoveryQuery(titleContains = " Alien "))
        store.save(saved, 1)
        assertEquals(listOf(saved), store.discoveries.first())
        profileId.value = 2
        assertTrue(store.discoveries.first().isEmpty())
        profileId.value = 1
        assertEquals(" Alien ", store.discoveries.first().single().query.titleContains)
    }

    @Test
    fun `corrupt or invalid records cannot break saved discovery picker`() {
        assertTrue(store.parse("{broken").isEmpty())
        assertTrue(store.parse("[null,{}]").isEmpty())
        assertTrue(store.parse(null).isEmpty())
        val invalid = SavedDiscovery("one", "Invalid", DiscoveryQuery(minimumRating = "bad"))
        val valid = SavedDiscovery("two", "Valid", DiscoveryQuery())
        assertEquals(listOf(valid), store.parse(Gson().toJson(listOf(invalid, valid, valid))))
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
