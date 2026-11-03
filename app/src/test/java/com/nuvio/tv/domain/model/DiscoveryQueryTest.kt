package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryQueryTest {
    @Test
    fun `movie discovery combines filters with provider sorting`() {
        val query = DiscoveryQuery(genres = setOf(53, 80), excludedGenres = setOf(27),
            dateFrom = "2015-01-01", dateTo = "2026-11-03", originalLanguage = "en",
            minimumRating = "7.5", minimumVotes = "1000", runtimeFrom = "80", runtimeTo = "120",
            sort = "release_date.desc")
        assertNull(query.validationError())
        assertEquals(mapOf("include_adult" to "false", "sort_by" to "primary_release_date.desc",
            "with_genres" to "53|80", "without_genres" to "27", "primary_release_date.gte" to "2015-01-01",
            "primary_release_date.lte" to "2026-11-03", "with_original_language" to "en",
            "vote_average.gte" to "7.5", "vote_count.gte" to "1000", "with_runtime.gte" to "80", "with_runtime.lte" to "120"),
            query.tmdbParameters())
    }

    @Test
    fun `TV discovery uses first aired ranges ended status and ascending dates`() {
        val parameters = DiscoveryQuery(television = true, dateFrom = "2020-01-01", dateTo = "2024-12-31",
            genres = setOf(18, 35), matchAllGenres = true, seriesStatus = "3", sort = "release_date.asc").tmdbParameters()
        assertEquals("first_air_date.asc", parameters["sort_by"])
        assertEquals("2020-01-01", parameters["first_air_date.gte"])
        assertEquals("2024-12-31", parameters["first_air_date.lte"])
        assertEquals("18,35", parameters["with_genres"])
        assertEquals("3", parameters["with_status"])
        assertFalse(parameters.containsKey("primary_release_date.gte"))
    }

    @Test
    fun `local watched filtering and TV-only status are never sent for movies`() {
        val query = DiscoveryQuery(hideWatched = true, seriesStatus = "4")
        assertEquals(mapOf("include_adult" to "false", "sort_by" to "popularity.desc"), query.tmdbParameters())
        assertEquals(DiscoveryQuery().tmdbParameters(), query.tmdbParameters())
    }

    @Test
    fun `impossible dates inverted ranges and malformed numbers cannot execute`() {
        val invalid = listOf(
            DiscoveryQuery(dateFrom = "2025-02-29") to DiscoveryValidationError.DATE,
            DiscoveryQuery(dateFrom = "2024-2-01") to DiscoveryValidationError.DATE,
            DiscoveryQuery(dateFrom = "2025-12-31", dateTo = "2025-01-01") to DiscoveryValidationError.DATE_ORDER,
            DiscoveryQuery(minimumRating = "NaN") to DiscoveryValidationError.RATING,
            DiscoveryQuery(minimumRating = "10.1") to DiscoveryValidationError.RATING,
            DiscoveryQuery(minimumVotes = "-1") to DiscoveryValidationError.VOTES,
            DiscoveryQuery(minimumVotes = "2147483648") to DiscoveryValidationError.VOTES,
            DiscoveryQuery(runtimeFrom = "90.5") to DiscoveryValidationError.RUNTIME,
            DiscoveryQuery(runtimeFrom = "120", runtimeTo = "90") to DiscoveryValidationError.RUNTIME_ORDER,
            DiscoveryQuery(genres = setOf(18), excludedGenres = setOf(18)) to DiscoveryValidationError.GENRES,
            DiscoveryQuery(originalLanguage = "English") to DiscoveryValidationError.OPTIONS,
            DiscoveryQuery(sort = "unknown.desc") to DiscoveryValidationError.OPTIONS
        )
        invalid.forEach { (query, expected) ->
            assertEquals(expected, query.validationError())
            assertEquals(true, runCatching { query.tmdbParameters() }.isFailure)
        }
        assertNull(DiscoveryQuery(dateFrom = "2024-02-29", dateTo = "2024-02-29", minimumRating = "0", minimumVotes = "0").validationError())
    }

    @Test
    fun `all available sort directions preserve their meaning for each media type`() {
        listOf(false, true).forEach { tv ->
            DiscoveryQuery.SORTS.forEach { sort ->
                val actual = DiscoveryQuery(television = tv, sort = sort).tmdbParameters().getValue("sort_by")
                assertEquals(sort.substringAfterLast('.'), actual.substringAfterLast('.'))
                if (!sort.startsWith("release_date.")) assertEquals(sort, actual)
            }
        }
    }
}
