package com.nuvio.tv.livetv.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.*
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import com.nuvio.tv.domain.model.TvMix
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DrivePlaybackProfileTest {
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val stores = mutableMapOf<Pair<Int, String>, Store>()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(any(), any()) } answers { stores.getOrPut(firstArg<Int>() to secondArg<String>(), ::Store) }
    }
    private val active = MutableStateFlow(2)
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
    private val layout = LayoutPreferenceDataStore(factory, profiles)
    private val shuffle = EpisodeShuffleStore(factory, profiles, layout)
    private val mixes = TvMixStore(factory, profiles)

    private fun envelope() = JSONObject().put("format", 1).put("data", JSONObject())
    private fun text(value: String) = JSONObject().put("t", "str").put("v", value)

    @Test fun transferFromDifferentTvProfilePreservesOtherItemsAndMakesShuffleAvailable() = runTest {
        val tv = 4
        mixes.save(tv, TvMix("sitcom", "Friday night"))
        shuffle.save("tvdb:42", EpisodeShuffleSettings(true, true), tv)
        val backup = envelope()
        backup.getJSONObject("data").apply {
            put("reseq_profile:4", text("Living room"))
            put("reseq_profile:1", text("Main"))
            put("reseq_mix_version", JSONObject().put("t", "i").put("v", 1))
            put("reseq_shuffle_version", JSONObject().put("t", "i").put("v", 1))
            mixes.export(tv).forEach { (key, raw) -> put("reseq_mix:4:$key", text(raw)) }
            shuffle.exportSelections(tv).forEach { (id, raw) -> put("reseq_shuffle:4:$id", text(raw)) }
        }
        mixes.save(2, TvMix("local", "On the phone"))
        shuffle.save("tt123", EpisodeShuffleSettings(false, true), 2)
        val preview = playbackProfilesFromBackup(backup)
        assertEquals(listOf("Main", "Living room"), preview.map { it.name })
        assertTrue(preview.first().mixes.isEmpty())
        repeat(2) { preview.last().copyTo(2, mixes, shuffle) }
        assertEquals(setOf("sitcom", "local"), mixes.observe(2).first().map { it.id }.toSet())
        assertEquals(setOf("tvdb:42", "tt123"), shuffle.exportSelections(2).keys)
        assertTrue(shuffle.observeProfile(2).first().settings("tvdb:42", "series").enabled)
        assertTrue(mixes.observe(1).first().isEmpty())
        layout.setRandomEpisodeEnabled(false)
        preview.last().copyTo(2, mixes, shuffle)
        assertFalse(shuffle.observeProfile(2).first().available)
    }

    @Test fun oldBackupsHaveNoPlaybackProfilesAndInvalidChoicesFailBeforeRestore() {
        val backup = envelope()
        assertTrue(playbackProfilesFromBackup(backup).isEmpty())
        backup.getJSONObject("data").apply {
            put("reseq_profile:3", text("TV"))
            put("reseq_shuffle_version", JSONObject().put("t", "i").put("v", 1))
            put("reseq_shuffle:3:tvdb:42", text("{}"))
        }
        assertThrows(IllegalArgumentException::class.java) { playbackProfilesFromBackup(backup) }
    }
}
