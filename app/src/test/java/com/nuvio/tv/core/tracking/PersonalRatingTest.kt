package com.nuvio.tv.core.tracking

import org.junit.Assert.*
import org.junit.Test

class PersonalRatingTest {
    @Test fun defaultMovieAndSeriesUseNativeIds() {
        assertEquals("tt1234567", personalRatingTarget("tt1234567", "movie")!!.ids.imdb)
        assertEquals("shows", personalRatingTarget("tmdb:1437", "series")!!.group)
    }
    @Test fun conflictingIdsAreRejected() {
        assertNull(personalRatingTarget("tt1234567", "movie", "tt7654321"))
        assertNull(personalRatingTarget("provider:item", "movie"))
        assertNull(personalRatingTarget("tvdb:80552", "series"))
    }
    @Test fun nativeTrackerIdsAreNotInventedForOtherServices() {
        val target = personalRatingTarget("simkl:123", "movie")!!
        assertTrue(target.supports(PersonalRatingProvider.SIMKL))
        assertFalse(target.supports(PersonalRatingProvider.TRAKT))
        assertFalse(target.supports(PersonalRatingProvider.MDBLIST))
    }
    @Test fun animeUsesSimklBucketAndReliableAnimeIds() {
        val target = personalRatingTarget("mal:123", "anime")!!
        assertEquals("anime", target.groupFor(PersonalRatingProvider.SIMKL))
        assertTrue(target.supports(PersonalRatingProvider.SIMKL))
        assertFalse(target.supports(PersonalRatingProvider.TRAKT))
    }
    @Test fun watchedMovieExitRequestsMovieRating() {
        val movie = watchedRatingRequest(2, "tt1234567", "movie", "Movie", watched = true, hasError = false)!!
        assertTrue(movie.target.movie)
        assertEquals(2, movie.profileId)
        assertEquals("Movie", movie.title)
    }

    @Test fun completingAnEpisodeNeverRequestsARatingForItsSeries() {
        for (type in listOf("series", "tv", "anime")) {
            assertNull(type, watchedRatingRequest(2, "tt0386676", type, "The Office", watched = true, hasError = false))
        }
    }

    @Test fun earlyExitErrorAndUnsupportedTargetsAreSkipped() {
        assertNull(watchedRatingRequest(1, "tt1234567", "movie", "Movie", watched = false, hasError = false))
        assertNull(watchedRatingRequest(1, "tt1234567", "movie", "Movie", watched = true, hasError = true))
        assertNull(watchedRatingRequest(1, "tt1234567", "channel", "Live", watched = true, hasError = false))
    }

    @Test fun promptRemainsOptIn() {
        assertFalse(com.nuvio.tv.data.local.PlayerSettings().promptToRateAfterWatching)
    }
}
