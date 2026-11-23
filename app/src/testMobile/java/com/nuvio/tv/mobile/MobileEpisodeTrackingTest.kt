package com.nuvio.tv.mobile

import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.detail.MetaDetailsUiState
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class MobileEpisodeTrackingTest {
    private val display = EpisodeCoordinates(2, 1)
    private val native = EpisodeCoordinates(1, 3)
    private val id = EpisodeIdentity(78, 42, "dvd", display, native, providerVideoId = "show:1:3", providerCoordinates = native)
    private val video = Video("show:1:3", "The third aired episode", null, null, season = 2, episode = 1, overview = null, episodeIdentity = id)
    private val pause = WatchProgress("show", "series", "Show", null, null, null, video.id, 1, 3, video.title, 40, 100, 1000,
        deviceWatchEventId = "second-viewing", tvdbEpisodeIds = listOf(78))

    @Test fun projectedRecordAndSourcePendingKeyFollowTheSelectedPart() {
        val unrelated = pause.copy(episode = 1, deviceWatchEventId = "other", position = 100)
        val state = MetaDetailsUiState(episodeProgressMap = mapOf((2 to 1) to pause, (1 to 3) to unrelated),
            episodeWatchedPendingKeys = setOf("show:1:3:1:3"))
        val result = state.trackingForEpisode(video)
        assertSame(pause, result.progress)
        assertTrue(result.pending)
        assertFalse(result.watched)
        assertTrue(pause.hasRemovableWatchEvent())
        assertTrue(pause.hasRemovableEpisodePause())
        assertFalse(pause.copy(position = 100).hasRemovableEpisodePause())
        assertFalse(pause.copy(deviceWatchEventId = null).hasRemovableWatchEvent())
    }

    @Test fun unknownAndUnverifiedPartsCannotBorrowWatchHistory() {
        val state = MetaDetailsUiState(episodeProgressMap = mapOf((0 to 0) to pause, (2 to 1) to pause))
        val sibling = video.copy(id = "other", episodeIdentity = id.copy(tvdbEpisodeId = 79))
        val ambiguous = listOf(video, sibling).ambiguousEpisodeSlots()
        assertFalse(state.trackingForEpisode(video, (2 to 1) in ambiguous).canTrack)
        for (unmapped in listOf(video.copy(episodeIdentity = null, season = null, episode = null),
            video.copy(episodeIdentity = id.copy(contentVerified = false)),
            video.copy(episodeIdentity = id.copy(providerVideoId = null)))) {
            val result = state.trackingForEpisode(unmapped)
            assertNull(result.progress)
            assertFalse(result.canTrack)
        }
    }

    @Test fun localDateAndOptionalTimeSurviveOffsetChangesAndRejectFutureOrSkippedTimes() {
        val zone = ZoneId.of("Australia/Sydney")
        val now = Instant.parse("2026-11-23T13:10:00Z")
        val date = LocalDate.of(2026, 11, 24)
        assertEquals(Instant.parse("2026-11-23T13:00:00Z"), selectedWatchInstant(date, null, zone, now))
        assertEquals(Instant.parse("2026-11-23T13:07:00Z"), selectedWatchInstant(date, 7, zone, now))
        assertNull(selectedWatchInstant(date, 11, zone, now))
        assertNull(selectedWatchInstant(LocalDate.of(2026, 10, 4), 150, zone, now))
        assertEquals(Instant.parse("2026-06-17T14:00:00Z"), selectedWatchInstant(LocalDate.of(2026, 6, 18), null, zone, now))
    }
}
