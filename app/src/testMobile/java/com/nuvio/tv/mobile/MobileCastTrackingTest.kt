package com.nuvio.tv.mobile

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class MobileCastTrackingTest {
    private val rows = mockk<WatchProgressRepository>(relaxed = true)
    private val scrobbles = mockk<TrackingScrobbleCoordinator>(relaxed = true)
    private val profile = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(8) }
    private val tracker = MobileCastTracking(rows, scrobbles, profile, mockk(), mockk())
    private val episode = MobileVideo(8, "series", "tt1234567", "tt1234567:2:7", "Fixture", 2, 7, "Episode", "provider")

    @Test fun remoteClockDrivesOrderedStartPauseSeekAndStopForTheOriginalProfile() {
        fun observe(position: Long, playing: Boolean, clock: Long) =
            tracker.observe(CastSample(episode, position, 1_800_000, playing, clock))
        observe(120_000, true, 0)
        observe(125_000, false, 5000)
        observe(125_000, true, 7000)
        observe(900_000, true, 8000)
        tracker.finish(true)
        coVerify(timeout = 8000) { scrobbles.scrobble(TrackingScrobbleAction.STOP, match {
            it.progressPercent == 100.0 && it.media.catalog?.videoId == episode.videoId
        }) }
        coVerifyOrder {
            scrobbles.scrobble(TrackingScrobbleAction.START, any())
            scrobbles.scrobble(TrackingScrobbleAction.PAUSE, any())
            scrobbles.scrobble(TrackingScrobbleAction.START, any())
            scrobbles.scrobbleSeek(TrackingScrobbleAction.PAUSE, match { !it.allowCompletion })
            scrobbles.scrobbleSeek(TrackingScrobbleAction.START, match { !it.allowCompletion })
            rows.markAsCompleted(match { it.videoId == episode.videoId && it.providerDefaultPlayback }, 8, false)
            scrobbles.scrobble(TrackingScrobbleAction.STOP, any())
        }
    }

    @Test fun shortProviderErrorClipsCannotCompleteTheEpisode() {
        tracker.observe(CastSample(episode, 4000, 6000, true, 0))
        tracker.finish(true)
        coVerify(exactly = 0) { rows.markAsCompleted(any(), any<Int>(), any()) }
        coVerify(exactly = 0) { scrobbles.scrobble(any(), any()) }
    }
}
