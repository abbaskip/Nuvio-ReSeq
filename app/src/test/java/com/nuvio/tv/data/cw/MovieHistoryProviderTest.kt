package com.nuvio.tv.data.cw

import com.nuvio.tv.data.mdblist.*
import com.nuvio.tv.data.simkl.*
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieHistoryProviderTest {
    private val id = "tt0000181"
    private val date = "2026-01-02T00:00:00Z"
    private val at = Instant.parse(date).toEpochMilli()

    @Test fun `Simkl uses watch time across aliases and never substitutes the list addition date`() {
        val entry = SimklLibraryEntry(mediaType = SimklMediaType.MOVIES,
            movie = SimklMedia(ids = mapOf("imdb" to JsonPrimitive(id), "tmdb" to JsonPrimitive(19))),
            lastWatchedAt = date, addedToWatchlistAt = "2026-03-01T00:00:00Z")
        val undated = entry.copy(lastWatchedAt = null, status = SimklListStatus.COMPLETED)
        assertNull(simklMovieCompletionTimes(listOf(undated))[id])
        val result = simklMovieCompletionTimes(listOf(entry, undated))
        assertEquals(at, result[id])
        assertEquals(at, result["tmdb:19"])
    }

    @Test fun `receiving Simkl and MDBList projections remove remote completions and allow newer rewatches`() {
        val simklMedia = SimklMedia(ids = mapOf("imdb" to JsonPrimitive(id)))
        val simklHistory = SimklLibraryEntry(mediaType = SimklMediaType.MOVIES, movie = simklMedia, lastWatchedAt = date)
        val mdbMedia = MdbListMedia(MdbListIds(imdb = id))
        val mdbHistory = MdbListWatchedRecord(MdbListItemType.MOVIE, mdbMedia, date)
        val earlier = "2026-01-01T00:00:00Z"
        val later = "2026-01-03T00:00:00Z"
        for (provider in listOf("SIMKL", "MDBLIST")) {
            fun receive(state: CwProjection, completed: Boolean, paused: String?): CwProjection {
                val (items, times) = if (provider == "SIMKL") {
                    val snapshot = SimklSyncSnapshot(entries = listOfNotNull(simklHistory.takeIf { completed }),
                        playback = listOfNotNull(paused?.let { SimklPlaybackSession(movie = simklMedia, pausedAt = it, progress = 33.0) }))
                    snapshot.toSimklProgressEntries() to simklMovieCompletionTimes(snapshot.entries)
                } else {
                    val snapshot = MdbListSyncSnapshot(accountId = 1, watched = listOfNotNull(mdbHistory.takeIf { completed }),
                        playback = listOfNotNull(paused?.let { MdbListPlayback(1L, MdbListItemType.MOVIE, mdbMedia, 33f, it) }))
                    MdbListProgressProjection(snapshot).progress to mdbListMovieCompletionTimes(snapshot.watched)
                }
                return CwMembershipReducer.reconcileWatchedMovies(CwMembershipReducer.progress(state, items), times)
            }
            val initial = receive(CwProjection(profileId = 1, source = provider), false, earlier)
            assertEquals(33f, initial.inProgress.single().progressPercent)
            for (paused in listOf(null, earlier)) {
                val finished = receive(initial, true, paused)
                assertEquals(emptySet<String>(), finished.members.keys)
                assertEquals(at, finished.removals[id])
                assertEquals(33f, receive(finished, true, later).inProgress.single().progressPercent)
            }
        }
    }

    @Test fun `MDBList uses latest movie watch time and ignores another media type with the same ID`() {
        val record = MdbListWatchedRecord(MdbListItemType.MOVIE, MdbListMedia(MdbListIds(imdb = id, tmdb = 19)), date)
        val result = mdbListMovieCompletionTimes(listOf(record, record.copy(watchedAt = "2025-01-01T00:00:00Z"),
            record.copy(type = MdbListItemType.EPISODE, watchedAt = "2026-04-01T00:00:00Z")))
        assertEquals(at, result[id])
        assertEquals(at, result["tmdb:19"])
    }
}
