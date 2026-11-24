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

    @Test fun linkedMdbListHistoryAdvancesInTheRecordedOfficeAltTwoOrder() = runTest {
        val order = com.nuvio.tv.data.tvdb.officeOrderFixtures().getValue("alttwo")
        val meta = com.nuvio.tv.data.tvdb.tvdbEpisodeOverlay(com.nuvio.tv.data.tvdb.recordedProvider(order), 73244, order)
        val history = decodeMdbListWatched("""{"episodes":[{"watched_at":"2026-11-24T00:19:00Z",
            "episode":{"season":6,"number":18,"ids":{"tmdb":501},"tvdb_ids":{"series":73244,"episode":1692591}},
            "show":{"ids":{"imdb":"tt0386676"}}}]}""")
        val raw = MdbListProgressProjection(MdbListSyncSnapshot(1, watched = history.items)).nextUp(false).single()
        val prior = raw.copy(episode = 17, tvdbEpisodeId = 1775891, lastWatched = raw.lastWatched - 60000)
        val seasonFour = raw.copy(season = 4, episode = 1, tvdbEpisodeId = 10718498, lastWatched = prior.lastWatched - 60000)
        val next = com.nuvio.tv.ui.screens.detail.SeriesNextToWatch(mockk(relaxed = true))
        for ((rows, expected) in listOf(listOf(seasonFour, prior, raw) to 21, listOf(seasonFour, prior) to 20)) {
            val watched = com.nuvio.tv.data.tvdb.reconcileTvdbProgress(meta, emptyMap(), rows)
            for (furthest in listOf(false, true)) {
                val offer = next.resolve(meta.id, "series", meta.name, meta.videos, true, null, watched, emptySet(), furthest) { emptyMap() }
                assertEquals(6 to expected, offer.nextSeason to offer.nextEpisode)
            }
        }
    }

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
