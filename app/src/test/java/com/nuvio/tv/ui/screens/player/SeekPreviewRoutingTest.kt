package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.domain.model.EpisodeSourceIdentity
import com.nuvio.tv.domain.model.TvdbPlaybackContext
import com.nuvio.tv.domain.model.EpisodeContentIdentity
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.tvdb.TvdbTrackingTarget
import org.junit.Assert.*
import org.junit.Test
import tv.seekr.previews.core.SeekrContent

class SeekPreviewRoutingTest {
    @Test fun missingTrackGeneratesOnlyInAutomaticMode() {
        val preview = SeekrPreviewState()
        preview.resolved(null, seekrOnly = false)
        assertTrue(preview.allowGeneration)
        preview.resolved(null, seekrOnly = true)
        assertFalse(preview.allowGeneration)
        preview.reset()
        assertNull(preview.track)
        assertFalse(preview.allowGeneration)
    }

    @Test fun onlyMovieAndEpisodeIdentitiesAreSentToSeekr() {
        val movie = PlayerUiState(contentType = "movie")
        assertEquals(SeekrContent.Movie(imdbId = "tt0133093"), seekrTitle("tt0133093", movie))
        assertEquals(SeekrContent.Movie(tmdbId = 603), seekrTitle("tmdb:603", movie))
        assertNull(seekrTitle("iptv:603", movie))
        assertNull(seekrTitle("tt0133093", movie.copy(contentType = "channel")))
        assertNull(seekrTitle("tt0133093", movie.copy(contentType = "series")))
    }

    private val first = SeekrContent.Episode(showImdbId = "tt123", season = 1, episode = 7)
    private val second = SeekrContent.Episode(showImdbId = "tt123", season = 1, episode = 8)
    private val refs = listOf(
        EpisodeSourceIdentity("tt123:1:7", EpisodeCoordinates(1, 7), listOf(41), "Part one", 20),
        EpisodeSourceIdentity("tt123:1:8", EpisodeCoordinates(1, 8), listOf(42), "Part two", 30)
    )
    private val combined = TvdbPlaybackContext(55, "dvd", 99, 2, 3, listOf(41, 42), "tt123:2:3", 50, refs,
        mapOf(41L to 20, 42L to 30))

    @Test fun dvdDoubleUsesVerifiedProviderRowsAndOffsetsAtPartBoundary() {
        val matches = combined.previewProviderMatches("tt123", null)
        val duration = 3_000_000L
        val windows = combined.previewWindows(duration, matches)
        assertEquals(first, windows.windowAt(1_199_999, duration)?.content)
        val next = requireNotNull(windows.windowAt(1_200_000, duration))
        assertEquals(second, next.content)
        assertEquals(1_800_000L, next.durationMs)
        assertEquals(5_000L, next.positionInTrack(1_205_000))
        assertEquals(next, windows.windowAt(duration, duration))
        assertTrue(combined.previewWindows(1_200_000, matches).isEmpty())
    }

    @Test fun singlePartStartsAtItsOffsetInACombinedProviderTrack() {
        val split = combined.copy(episodeIds = listOf(42), expectedRuntimeMinutes = null)
        val matches = listOf(SeekrEpisodeMatch(first, listOf(41, 42)))
        val window = split.previewWindows(1_800_000, matches).single()
        assertEquals(3_000_000, window.durationMs)
        assertEquals(1_260_000, window.positionInTrack(60_000))
        assertTrue(split.previewWindows(3_000_000, matches).isEmpty())
    }

    @Test fun wholeMatchesNeedNoEstimateButMissingOrConflictingCoverageGetsNoTrack() {
        val single = combined.copy(episodeIds = listOf(42), providerSources = emptyList(), episodeRuntimeMinutes = emptyMap())
        val exact = SeekrEpisodeMatch(second, listOf(42))
        assertEquals(second, single.previewWindows(65_000, listOf(exact)).single().content)
        assertTrue(single.previewWindows(65_000, listOf(exact, exact.copy(content = first))).isEmpty())
        assertTrue(single.previewWindows(65_000, listOf(SeekrEpisodeMatch(first, listOf(41, 42)))).isEmpty())
        assertTrue(single.copy(episodeIds = emptyList()).previewWindows(65_000, listOf(exact)).isEmpty())
        assertTrue(combined.previewProviderMatches("tt999", null).isEmpty())
        assertTrue(combined.copy(providerSources = listOf(refs[0].copy(videoId = "tt123:2:3")))
            .previewProviderMatches("tt123", null).isEmpty())
    }

    @Test fun missingSecondPartFallsBackOnlyForThatPart() {
        val matches = listOf(SeekrEpisodeMatch(first, listOf(41)))
        val windows = combined.previewWindows(3_000_000, matches)
        assertEquals(first, windows.windowAt(0, 3_000_000)?.content)
        assertNull(windows.windowAt(1_200_000, 3_000_000))
    }

    @Test fun translatedEpisodeUsesTheVerifiedDestinationShowAndSeason() {
        val record = EpisodeContentIdentity(42, "Part two", null, EpisodeCoordinates(1, 8), listOf(42))
        val target = TvdbTrackingTarget(TrackingEpisode(4, 12, tvdbId = 42), setOf(42), TrackingExternalIds(tmdb = 678))
        val match = requireNotNull(target.previewEpisodeMatch(listOf(record)))
        assertEquals(SeekrContent.Episode(showTmdbId = 678, season = 4, episode = 12), match.content)
        assertEquals(listOf(42L), match.episodeIds)
        assertNull(target.previewEpisodeMatch(emptyList()))
    }

    @Test fun launchContextAndSavedOrderBlockDisplayedNumbersUntilMetadataArrives() = kotlinx.coroutines.runBlocking {
        val ui = PlayerUiState(contentType = "series", currentSeason = 2, currentEpisode = 3)
        suspend fun route(selected: TvdbPlaybackContext?, explicit: Boolean = false, saved: Boolean = false, provider: Boolean = false) =
            seekrWindows("tt123", ui, 3_000_000, selected, null, provider, explicit, { saved }, { _, _ -> error("No metadata") })
        assertEquals(first, route(combined, explicit = true).first().content)
        assertTrue(route(null, explicit = true).isEmpty())
        assertTrue(route(null, saved = true).isEmpty())
        val numbered = SeekrContent.Episode(showImdbId = "tt123", season = 2, episode = 3)
        assertEquals(numbered, route(null).single().content)
        assertEquals(numbered, route(null, provider = true, saved = true).single().content)
    }
}
