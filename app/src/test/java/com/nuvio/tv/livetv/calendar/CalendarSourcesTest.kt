package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.data.tvdb.TvdbEpisode
import com.nuvio.tv.data.tvdb.TvdbEpisodeOrder
import com.nuvio.tv.domain.model.EpisodeCoordinates
import dagger.Lazy
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
    private val series = CalendarTitle("tt101", "Fixture", false, "tt101", null, 0)

    @Test fun episodeLookupFollowsTheShowIdentityAndOnlyReadsSeasonsInTheWindow() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            paths += path
            val response = Response.Builder().protocol(Protocol.HTTP_1_1).request(request).message("fixture").code(200)
            val json = when (path) {
                "/lookup/shows" -> {
                    response.code(301).header("Location", "https://api.tvmaze.com/shows/12/fixture")
                    ""
                }
                "/shows/12/seasons" -> """[{"id":3,"endDate":"2011-01-01"},{"id":4,"premiereDate":"2026-12-01"}]"""
                "/seasons/4/episodes" -> """[{"season":2,"number":3,"name":"Final","airdate":"2026-12-05"}]"""
                else -> throw AssertionError(path)
            }
            response.body(json.toResponseBody()).build()
        }.build()
        val sources = CalendarSources(client, Lazy { error("No Trakt lookup expected") }, Lazy { error("No TMDB lookup expected") })
        val rows = sources.listings(1, series, CalendarWindow.around(LocalDate.parse("2026-12-04")), ZoneId.of("Australia/Sydney"), "AU")
        assertEquals(listOf("/lookup/shows", "/shows/12/seasons", "/seasons/4/episodes"), paths)
        assertEquals(CalendarKind.EPISODE, rows.single().kind)
        assertEquals("Final", rows.single().detail)
    }

    @Test fun timedEpisodesCrossMidnightInSydneyAndDateOnlyEpisodesKeepTheirListedDay() {
        val ep = JSONObject("""{"season":2,"number":3,"name":"Final","airdate":"2026-12-31",
            "airtime":"20:00","airstamp":"2026-12-31T20:00:00-05:00"}""")
        val zone = ZoneId.of("Australia/Sydney")
        val timed = CalendarSources.parseEpisode(series, ep, zone)!!
        assertEquals(LocalDate.of(2027, 1, 1).toEpochDay(), timed.day)
        assertEquals(3, timed.episode)
        val untimed = CalendarSources.parseEpisode(series, ep.put("airtime", ""), zone)!!
        assertEquals(LocalDate.of(2026, 12, 31).toEpochDay(), untimed.day)
        assertNull(untimed.time)
    }

    @Test fun movieReleasesUseTheEarliestDateOfEachTypeInTheSelectedCountry() {
        val releases = JSONArray("""[{"iso_3166_1":"US","release_dates":[{"type":4,"release_date":"2026-11-01"}]},
            {"iso_3166_1":"AU","release_dates":[{"type":3,"release_date":"2026-12-03"},
            {"type":2,"release_date":"2026-12-01"},{"type":4,"release_date":"2027-01-02"},
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
            {"country":"au","release_type":"digital","release_date":"2026-12-16"},
            {"country":"au","release_type":"physical","release_date":"2027-02-18"},
            {"country":"us","release_type":"digital","release_date":"2026-12-02"}]""")
        assertEquals(mapOf(CalendarKind.CINEMA to LocalDate.parse("2026-12-01").toEpochDay(),
            CalendarKind.DIGITAL to LocalDate.parse("2026-12-16").toEpochDay(),
            CalendarKind.DISC to LocalDate.parse("2027-02-18").toEpochDay()), CalendarSources.traktMovieDays(releases, "AU"))
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
