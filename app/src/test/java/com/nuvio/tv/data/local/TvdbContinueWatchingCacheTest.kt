package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
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
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns profile }
        val context = mockk<Context> { every { filesDir } returns folder.root }
        fun cache() = ContinueWatchingEnrichmentCache(context, profiles)
        val original = cache()
        val office = CachedInProgressItem("tt0386676", "series", "Office", null, null, null, "office:1:1", 1, 1,
            "Pilot", 10, 100, 1, null, tvdbContext = com.nuvio.tv.domain.model.TvdbPlaybackContext(
                73244, "alttwo", 789, 1, 1, listOf(789), "office:1:1"))
        val other = office.copy(contentId = "other-show", name = "Other show")
        val next = CachedNextUpItem("other-show", "series", "Other show", null, null, null, "other:1:2", 1, 2,
            "Next", thumbnail = null, lastWatched = 1, sortTimestamp = 1, tvdbEpisodeId = 789)
        original.saveInProgressSnapshot(listOf(office, other), force = true)
        original.saveNextUpSnapshot(listOf(next, next.copy(contentId = office.contentId)), force = true)
        profile.value = 2
        original.saveInProgressSnapshot(listOf(office), force = true)
        profile.value = 1
        original.invalidateContent(1, setOf(office.contentId))
        assertEquals(listOf(office, other), cache().getInProgressSnapshot())
        assertEquals(listOf(next, next.copy(contentId = office.contentId)), cache().getNextUpSnapshot())
        assertEquals(0, original.cacheCleared.value)
        assertEquals(setOf(office.contentId), original.contentInvalidated.value?.contentIds)
        // A successful normal refresh replaces stale cards, rather than retaining them forever.
        original.saveInProgressSnapshot(listOf(other), force = true)
        original.saveNextUpSnapshot(listOf(next), force = true)
        assertEquals(listOf(other), cache().getInProgressSnapshot())
        assertEquals(listOf(next), cache().getNextUpSnapshot())
        profile.value = 2
        assertEquals(listOf(office), cache().getInProgressSnapshot())
    }
}
