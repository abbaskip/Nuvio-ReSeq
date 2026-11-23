package com.nuvio.tv.mobile

import com.nuvio.tv.data.cw.*
import com.nuvio.tv.data.local.DeviceWatchEvent
import com.nuvio.tv.data.local.DeviceWatchEventStore
import com.nuvio.tv.data.mdblist.*
import com.nuvio.tv.data.repository.survivingNextUpSeeds
import com.nuvio.tv.domain.model.TvdbPlaybackContext
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobileContinueWatchingUndoTest {
    @get:Rule val rename = AndroidAtomicRenameRule()
    @get:Rule val folder = TemporaryFolder()
    private val journal = DeviceWatchEventStore(mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
    private val office = MdbListMedia(MdbListIds(imdb = "tt0386676"), "The Office")
    private fun watched(season: Int, episode: Int, id: Long, date: String) =
        mdbListTestEpisode(season, episode, date).copy(media = office, episodeTvdbId = id)

    @Test fun undoneAlt2WatchUsesRemainingMdbListHistoryWithEitherPreferenceAfterRestart() = runTest {
        val earlier = watched(3, 2, 302, "2026-11-21T12:00:00Z")
        val furthest = watched(6, 16, 616, "2026-10-21T12:00:00Z")
        val last = watched(6, 17, 617, "2026-11-23T12:00:00Z")
        val snapshot = MdbListSyncSnapshot(1, watched = listOf(earlier, furthest, last))
        val source = MdbListProgressProjection(snapshot)
        val raw = source.nextUp(true).single()
        val context = TvdbPlaybackContext(73244, "alttwo", 100617, 6, 19, listOf(617), "tt0386676:6:19")
        val removed = raw.copy(episode = 19, videoId = context.requestVideoId, tvdbContext = context, deviceWatchEventId = "last")
        val event = DeviceWatchEvent("last", removed, removed.lastWatched, removed.lastWatched, removed = true)
        for (preferFurthest in listOf(true, false)) {
            val chosen = survivingNextUpSeeds(source.nextUp(preferFurthest), listOf(removed), preferFurthest,
                { journal.decorate(it, listOf(event)) }, source::nextUpHistory).single()
            assertEquals(if (preferFurthest) 616L else 302L, chosen.tvdbEpisodeId)
            val store = CwProjectionStore(folder.root)
            val original = CwProjection(profileId = 1, source = "MDBLIST")
            val before = CwMembershipReducer.progress(original, listOf(removed))
            val after = CwMembershipReducer.removeDeviceWatchEvent(before, event)
            val rebuilt = CwMembershipReducer.progress(after, listOf(chosen), seeds = true)
            store.write(rebuilt)
            val restarted = CwProjectionStore(folder.root).read(1, "MDBLIST")
            assertEquals(chosen.tvdbEpisodeId, restarted.members.getValue(raw.contentId).progress!!.tvdbEpisodeId)
            val confirmed = MdbListProgressProjection(snapshot.copy(watched = listOf(earlier, furthest)))
            val refreshed = survivingNextUpSeeds(confirmed.nextUp(preferFurthest), emptyList(), preferFurthest,
                { journal.decorate(it, listOf(event)) }, confirmed::nextUpHistory)
            assertEquals(chosen.tvdbEpisodeId, refreshed.single().tvdbEpisodeId)
        }
        val only = MdbListProgressProjection(snapshot.copy(watched = listOf(last)))
        assertTrue(survivingNextUpSeeds(only.nextUp(true), listOf(removed), true,
            { journal.decorate(it, listOf(event)) }, only::nextUpHistory).isEmpty())
    }
}
