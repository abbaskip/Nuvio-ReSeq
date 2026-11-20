package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.core.tmdb.TmdbCredentialAccess
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.tvdb.TvdbEpisode
import com.nuvio.tv.data.tvdb.TvdbEpisodeOrder
import com.nuvio.tv.domain.model.EpisodeCoordinates
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalendarSourcesTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val monday = LocalDate.parse("2026-12-28").toEpochDay()
    private val week = CalendarWindow(monday, monday + 6)
    private fun airing(imdb: String, status: String, stamp: String, number: Int = 1) = JSONObject()
        .put("show", JSONObject().put("title", "Fixture").put("status", status).put("ids", JSONObject().put("imdb", imdb)))
        .put("episode", JSONObject().put("season", 2).put("number", number).put("title", "Final"))
        .put("first_aired", stamp).put("released", "2026-12-31")

    @Test fun fourThousandWatchedIdsUseOneWeeklyRequestAndOnlyActiveWatchedShowsAppear() = runBlocking {
        val requests = mutableListOf<okhttp3.HttpUrl>()
        val body = JSONArray().put(airing("tt222", "continuing", "2026-12-31T20:00:00-05:00"))
            .put(airing("tt223", "ended", "2026-12-31T10:00:00Z"))
            .put(airing("tt224", "canceled", "2026-12-31T10:00:00Z"))
            .put(airing("tt99999", "continuing", "2026-12-31T10:00:00Z")).toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url
            Response.Builder().protocol(Protocol.HTTP_1_1).request(chain.request()).message("fixture").code(200)
                .body(body.toResponseBody()).build()
        }.build()
        val source = CalendarSources(Lazy { client }, Lazy { error("No TMDB request for episodes") })
        assertTrue(source.scheduledEpisodes(emptyMap(), week, zone).isEmpty())
        val history = (1..4000).associate { "tt" + it to "tt" + it }
        val rows = source.scheduledEpisodes(history, week, zone)
        val url = requests.single()
        assertEquals("/calendars/all/shows/2026-12-27/8", url.encodedPath)
        assertTrue("ended" !in url.queryParameter("status").orEmpty())
        assertEquals("tt222", rows.single().contentId)
        assertEquals(LocalDate.parse("2027-01-01").toEpochDay(), rows.single().day)
        assertEquals("Final", rows.single().detail)
    }

    @Test fun dateOnlyReleasesKeepTheirDayAndRepeatedOrOutOfWeekAiringsAreExcluded() {
        val row = airing("tt222", "returning series", "")
        val outside = airing("tt222", "continuing", "2027-01-10T18:00:00Z", number = 2)
        val items = CalendarSources.scheduledEpisodes(JSONArray().put(row).put(row).put(outside), mapOf("tt222" to "simkl:7"), week, zone)
        assertEquals("simkl:7", items.single().contentId)
        assertEquals(LocalDate.parse("2026-12-31").toEpochDay(), items.single().day)
        assertNull(items.single().time)
        val dateOnly = airing("tt222", "continuing", "2027-01-02").apply { remove("released") }
        val undated = airing("tt222", "continuing", "unknown", number = 2).apply { remove("released") }
        val dateRow = CalendarSources.scheduledEpisodes(JSONArray().put(dateOnly).put(undated),
            mapOf("tt222" to "tt222"), week, zone).single()
        assertEquals("2027-01-02", dateRow.airedDate)
        assertEquals(LocalDate.parse("2027-01-02").toEpochDay(), dateRow.day)
        assertNull(dateRow.time)
    }

    @Test fun movieReleasesUseTheEarliestDateOfEachTypeInTheSelectedCountry() {
        val releases = JSONArray("""[{"iso_3166_1":"US","release_dates":[{"type":4,"release_date":"2026-11-01"}]},
            {"iso_3166_1":"AU","release_dates":[{"type":3,"release_date":"2026-12-03"},
            {"type":2,"release_date":"2026-12-01"},{"type":4,"release_date":"2027-01-02","note":"PVOD"},
            {"type":5,"release_date":"2027-02-18"}]}]""")
        val days = CalendarSources.movieDays(releases, "AU")
        assertEquals(LocalDate.parse("2026-12-01").toEpochDay(), days[CalendarKind.CINEMA])
        assertEquals(LocalDate.parse("2027-01-02").toEpochDay(), days[CalendarKind.DIGITAL])
        assertEquals(LocalDate.parse("2027-02-18").toEpochDay(), days[CalendarKind.DISC])
        assertTrue(CalendarSources.movieDays(releases, "GB").isEmpty())
    }

    @Test fun publicMovieReleasesWorkWithoutALocalTmdbKey() {
        val releases = JSONArray("""[{"country":"au","release_type":"theatrical","release_date":"2026-12-03"},
            {"country":"au","release_type":"limited","release_date":"2026-12-01"},
            {"country":"au","release_type":"digital","release_date":"2026-12-16","note":"Digital purchase"},
            {"country":"au","release_type":"physical","release_date":"2027-02-18"},
            {"country":"us","release_type":"digital","release_date":"2026-12-02"}]""")
        assertEquals(mapOf(CalendarKind.CINEMA to LocalDate.parse("2026-12-01").toEpochDay(),
            CalendarKind.DIGITAL to LocalDate.parse("2026-12-16").toEpochDay(),
            CalendarKind.DISC to LocalDate.parse("2027-02-18").toEpochDay()), CalendarSources.traktMovieDays(releases, "AU"))
    }

    @Test fun largeMovieLibraryChecksRegionalDetailsOnlyForMatchingPublicReleases() = runBlocking {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            seen += path
            val contents = when (path) {
                "/calendars/all/movies/2026-12-28/7" -> """[{"movie":{"ids":{"imdb":"tt99999"}}}]"""
                "/calendars/all/streaming/2026-12-28/7" -> """[{"movie":{"ids":{"imdb":"tt221"}}}]"""
                "/calendars/all/dvd/2026-12-28/7" -> "[]"
                "/movies/tt221/releases/au" -> """[{"country":"us","release_type":"digital","note":"PVOD","release_date":"2026-12-30"},
                    {"country":"au","release_type":"physical","release_date":"2027-01-02"}]"""
                else -> error("Unexpected calendar path: " + path)
            }
            Response.Builder().protocol(Protocol.HTTP_1_1).request(chain.request()).message("fixture").code(200)
                .body(contents.toResponseBody()).build()
        }.build()
        val credentials = mockk<TmdbCredentialStore> { every { access(1) } returns TmdbCredentialAccess(1, 0, "") }
        val source = CalendarSources(Lazy { client }, Lazy { credentials })
        val films = (1..4000).map { CalendarTitle("tt" + it, "Saved " + it, true, "tt" + it, null, 0) }
        val selected = source.movieCandidates(1, films, week, "AU").single()
        assertEquals("tt221", selected.id)
        val date = source.movie(1, selected, week, "AU").single()
        assertEquals(CalendarKind.DISC, date.kind)
        assertEquals(LocalDate.parse("2027-01-02").toEpochDay(), date.day)
        assertEquals(4, seen.size)
    }

    @Test fun onlyAnExplicitOfferDescribesADigitalDateAsRentalOrPurchase() {
        fun release(note: String, day: Int) = JSONObject().put("country", "au").put("release_type", "digital")
            .put("note", note).put("release_date", "2026-12-" + day)
        val rows = JSONArray().put(release("Digital premiere", 10)).put(release("Subscription streaming premiere", 11))
        assertTrue(CalendarSources.traktMovieDays(rows, "AU").isEmpty())
        rows.put(release("Available to rent or buy", 12))
        assertEquals(mapOf(CalendarKind.DIGITAL to LocalDate.parse("2026-12-12").toEpochDay()),
            CalendarSources.traktMovieDays(rows, "AU"))
    }

    @Test fun splitAndCombinedUserOrdersUseContentCoverageAndHideUnmappedLabels() {
        fun ep(id: Long, number: Int, name: String) =
            TvdbEpisode(id, EpisodeCoordinates(1, number), name, null, null, "2026-11-20", 22)
        val combined = ep(1, 7, "The Crossing")
        val splits = listOf(ep(2, 9, "The Crossing (1)"), ep(3, 10, "The Crossing (2)"))
        val split = CalendarOrderLabels(TvdbEpisodeOrder("alternate", "alternate", splits, listOf(combined)))
        assertEquals(splits.map { it.coordinates }, split.coordinates("The Crossing", "2026-11-20"))
        val joined = CalendarOrderLabels(TvdbEpisodeOrder("alternate", "alternate", listOf(combined), splits))
        assertEquals(listOf(combined.coordinates), joined.coordinates("The Crossing (1)", "2026-11-20"))
        assertTrue(split.coordinates("Another episode", "2026-11-20").isEmpty())
    }
}
