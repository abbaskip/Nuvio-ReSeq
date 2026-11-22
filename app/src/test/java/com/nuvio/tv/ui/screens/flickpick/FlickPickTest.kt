package com.nuvio.tv.ui.screens.flickpick

import com.nuvio.tv.data.repository.DiscoveryPage
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.MovieRatingSystem
import com.nuvio.tv.domain.model.MovieAgeLimit
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.data.remote.api.TmdbCertification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class FlickPickTest {
    private val today = LocalDate.of(2026, 11, 22)
    private fun movie(id: Int, rating: Float = 7f) = MetaPreview("tmdb:$id", ContentType.MOVIE,
        name = "Movie $id", poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
        description = null, releaseInfo = "2020", imdbRating = rating, genres = emptyList())

    @Test fun genrePressesCycleIndependentlyAndCombineAnyRequiredAndExcluded() {
        val filters = FlickPickFilters().cycle(1).cycle(1).cycle(2).cycle(3).cycle(4).cycle(4).cycle(4)
        val query = filters.query()
        assertEquals(setOf(1), query.genres)
        assertTrue(query.matchAllGenres)
        assertEquals(setOf(2, 3), filters.anyOfGenres)
        assertEquals(setOf(4), query.excludedGenres)
        assertNull(filters.cycle(4).genres[4])
        val oneAlternative = filters.copy(genres = filters.genres - 3)
        assertEquals("1,2", oneAlternative.query().tmdbParameters()["with_genres"])
        assertTrue(oneAlternative.anyOfGenres.isEmpty())
        assertEquals("2|3", filters.copy(genres = filters.genres - 1).query().tmdbParameters()["with_genres"])
    }

    @Test fun erasUseReleaseDatesAndRollingCalendarYears() {
        assertEquals("1950-01-01" to "1969-12-31", FlickPickEra.FIFTIES_SIXTIES.dates(today))
        assertEquals("1980-01-01" to "1999-12-31", FlickPickEra.EIGHTIES_NINETIES.dates(today))
        assertEquals("2000-01-01" to "2019-12-31", FlickPickEra.NOUGHTIES_TENS.dates(today))
        assertEquals("2020-01-01" to today.toString(), FlickPickEra.TWENTIES.dates(today))
        assertEquals("1996-11-22" to today.toString(), FlickPickEra.LAST_30.dates(today))
        assertEquals("" to "", FlickPickEra.ALL.dates(today))
        val custom = FlickPickFilters().withEra(FlickPickEra.LAST_5, today).copy(from = "2022-01-01", era = null)
        assertEquals("2022-01-01", custom.query().dateFrom)
        assertEquals(today.toString(), custom.query().dateTo)
    }

    @Test fun allPagesAreConsideredBeforeQualityIsRelaxed() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val result = pickDiscoveredMovie(FlickPickFilters(), today, load = { query, page, _ ->
            calls += query.minimumVotes to page
            assertEquals("release_date.asc", query.sort)
            assertEquals(today.toString(), query.dateTo)
            if (page == 1) DiscoveryPage(listOf(movie(1), movie(2, 6f)), true, 21, 2)
            else DiscoveryPage(listOf(movie(3)), false, 21, 2)
        }, unwatched = { it.takeUnless { movie -> movie.id == "tmdb:1" } })
        assertEquals("tmdb:3", result?.id)
        assertEquals(listOf("501" to 1, "501" to 2), calls)
    }

    @Test fun samplingSpansAllPagesEvenWhenPageOneHasUnwatchedMatches() = runTest {
        val picks = (0..40).map { seed ->
            pickDiscoveredMovie(FlickPickFilters(), today, load = { _, page, _ ->
                DiscoveryPage(listOf(movie(page)), page < 3, 41, 3)
            }, unwatched = { it }, random = Random(seed))!!.id
        }
        assertEquals(3, picks.distinct().size)
    }

    @Test fun recentPicksGiveWayToFreshMatchesThenRotateWhenPoolIsExhausted() = runTest {
        val filters = FlickPickFilters(keywords = "coming of age",
            genres = mapOf(12 to FlickPickGenre.ANY, 16 to FlickPickGenre.EXCLUDE, 27 to FlickPickGenre.EXCLUDE))
            .withEra(FlickPickEra.LAST_30, today)
        suspend fun pick(recent: List<String>) = pickDiscoveredMovie(filters, today, load = { query, _, _ ->
            assertEquals(setOf(16, 27), query.excludedGenres)
            assertEquals("coming of age", query.keywords)
            assertEquals("1996-11-22", query.dateFrom)
            val movies = if (query.minimumVotes == "501") listOf(movie(1)) else listOf(movie(1), movie(2))
            DiscoveryPage(movies, false, movies.size)
        }, unwatched = { it }, recent = recent)?.id
        assertEquals("tmdb:2", pick(listOf("tmdb:1")))
        assertEquals("tmdb:1", pick(listOf("tmdb:2", "tmdb:1")))
    }

    @Test fun emptySearchExhaustsOnlyQualityThresholdsKeepingUserFilters() = runTest {
        val queries = mutableListOf<DiscoveryQuery>()
        val filters = FlickPickFilters(keywords = "\"christmas\", santa").cycle(3).withEra(FlickPickEra.SEVENTIES, today)
        assertNull(pickDiscoveredMovie(filters, today, load = { query, page, _ ->
            assertEquals(1, page)
            queries += query
            DiscoveryPage(emptyList(), false, 0)
        }, unwatched = { it }))
        assertEquals(listOf("6" to "501", "5" to "250", "4" to "100", "" to ""),
            queries.map { it.minimumRating to it.minimumVotes })
        queries.forEach { assertEquals(filters.query(), it.copy(minimumRating = "", minimumVotes = "")) }
    }

    @Test fun providerFailureIsNotAnEmptySearch() = runTest {
        var calls = 0
        val failure = runCatching { pickDiscoveredMovie(FlickPickFilters(), today, load = { _, _, _ ->
            calls++
            throw java.io.IOException("offline")
        }, unwatched = { it }) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals(1, calls)
    }

    @Test fun deviceRegionDeterminesClassificationOrderAndUnclassifiedFilmsAreSkipped() {
        val levels = mapOf("DE" to listOf(TmdbCertification("12", 3), TmdbCertification("0", 1),
            TmdbCertification("16", 4), TmdbCertification("6", 2)))
        val system = MovieRatingSystem.forRegion("de", levels)
        assertEquals(listOf("0", "6", "12", "16"), system.ranks.keys.toList())
        val limit = MovieAgeLimit(system, "12")
        assertTrue(limit.accepts(listOf("", "6", "12")))
        assertFalse(limit.accepts(listOf("12", "16")))
        assertFalse(limit.accepts(emptyList()))
        assertFalse(limit.accepts(listOf("PG")))
        assertEquals("US", MovieRatingSystem.forRegion("ZZ", levels).country)
    }

    @Test fun homeListsIncludeRenamedMovieRowsAndMovieFoldersAndHonorVisibility() {
        val row = CatalogDescriptor(ContentType.MOVIE, id = "movie-list", name = "Films")
        val addon = Addon(id = "addon", name = "My addon", version = "1", description = null, logo = null,
            baseUrl = "https://example.test", catalogs = listOf(row, row.copy(id = "hidden"),
                row.copy(type = ContentType.SERIES, rawType = "series", id = "shows")),
            types = listOf(ContentType.MOVIE), resources = emptyList())
        val films = CollectionFolder("films", "Movie night", sources =
            listOf(AddonCatalogCollectionSource(addon.id, "movie", row.id, "Adventure")))
        val shows = CollectionFolder("tv", "Shows", sources =
            listOf(TraktCollectionSource("Series", 123, TmdbCollectionMediaType.TV)))
        val folder = Collection("shelves", "Shelves", folders = listOf(films, shows))
        val lists = homeMovieLists(listOf(addon), listOf(folder, folder.copy(id = "hidden")),
            setOf("collection_hidden", homeCatalogKey(addon.id, "movie", "hidden")),
            mapOf(homeCatalogKey(addon.id, "movie", row.id) to "Weekend"))
        assertEquals(listOf("Weekend · My addon", "Shelves / Movie night"), lists.map { it.tab.title })
        assertEquals("Adventure", (lists.last().sources.single() as FlickPickSource.Catalog).genre)
    }
}
