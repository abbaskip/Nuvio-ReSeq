package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a 24/7 channel plays, from TMDB: a TV show ("24/7 Seinfeld"), a film series ("24/7 Harry
 * Potter" -> the Harry Potter Collection), an actor or director ("24/7 Adam Sandler" -> their
 * photo) or a single film. TMDB is asked once for everything it knows by that name (shows,
 * films and people in one search, collections in another), and the likeliest match wins:
 *
 *  - the name has to match: exactly, or as whole words at the start ("Matrix" -> "The Matrix
 *    Collection"),
 *  - what the channel or its group says it plays counts: movies / films / cinema lean to film
 *    series, people and films; TV / shows / series / sitcoms / cartoons lean to TV shows;
 *    actors / stars / legends lean to people. With none of those, 24/7 leans to TV shows,
 *    series, people and films; other 24/7 channels lean to TV shows,
 *  - then how well known it is (TMDB popularity and votes) breaks ties ("24/7 Batman").
 *
 * Results are kept on the TV (a found match for a month, "nothing" for a few days), so each
 * channel is looked up once. Uses the TMDB key the app is built with; none, nothing found.
 */
@Singleton
class LiveTv247Matcher @Inject constructor(
    private val store: LiveTvPosterStore
) {
    /** One match: what it is, its picture and what to say about it. */
    data class Match(
        /** "series", "movie", "collection" or "person". */
        val kind: String,
        val title: String,
        val poster: String,
        val backdrop: String?,
        val overview: String?,
        val year: String?,
        val rating: Float?
    )

    private val memory = ConcurrentHashMap<String, Match>()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    /** A few TMDB searches at a time (TMDB allows plenty, the TV's network might not). */
    private val permits = Semaphore(3)

    /**
     * The best match for a 24/7 channel, trying [names] in order (the cleaned-up channel name
     * first). A brand in front ("OnePlay Paw Patrol") is dropped if nothing matched as is.
     */
    suspend fun match(channel: LiveChannel, names: List<String>): Match? {
        if (names.isEmpty() || com.nuvio.tv.BuildConfig.TMDB_API_KEY.isBlank()) return null
        val lean = leanOf(channel)
        // "247tmdb3": matches from before collections won over a single film are looked up again.
        val key = "247tmdb3|" + lean.code + "|" + norm(names.first())
        memory[key]?.let { return it }
        if (key in misses) return null
        withContext(Dispatchers.IO) { store.get(key) }?.let { saved ->
            val m = if (saved.isEmpty()) null else decode(saved)
            if (m == null) misses += key else memory[key] = m
            return m
        }
        var reached = false
        val tries = buildList {
            addAll(names)
            names.first().split(' ').filter { it.isNotBlank() }.let { w ->
                if (w.size >= 2) add(w.drop(1).joinToString(" "))
                if (w.size >= 3) add(w.drop(2).joinToString(" "))
            }
        }.distinctBy { norm(it) }
            // Dropping words can leave just a genre ("Pixar Animation" -> "Animation"): skip those.
            .filter { norm(it).length >= 2 && !LiveTvPosterResolver.isGenericName(it) }
        var found: Match? = null
        for (name in tries) {
            val (m, ok) = search(name, lean)
            reached = reached || ok
            if (m != null) { found = m; break }
        }
        val result = found
        when {
            result != null -> {
                memory[key] = result
                withContext(Dispatchers.IO) { store.put(key, encode(result)) }
            }
            reached -> {
                misses += key
                withContext(Dispatchers.IO) { store.put(key, null) }
            }
        }
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "24/7 \"${names.first()}\": " + when {
                result != null -> "TMDB ${result.kind} \"${result.title}\""
                reached -> "nothing on TMDB"
                else -> "TMDB didn't answer"
            }
        )
        return result
    }

    /** The match for one name, and whether TMDB answered at all. */
    private suspend fun search(name: String, lean: Lean): Pair<Match?, Boolean> = coroutineScope {
        val movies = lean.movies
        val apiKey = com.nuvio.tv.BuildConfig.TMDB_API_KEY
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        val multi = async { get("https://api.themoviedb.org/3/search/multi?api_key=$apiKey&include_adult=false&query=$q") }
        val coll = async { get("https://api.themoviedb.org/3/search/collection?api_key=$apiKey&query=$q") }
        val multiJson = multi.await()
        val collJson = coll.await()
        if (multiJson == null && collJson == null) return@coroutineScope null to false
        val wanted = norm(name)
        data class Scored(val m: Match, val score: Double, val exact: Boolean = false, val movieId: Int = 0)
        val all = ArrayList<Scored>()

        fun nameScore(candidate: String): Double? {
            val c = norm(stripCollectionWords(candidate))
            if (c.isEmpty()) return null
            return when {
                c == wanted -> 1_000.0
                // Whole words at the start, either way round ("Matrix" vs "The Matrix Collection").
                startsWithWords(c, wanted) || startsWithWords(wanted, c) -> 350.0
                else -> null
            }
        }

        multiJson?.optJSONArray("results")?.let { results ->
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val type = r.optString("media_type")
                val popularity = r.optDouble("popularity", 0.0).coerceAtMost(150.0)
                val votes = r.optInt("vote_count", 0).coerceAtMost(10_000) / 40.0
                when (type) {
                    "tv", "movie" -> {
                        val title = if (type == "tv") r.optString("name") else r.optString("title")
                        val base = nameScore(title) ?: continue
                        val poster = r.optString("poster_path").takeIf { it.startsWith("/") } ?: continue
                        val prior = when (type) {
                            "tv" -> when {
                                lean.shows -> 380.0
                                movies || lean.people -> 0.0
                                else -> 260.0
                            }
                            else -> when {
                                lean.shows -> 0.0
                                movies -> 120.0
                                else -> 40.0
                            }
                        }
                        all += Scored(
                            Match(
                                kind = if (type == "tv") "series" else "movie",
                                title = title,
                                poster = IMG + poster,
                                backdrop = r.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { BACKDROP + it },
                                overview = r.optString("overview").takeIf { it.isNotBlank() },
                                year = (if (type == "tv") r.optString("first_air_date") else r.optString("release_date")).take(4).takeIf { it.length == 4 },
                                rating = r.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0f }
                            ),
                            base + prior + popularity + votes,
                            movieId = if (type == "movie") r.optInt("id", 0) else 0
                        )
                    }
                    "person" -> {
                        val dept = r.optString("known_for_department")
                        if (dept != "Acting" && dept != "Directing") continue
                        val base = nameScore(r.optString("name")) ?: continue
                        // People only on an exact name: "24/7 Will" mustn't become Will Smith.
                        if (base < 1_000.0) continue
                        val photo = r.optString("profile_path").takeIf { it.startsWith("/") } ?: continue
                        val knownFor = r.optJSONArray("known_for")?.let { k ->
                            (0 until k.length()).mapNotNull { j ->
                                k.optJSONObject(j)?.let { o -> o.optString("title").ifBlank { o.optString("name") }.takeIf { it.isNotBlank() } }
                            }
                        }.orEmpty()
                        all += Scored(
                            Match(
                                kind = "person",
                                title = r.optString("name"),
                                poster = IMG + photo,
                                backdrop = null,
                                overview = if (knownFor.isEmpty()) null else
                                    (if (dept == "Directing") "Films directed by " else "Films with ") + r.optString("name") +
                                        ". Known for " + knownFor.take(3).joinToString(", ") + ".",
                                year = null,
                                rating = null
                            ),
                            base + (when {
                                lean.people -> 420.0
                                lean.shows -> 60.0
                                movies -> 220.0
                                else -> 160.0
                            }) + popularity
                        )
                    }
                }
            }
        }
        collJson?.optJSONArray("results")?.let { results ->
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val title = r.optString("name")
                val base = nameScore(title) ?: continue
                val poster = r.optString("poster_path").takeIf { it.startsWith("/") } ?: continue
                all += Scored(
                    Match(
                        kind = "collection",
                        title = title,
                        poster = IMG + poster,
                        backdrop = r.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { BACKDROP + it },
                        overview = r.optString("overview").takeIf { it.isNotBlank() },
                        year = null,
                        rating = null
                    ),
                    // Collections don't carry popularity in search: a fixed lean instead, and the
                    // first result (TMDB's best guess) counts a little more.
                    base + (when {
                        movies -> 300.0
                        lean.shows -> 60.0
                        lean.people -> 120.0
                        else -> 170.0
                    }) + (if (i == 0) 40.0 else 0.0),
                    exact = base >= 1_000.0
                )
            }
        }
        // A 24/7 channel named after a film series ("BATMAN", "DIE HARD") plays the whole series,
        // not one film: a collection with exactly that name wins over a film. (A famous single
        // film used to win on popularity, so only less popular series like Blade got their
        // collection cover.) Shows and people are scored as before.
        val best = all.maxByOrNull { it.score }
        val exactCollection = all.filter { it.m.kind == "collection" && it.exact }.maxByOrNull { it.score }
        val pick = if (best != null && exactCollection != null && best.m.kind == "movie") exactCollection else best
        // Still a single film (no collection by the channel's name): a 24/7 channel plays the
        // whole series, so use the collection that film belongs to, if it's in one ("BATMAN" ->
        // The Batman -> The Batman Collection). A film on its own keeps its poster. Not for shows.
        if (pick != null && pick.m.kind == "movie" && pick.movieId > 0) {
            collectionOf(pick.movieId, apiKey)?.let { return@coroutineScope it to true }
        }
        pick?.m to true
    }

    /** The collection film [movieId] belongs to, as a match (poster, backdrop, overview). */
    private suspend fun collectionOf(movieId: Int, apiKey: String): Match? {
        val movie = get("https://api.themoviedb.org/3/movie/$movieId?api_key=$apiKey") ?: return null
        val c = movie.optJSONObject("belongs_to_collection") ?: return null
        val poster = c.optString("poster_path").takeIf { it.startsWith("/") } ?: return null
        val id = c.optInt("id", 0)
        val details = if (id > 0) get("https://api.themoviedb.org/3/collection/$id?api_key=$apiKey") else null
        return Match(
            kind = "collection",
            title = c.optString("name").ifBlank { details?.optString("name").orEmpty() },
            poster = IMG + poster,
            backdrop = c.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { BACKDROP + it },
            overview = details?.optString("overview")?.takeIf { it.isNotBlank() },
            year = null,
            rating = null
        )
    }

    private suspend fun get(url: String): JSONObject? = permits.withPermit {
        withContext(Dispatchers.IO) {
            runCatching {
                val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 8_000
                }
                try {
                    if (c.responseCode !in 200..299) null
                    else JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
        }
    }

    private fun encode(m: Match): String = JSONObject()
        .put("k", m.kind).put("t", m.title).put("p", m.poster)
        .put("b", m.backdrop ?: "").put("o", m.overview ?: "").put("y", m.year ?: "")
        .put("r", m.rating?.toDouble() ?: 0.0)
        .toString()

    private fun decode(s: String): Match? = runCatching {
        val o = JSONObject(s)
        Match(
            kind = o.getString("k"),
            title = o.getString("t"),
            poster = o.getString("p"),
            backdrop = o.optString("b").ifBlank { null },
            overview = o.optString("o").ifBlank { null },
            year = o.optString("y").ifBlank { null },
            rating = o.optDouble("r", 0.0).toFloat().takeIf { it > 0f }
        )
    }.getOrNull()

    /** Forgets every 24/7 match (with "Clear posters and show info"). */
    fun clear() {
        memory.clear()
        misses.clear()
    }

    companion object {
        private const val IMG = "https://image.tmdb.org/t/p/w500"
        private const val BACKDROP = "https://image.tmdb.org/t/p/w1280"
        private val MOVIE_WORDS = Regex("""(?i)\b(movies?|films?|cinema|kino|pel[ií]culas?|filmes?)\b""")
        private val COLLECTION_WORDS = Regex("""(?i)\b(collection|saga|trilogy|series|anthology|franchise)\b""")
        private val LEADING_ARTICLE = Regex("""(?i)^(the|a|an)\s+""")

        /** The channel or its group says it plays films. */
        private val SHOW_WORDS = Regex("""(?i)\b(tv|tv\s*shows?|shows?|series|sitcoms?|cartoons?|animated|anime|kids|telenovelas?|reality)\b""")
        private val PEOPLE_WORDS = Regex("""(?i)\b(actors?|actress(es)?|stars?|legends?|celebrit(y|ies)|directors?|icons?)\b""")

        /** What a channel's name or group says it plays. */
        data class Lean(val movies: Boolean, val shows: Boolean, val people: Boolean) {
            val code: String get() = (if (movies) "m" else "") + (if (shows) "s" else "") + (if (people) "p" else "") + "_"
        }

        fun leanOf(channel: LiveChannel): Lean {
            val texts = listOfNotNull(channel.name, channel.group, channel.groupId, channel.tvgName)
            fun any(r: Regex) = texts.any { r.containsMatchIn(it) }
            val movies = any(MOVIE_WORDS)
            val people = any(PEOPLE_WORDS)
            // "TV" alone counts only when it's not a movie channel ("Movies TV" stays movies).
            val shows = !movies && any(SHOW_WORDS)
            return Lean(movies, shows, people)
        }

        /** The channel or its group says it plays films. */
        fun isMovieChannel(channel: LiveChannel): Boolean = leanOf(channel).movies

        private fun stripCollectionWords(s: String) = s.replace(COLLECTION_WORDS, " ")

        /** Lower case, no leading "the", letters and digits only ("The Matrix" -> "matrix"). */
        fun norm(s: String): String =
            s.trim().replace(LEADING_ARTICLE, "").lowercase().replace("&", "and").filter { it.isLetterOrDigit() || it == ' ' }
                .split(' ').filter { it.isNotBlank() }.joinToString(" ")

        /** [long] begins with all of [short]'s words. */
        private fun startsWithWords(long: String, short: String): Boolean {
            if (short.isEmpty() || long.length <= short.length) return false
            return long.startsWith("$short ")
        }
    }
}
