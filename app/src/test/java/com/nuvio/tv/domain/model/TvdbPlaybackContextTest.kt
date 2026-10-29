package com.nuvio.tv.domain.model

import com.nuvio.tv.data.tvdb.forNuvioCloud
import org.junit.Assert.*
import org.junit.Test

class TvdbPlaybackContextTest {
    private val combined = TvdbPlaybackContext(73244, "alttwo", 10718533, 5, 14,
        listOf(427605, 427606), "tt0386676:5:14", 44)

    @Test fun `short or unknown video duration cannot complete a verified combined story`() {
        assertFalse(combined.permitsTracking(22 * 60_000L))
        assertFalse(combined.permitsTracking(0))
        assertFalse(combined.copy(expectedRuntimeMinutes = null).permitsTracking(44 * 60_000L))
        assertTrue(combined.permitsTracking(44 * 60_000L))
        assertEquals(listOf(427605L, 427606L), combined.episodeIds)
    }

    @Test fun `normal extended cuts and individual parts use actual runtime without duration ratios`() {
        val episode = combined.copy(episodeIds = listOf(427605), expectedRuntimeMinutes = 22)
        assertTrue(episode.permitsTracking(30 * 60_000L))
        assertTrue(episode.permitsTracking(22 * 60_000L))
        // Duration never expands a part's content to include its neighbour.
        assertTrue(episode.permitsTracking(44 * 60_000L))
        assertEquals(listOf(427605L), episode.episodeIds)
    }

    @Test fun `captured order content and duration guard survive local JSON and legacy key decoding`() {
        assertEquals(combined, TvdbPlaybackContext.decode(combined.encode()))
        assertEquals(combined, TvdbPlaybackContext.fromStorageKey("tt0386676", combined.storageKey("tt0386676"), combined.requestVideoId))
        assertNull(TvdbPlaybackContext.decode("provider"))
    }

    @Test fun `ambiguous combined completion remains resumable without a watched marker`() {
        val progress = WatchProgress("tt0386676", "series", "Office", null, null, null, combined.requestVideoId,
            5, 14, "Lecture Circuit", 22 * 60_000L, 22 * 60_000L, 1, tvdbContext = combined,
            tvdbCoverageVerified = combined.permitsTracking(22 * 60_000L))
        assertFalse(progress.isCompleted())
        assertTrue(progress.isInProgress())
        assertTrue(progress.tvdbWatchedIds.isEmpty())
        assertEquals(22 * 60_000L, progress.resolveResumePosition(44 * 60_000L))
    }
    @Test fun `native cloud coverage proof survives JSON while legacy contexts safely withhold progress`() {
        val native = EpisodeSourceIdentity("tt0386676:5:14", EpisodeCoordinates(5, 14), combined.episodeIds, "Example")
        val context = combined.copy(providerSources = listOf(native))
        assertEquals(context, TvdbPlaybackContext.decode(context.encode()))
        val progress = WatchProgress("tt0386676", "series", "Example", null, null, null, combined.requestVideoId,
            5, 16, null, 500_000, 2_800_000, 1, tvdbContext = context)
        val remote = progress.forNuvioCloud().single()
        assertEquals(14, remote.episode)
        assertEquals(progress.position, remote.position)
        assertNull(remote.tvdbContext)
        assertTrue(progress.copy(tvdbContext = combined).forNuvioCloud().isEmpty())
        assertTrue(progress.copy(tvdbCoverageVerified = false).forNuvioCloud().none { it.isCompleted() })
        assertTrue(progress.copy(tvdbContext = context.copy(providerSources = listOf(native.copy(episodeIds = listOf(427605))))).forNuvioCloud().isEmpty())
        val providerDefault = progress.copy(tvdbContext = null)
        assertSame(providerDefault, providerDefault.forNuvioCloud().single())
    }

}
