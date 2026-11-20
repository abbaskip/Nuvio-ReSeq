package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.consumeCancellableResponse
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.core.tmdb.tmdbPrivateClient
import dagger.Lazy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

/**
 * Adapted from CalendarSources in homelessbrian/NuvioPlusIPTV (f85adc128). The source
 * lookups, season windows, date-only episodes and regional movie release types come from
 * that implementation. ReSeq persists only the resulting listings and cancels HTTP on exit.
 */
internal class CalendarSources @Inject constructor(
    client: OkHttpClient,
    @param:Named("trakt") private val traktClient: Lazy<OkHttpClient>,
    private val credentials: Lazy<TmdbCredentialStore>
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).build()
    private val publicTrakt by lazy { traktClient.get().newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).build() }
    private val privateTmdb by lazy { tmdbPrivateClient().newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
    private val mazeGate = Mutex()
    private var lastMazeCall = 0L

    suspend fun listings(profile: Int, title: CalendarTitle, window: CalendarWindow, zone: ZoneId, country: String): List<CalendarItem> =
        if (title.movie) movie(profile, title, window, country) else episodes(title, window, zone)

    private suspend fun request(url: String, lookup: Boolean = false, client: OkHttpClient = http): String? {
        return client.newCall(Request.Builder().url(url).build()).consumeCancellableResponse { response ->
            when {
                response.code == 404 -> null
                lookup && response.isRedirect -> response.header("Location")
                    ?.let { Regex("/shows/(\\d+)").find(it)?.groupValues?.get(1) }
                    ?: throw IOException("Unknown TVmaze redirect")
                !response.isSuccessful -> throw IOException("Schedule service HTTP " + response.code)
                else -> {
                    val stream = response.body.source()
                    if (stream.request(MAX_RESPONSE + 1)) throw IOException("Schedule response too large")
                    stream.readUtf8().let { if (lookup) JSONObject(it).getInt("id").toString() else it }
                }
            }
        }
    }

    private suspend fun maze(path: String, lookup: Boolean = false): String? = mazeGate.withLock {
        val elapsed = System.nanoTime() / 1_000_000
        delay((520L - (elapsed - lastMazeCall)).coerceAtLeast(0))
        lastMazeCall = System.nanoTime() / 1_000_000
        request("https://api.tvmaze.com$path", lookup)
    }

    private suspend fun tmdb(path: String, key: String): JSONObject? {
        val url = "https://api.themoviedb.org/3$path".toHttpUrl().newBuilder().addQueryParameter("api_key", key).build()
        return request(url.toString(), client = privateTmdb)?.let(::JSONObject)
    }

    private suspend fun trakt(path: String): JSONArray? =
        request(BuildConfig.TRAKT_API_URL.ifBlank { "https://api.trakt.tv" }.trimEnd('/') + path, client = publicTrakt)?.let(::JSONArray)

    private suspend fun traktIds(title: CalendarTitle): JSONObject? {
        val external = title.imdb?.let { "imdb/$it" } ?: title.tmdb?.let { "tmdb/$it" } ?: return null
        val kind = if (title.movie) "movie" else "show"
        return trakt("/search/$external?type=$kind")?.objects()?.firstOrNull { it.optString("type") == kind }
            ?.optJSONObject(kind)?.optJSONObject("ids")
    }

    private suspend fun episodes(title: CalendarTitle, window: CalendarWindow, zone: ZoneId): List<CalendarItem> {
        val direct = title.id.removePrefix("tvmaze:").takeIf { title.id.startsWith("tvmaze:") }?.toIntOrNull()
        val id = direct ?: title.imdb?.let { maze("/lookup/shows?imdb=$it", lookup = true)?.toIntOrNull() } ?: run {
            val directTvdb = title.id.removePrefix("tvdb:").takeIf { title.id.startsWith("tvdb:") }?.toLongOrNull()
            val tvdb = directTvdb ?: traktIds(title)?.optLong("tvdb")?.takeIf { it > 0 }
            tvdb?.let { maze("/lookup/shows?thetvdb=$it", lookup = true)?.toIntOrNull() }
        } ?: return emptyList()
        val seasons = maze("/shows/$id/seasons")?.let(::JSONArray) ?: return emptyList()
        val result = mutableListOf<CalendarItem>()
        // TVmaze may retain decades of daily programming. Like + IPTV, only request the
        // latest two seasons which can overlap the visible weeks.
        val overlap = seasons.objects().filter { season ->
            val start = parseDay(season.optString("premiereDate"))
            val end = parseDay(season.optString("endDate"))
            (start == null || start <= window.last + 1) && (end == null || end >= window.first - 1)
        }.toList().takeLast(2)
        for (season in overlap) {
            val seasonId = season.optInt("id").takeIf { it > 0 } ?: continue
            val episodes = maze("/seasons/$seasonId/episodes")?.let(::JSONArray) ?: continue
            episodes.objects().mapNotNull { parseEpisode(title, it, zone) }
                .filterTo(result) { it.day in window }
        }
        return result
    }

    private suspend fun movie(profile: Int, title: CalendarTitle, window: CalendarWindow, country: String): List<CalendarItem> {
        val key = credentials.get().access(profile).apiKey
        val dates = if (key.isBlank()) {
            val id = title.imdb ?: traktIds(title)?.optLong("trakt")?.takeIf { it > 0 }?.toString() ?: return emptyList()
            val releases = trakt("/movies/$id/releases/" + country.lowercase(Locale.ROOT)) ?: return emptyList()
            traktMovieDays(releases, country)
        } else {
            val id = title.tmdb ?: title.imdb?.let {
                tmdb("/find/$it?external_source=imdb_id", key)?.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id")
            }?.takeIf { it > 0 } ?: return emptyList()
            val releases = tmdb("/movie/$id/release_dates", key)?.getJSONArray("results") ?: return emptyList()
            movieDays(releases, country)
        }
        return dates.filterValues { it in window }.map { (kind, day) ->
            CalendarItem(title.key + "/" + kind.name, title.key, title.title, day, kind, contentId = title.id)
        }
    }

    companion object {
        private const val MAX_RESPONSE = 2L * 1024 * 1024

        internal fun parseEpisode(title: CalendarTitle, ep: JSONObject, zone: ZoneId): CalendarItem? {
            val season = ep.optInt("season", -1)
            val number = ep.optInt("number", -1)
            if (season < 0 || number <= 0) return null
            val stamp = ep.optString("airstamp").takeIf { it.isNotBlank() }?.let {
                runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            }
            val time = stamp.takeIf { ep.optString("airtime").isNotBlank() }
            val day = time?.calendarDay(zone) ?: parseDay(ep.optString("airdate")) ?: stamp?.calendarDay(zone) ?: return null
            return CalendarItem(title.key + "/$season/$number", title.key, title.title, day, CalendarKind.EPISODE,
                detail = ep.optString("name").take(240), time = time, contentId = title.id,
                season = season, episode = number, airedDate = ep.optString("airdate").take(10))
        }

        internal fun movieDays(results: JSONArray, country: String): Map<CalendarKind, Long> {
            val local = results.objects().firstOrNull { it.optString("iso_3166_1").equals(country, ignoreCase = true) }
            val releases = local?.optJSONArray("release_dates") ?: return emptyMap()
            return earliest(releases.objects()) { release ->
                when (release.optInt("type")) {
                    2, 3 -> CalendarKind.CINEMA
                    4 -> CalendarKind.DIGITAL
                    5 -> CalendarKind.DISC
                    else -> null
                }
            }
        }

        internal fun traktMovieDays(releases: JSONArray, country: String): Map<CalendarKind, Long> =
            earliest(releases.objects().filter { it.optString("country").equals(country, ignoreCase = true) }) {
                when (it.optString("release_type")) {
                    "limited", "theatrical" -> CalendarKind.CINEMA
                    "digital" -> CalendarKind.DIGITAL
                    "physical" -> CalendarKind.DISC
                    else -> null
                }
            }

        private fun earliest(releases: Sequence<JSONObject>, kindOf: (JSONObject) -> CalendarKind?): Map<CalendarKind, Long> {
            val dates = mutableMapOf<CalendarKind, Long>()
            for (release in releases) {
                val kind = kindOf(release) ?: continue
                val day = parseDay(release.optString("release_date").take(10)) ?: continue
                dates[kind] = minOf(dates[kind] ?: day, day)
            }
            return dates
        }

        private fun parseDay(value: String): Long? = runCatching { LocalDate.parse(value).toEpochDay() }.getOrNull()
        private fun JSONArray.objects(): Sequence<JSONObject> = (0 until length()).asSequence().mapNotNull(::optJSONObject)
    }
}
