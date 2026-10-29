package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TraktTrackingScrobblerTest {
    @Test fun `estimated partial pause uses the non-completing endpoint including zero progress`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val mapper = mockk<TraktEpisodeMappingService>()
        val adapter = TraktTrackingScrobbler(service, mapper)
        val media = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(imdb = "tt123"),
            episode = com.nuvio.tv.core.tracking.TrackingEpisode(5, 14, numberingProvider = "trakt"))
        for (percent in listOf(0.0, 79.0)) {
            adapter.scrobble(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(media, percent, allowCompletion = false))
            coVerify(exactly = 1) { service.scrobblePause(any(), percent.toFloat()) }
        }
        coVerify(exactly = 0) { service.scrobbleStop(any(), any()) }
        io.mockk.confirmVerified(mapper)
    }

    @Test
    fun `verified TVDB destination is not remapped by the old positional heuristic`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val mapper = mockk<TraktEpisodeMappingService>()
        TraktTrackingScrobbler(service, mapper).scrobble(TrackingScrobbleAction.START, TrackingScrobbleEvent(
            TrackingMediaReference(kind = TrackingMediaKind.SHOW, ids = TrackingExternalIds(imdb = "tt0303461"),
                episode = com.nuvio.tv.core.tracking.TrackingEpisode(1, 6, "Shindig", 297994, numberingProvider = "trakt")),
            progressPercent = 10.0))
        coVerify(exactly = 1) { service.scrobbleStart(match { it is TraktScrobbleItem.Episode && it.number == 6 && it.season == 1 }, 10f) }
        io.mockk.confirmVerified(mapper)
    }
    @Test
    fun `pause preserves trakt stop behavior`() = runTest {
        val service = mockk<TraktScrobbleService>(relaxed = true)
        val scrobbler = TraktTrackingScrobbler(
            service = service,
            episodeMappingService = mockk(relaxed = true)
        )

        scrobbler.scrobble(
            action = TrackingScrobbleAction.PAUSE,
            event = TrackingScrobbleEvent(
                media = TrackingMediaReference(
                    kind = TrackingMediaKind.MOVIE,
                    ids = TrackingExternalIds(imdb = "tt0111161")
                ),
                progressPercent = 45.0
            )
        )

        coVerify(exactly = 1) { service.scrobbleStop(any(), 45f) }
        coVerify(exactly = 0) { service.scrobblePause(any(), any()) }
    }
}
