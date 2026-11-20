package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.consumeCancellableResponse
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.core.tmdb.tmdbPrivateClient
import dagger.Lazy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

/** The region-specific movie release types follow NuvioPlusIPTV (f85adc128). Episode requests are by week. */
internal class CalendarSources @Inject constructor(
    @param:Named("trakt") private val traktClient: Lazy<OkHttpClient>,
    private val credentials: Lazy<TmdbCredentialStore>
) {
    private val publicTrakt by lazy { traktClient.get().newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).build() }
    private val privateTmdb by lazy { tmdbPrivateClient().newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
    /** Match the week's first airings locally. The request size is independent of the size of watch history. */
    suspend fun scheduledEpisodes(watched: Map<String, String>, week: CalendarWindow, zone: ZoneId): List<CalendarItem> {
        if (watched.isEmpty()) return emptyList()
        val start = LocalDate.ofEpochDay(week.first).atStartOfDay(zone).toInstant().atZone(ZoneOffset.UTC).toLocalDate()
        val last = LocalDate.ofEpochDay(week.last + 1).atStartOfDay(zone).toInstant().minusMillis(1).atZone(ZoneOffset.UTC).toLocalDate()
        val days = last.toEpochDay() - start.toEpochDay() + 1
        val rows = trakt("/calendars/all/shows/$start/$days?status=returning%20series,continuing,in%20production,planned,upcoming,pilot")
            ?: throw IOException("Episode calendar unavailable")
        return scheduledEpisodes(rows, watched, week, zone)
    }

    /** For larger movie libraries, discover releases by week before checking the matched films' regional dates. */
    suspend fun movieCandidates(profile: Int, movies: List<CalendarTitle>, week: CalendarWindow, region: String): List<CalendarTitle> {
        if (movies.size <= CalendarPolicy.BATCH) return movies
        val first = LocalDate.ofEpochDay(week.first)
        val last = LocalDate.ofEpochDay(week.last)
        val aliases = mutableSetOf<String>()
        val regionalTitles = mutableSetOf<String>()
        val key = credentials.get().access(profile).apiKey
        if (key.isNotBlank()) {
            var page = 1
            do {
                val result = tmdb("/discover/movie?region=$region&with_release_type=2%7C3%7C4%7C5&release_date.gte=$first&release_date.lte=$last&page=$page", key)
                    ?: throw IOException("Regional movie calendar unavailable")
                result.getJSONArray("results").objects().forEach { film ->
                    aliases += "tmdb:" + film.getInt("id")
                    listOf("title", "original_title").map { film.optString(it).trim().lowercase(Locale.ROOT) }
                        .filterTo(regionalTitles) { it.isNotEmpty() }
                }
                page++
            } while (page <= result.optInt("total_pages").coerceAtMost(500))
        }
        // IMDb-only libraries also need the IDs exposed by the public release calendars.
        if (key.isBlank() || movies.any { it.tmdb == null }) {
            for (feed in listOf("movies", "streaming", "dvd")) {
                val rows = trakt("/calendars/all/$feed/$first/7") ?: throw IOException("Movie calendar unavailable")
                rows.objects().forEach { row -> row.optJSONObject("movie")?.optJSONObject("ids")?.let { aliases += mediaIds(it) } }
            }
        }
        // A name match only earns an ID-based regional lookup; it never supplies the displayed date.
        return movies.filter { film -> (film.tmdb == null && film.title.lowercase(Locale.ROOT) in regionalTitles) ||
            film.id in aliases || film.imdb in aliases ||
            (film.tmdb != null && "tmdb:" + film.tmdb in aliases) || (film.trakt != null && "trakt:" + film.trakt in aliases) }
    }

    private suspend fun request(url: String, client: OkHttpClient): String? =
        client.newCall(Request.Builder().url(url).build()).consumeCancellableResponse { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("Calendar service HTTP " + response.code)
                else -> {
                    val stream = response.body.source()
                    if (stream.request(MAX_RESPONSE + 1)) throw IOException("Calendar response too large")
                    stream.readUtf8()
                }
            }
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

    suspend fun movie(profile: Int, title: CalendarTitle, window: CalendarWindow, country: String): List<CalendarItem> {
        val key = credentials.get().access(profile).apiKey
        val dates = if (key.isBlank() || (title.tmdb == null && title.imdb == null)) {
            val id = title.imdb ?: title.trakt?.toString() ?: traktIds(title)?.optLong("trakt")?.takeIf { it > 0 }?.toString() ?: return emptyList()
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
        private val paidOffer = Regex("""\b(?:rent(?:al)?|purchase|buy|pvod|tvod|digital sell[- ]through)\b""", RegexOption.IGNORE_CASE)

        private fun mediaIds(ids: JSONObject): List<String> = buildList {
            ids.optString("imdb").takeIf { it.startsWith("tt") }?.let(::add)
            for (type in listOf("tmdb", "trakt", "tvdb")) {
                ids.optLong(type).takeIf { it > 0 }?.let { add("$type:$it") }
            }
        }

        internal fun scheduledEpisodes(rows: JSONArray, watched: Map<String, String>, week: CalendarWindow, zone: ZoneId): List<CalendarItem> =
            rows.objects().mapNotNull { row ->
                val show = row.optJSONObject("show") ?: return@mapNotNull null
                if (show.optString("status").lowercase(Locale.ROOT) in setOf("ended", "canceled", "cancelled")) return@mapNotNull null
                val ids = show.optJSONObject("ids") ?: return@mapNotNull null
                val id = mediaIds(ids).firstNotNullOfOrNull(watched::get) ?: return@mapNotNull null
                val episode = row.optJSONObject("episode") ?: return@mapNotNull null
                val season = episode.optInt("season", -1)
                val number = episode.optInt("number", 0)
                val name = show.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (season < 0 || number <= 0) return@mapNotNull null
                val stamp = row.optString("first_aired")
                val time = runCatching { OffsetDateTime.parse(stamp).toInstant().toEpochMilli() }.getOrNull()
                val suppliedDay = parseDay(if (time == null) stamp else stamp.take(10))
                    ?: parseDay(row.optString("released").take(10))
                val day = time?.calendarDay(zone) ?: suppliedDay ?: return@mapNotNull null
                if (day !in week) return@mapNotNull null
                val source = "series/" + id
                CalendarItem("$source/$season/$number", source, name.take(240), day, CalendarKind.EPISODE,
                    detail = episode.optString("title").take(240), time = time, contentId = id,
                    season = season, episode = number, airedDate = LocalDate.ofEpochDay(suppliedDay ?: day).toString())
            }.distinctBy { it.key }.toList()

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
                if (kind == CalendarKind.DIGITAL && !paidOffer.containsMatchIn(release.optString("note"))) continue
                val day = parseDay(release.optString("release_date").take(10)) ?: continue
                dates[kind] = minOf(dates[kind] ?: day, day)
            }
            return dates
        }

        private fun parseDay(value: String): Long? = runCatching { LocalDate.parse(value).toEpochDay() }.getOrNull()
        private fun JSONArray.objects(): Sequence<JSONObject> = (0 until length()).asSequence().mapNotNull(::optJSONObject)
    }
}
