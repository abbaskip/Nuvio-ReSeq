package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SimklEpisodePauseRemovalTest {
    @Test fun `removing a mapped partial rewatch deletes its session and retains completed history and siblings`() = runTest {
        val parent = "tt0386676"
        val media = SimklMedia(title = "The Office", ids = mapOf("imdb" to JsonPrimitive(parent)))
        val episode = SimklPlaybackEpisode(season = 6, number = 16)
        val old = SimklPlaybackSession(id = 31, progress = 1.0, pausedAt = "2026-11-10T00:00:00Z",
            type = "episode", show = media, episode = episode)
        val current = old.copy(id = 32, pausedAt = "2026-11-17T00:00:00Z", progress = .5)
        val sibling = current.copy(id = 33, episode = episode.copy(number = 15))
        val history = listOf(SimklLibraryEntry(mediaType = SimklMediaType.SHOWS, show = media,
            status = SimklListStatus.COMPLETED, seasons = listOf(SimklSeason(6,
                listOf(SimklEpisode(16, watchedAt = "2026-01-01T00:00:00Z"))))))
        val state = MutableStateFlow(SimklSyncState(snapshot = SimklSyncSnapshot(isInitialized = true,
            entries = history, playback = listOf(old, current, sibling))))
        val sync = mockk<SimklSyncRepository>(relaxed = true) {
            every { this@mockk.state } returns state
            every { projection } returns MutableStateFlow(SimklSnapshotProjection.Empty)
        }
        coEvery { sync.removePlaybackSessions(any()) } coAnswers {
            val ids = firstArg<Set<Long>>()
            state.value = state.value.copy(snapshot = state.value.snapshot.copy(
                playback = state.value.snapshot.playback.filterNot { it.id in ids }))
        }
        val requests = mutableListOf<Pair<String, String>>()
        val client = SimklApiClient(SimklHttpEngine { method, url, _, _ ->
            val path = java.net.URI(url).path
            requests += method to path
            SimklRawHttpResponse(200, if (method == "GET") Json.encodeToString(listOf(old, current, sibling)) else "{}")
        }, SimklApiConfiguration("fixture", "nuvio", "test"), { testSimklAuthorization() }, {})
        val provider = SimklTrackingProgressProvider(sync, client, mockk(relaxed = true), mockk(relaxed = true))
        val resume = WatchProgress(parent, "series", "The Office", null, null, null, "$parent:6:17", 6, 17,
            "The Delivery Part 1 (Extended Cut)", 0, 0, 3_000, progressPercent = .5f,
            source = WatchProgress.SOURCE_SIMKL_PLAYBACK, simklPlaybackId = 32,
            tvdbCompletedEpisodeIds = listOf(7931991))
        provider.removeEpisodePause(buildTrackingMediaReference("series", parent, "$parent:6:16",
            seasonNumber = 6, episodeNumber = 16), resume)
        assertEquals(listOf("GET" to "/sync/playback", "DELETE" to "/sync/playback/32"), requests)
        assertEquals(history, state.value.snapshot.entries)
        assertEquals(listOf(old, sibling), state.value.snapshot.playback)
    }
}
