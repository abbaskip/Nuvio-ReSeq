package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TvdbContinueWatchingCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `changing Office order retains its last snapshot until replacement across restart and profiles`() = runTest {
        val profile = MutableStateFlow(1)
        val profiles = mockk<ProfileManager> { every { resolvedActiveProfileId } returns profile; every { activeProfileId } returns profile }
        val context = mockk<Context> { every { filesDir } returns folder.root }
        val settings = mockk<TraktSettingsDataStore> {
            coEvery { getWatchProgressSource(any()) } returns WatchProgressSource.TRAKT
        }
        fun repository() = com.nuvio.tv.data.cw.ContinueWatchingRepository(context, profiles, settings,
            javax.inject.Provider { mockk() })
        fun cache() = ContinueWatchingEnrichmentCache(repository())
        val original = cache()
        val office = CachedInProgressItem("tt0386676", "series", "Office", null, null, null, "office:1:1", 1, 1,
            "Pilot", 10, 100, 1, null, tvdbContext = com.nuvio.tv.domain.model.TvdbPlaybackContext(
                73244, "alttwo", 789, 1, 1, listOf(789), "office:1:1"))
        val other = office.copy(contentId = "other-show", name = "Other show")
        val next = CachedNextUpItem("other-show", "series", "Other show", null, null, null, "other:1:2", 1, 2,
            "Next", thumbnail = null, lastWatched = 1, sortTimestamp = 1, tvdbEpisodeId = 789)
        val dir = java.io.File(folder.root, "cw_enrichment").apply { mkdirs() }
        java.io.File(dir, "inprogress_1.json").writeText(com.google.gson.Gson().toJson(listOf(office, other)))
        java.io.File(dir, "nextup_1.json").writeText(com.google.gson.Gson().toJson(listOf(next.copy(contentId = "next-show"))))
        original.getSnapshot()
        profile.value = 2
        java.io.File(dir, "inprogress_2.json").writeText(com.google.gson.Gson().toJson(listOf(office)))
        original.getSnapshot()
        profile.value = 1
        original.invalidateContent(1, setOf(office.contentId))
        assertEquals(listOf(office, other), cache().getInProgressSnapshot())
        assertEquals(listOf(next.copy(contentId = "next-show")), cache().getNextUpSnapshot())
        assertEquals(0, original.cacheCleared.value)
        assertEquals(setOf(office.contentId), original.contentInvalidated.value?.contentIds)
        // Only explicit removal can revoke membership; partial metadata cannot do so.
        original.repository.remove(setOf(office.contentId))
        assertEquals(listOf(other), cache().getInProgressSnapshot())
        assertEquals(listOf(next.copy(contentId = "next-show")), cache().getNextUpSnapshot())
        profile.value = 2
        assertEquals(listOf(office), cache().getInProgressSnapshot())
    }
}
