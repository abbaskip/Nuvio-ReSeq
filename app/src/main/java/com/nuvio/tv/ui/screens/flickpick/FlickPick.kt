package com.nuvio.tv.ui.screens.flickpick

import com.nuvio.tv.data.repository.DiscoveryPage
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import java.time.LocalDate
import kotlin.random.Random

internal enum class FlickPickGenre { ANY, MUST, EXCLUDE }

internal enum class FlickPickEra(val firstYear: Int? = null, val lastYear: Int? = null, val yearsBack: Long? = null) {
    ALL, FIFTIES_SIXTIES(1950, 1969), SEVENTIES(1970, 1979), EIGHTIES_NINETIES(1980, 1999),
    NOUGHTIES_TENS(2000, 2019), TWENTIES(2020, 2029),
    LAST_5(yearsBack = 5), LAST_10(yearsBack = 10), LAST_20(yearsBack = 20), LAST_30(yearsBack = 30);

    fun dates(today: LocalDate): Pair<String, String> = when {
        yearsBack != null -> today.minusYears(yearsBack).toString() to today.toString()
        firstYear != null && lastYear != null -> LocalDate.of(firstYear, 1, 1).toString() to
            minOf(today, LocalDate.of(lastYear, 12, 31)).toString()
        else -> "" to ""
    }
}

internal data class FlickPickFilters(
    val genres: Map<Int, FlickPickGenre> = emptyMap(),
    val keywords: String = "",
    val era: FlickPickEra? = FlickPickEra.ALL,
    val from: String = "",
    val to: String = ""
) {
    fun cycle(id: Int): FlickPickFilters {
        val next = when (genres[id]) {
            null -> FlickPickGenre.ANY
            FlickPickGenre.ANY -> FlickPickGenre.MUST
            FlickPickGenre.MUST -> FlickPickGenre.EXCLUDE
            FlickPickGenre.EXCLUDE -> null
        }
        return copy(genres = if (next == null) genres - id else genres + (id to next))
    }

    fun withEra(value: FlickPickEra, today: LocalDate): FlickPickFilters {
        val (from, to) = value.dates(today)
        return copy(era = value, from = from, to = to)
    }

    private fun ids(rule: FlickPickGenre) = genres.filterValues { it == rule }.keys
    val anyOfGenres: Set<Int> get() = if (FlickPickGenre.MUST in genres.values && ids(FlickPickGenre.ANY).size > 1)
        ids(FlickPickGenre.ANY) else emptySet()

    fun query(): DiscoveryQuery {
        val required = ids(FlickPickGenre.MUST)
        val any = ids(FlickPickGenre.ANY)
        // TMDB accepts a single conjunction or disjunction. One alternative can join the
        // required genres directly. Multiple alternatives are checked against each result's IDs.
        val serverGenres = when {
            required.isEmpty() -> any
            any.size == 1 -> required + any
            else -> required
        }
        return DiscoveryQuery(genres = serverGenres, matchAllGenres = required.isNotEmpty(),
            excludedGenres = ids(FlickPickGenre.EXCLUDE), dateFrom = from, dateTo = to,
            keywords = keywords, matchAllKeywords = true, sort = "release_date.asc")
    }
}

private data class FlickPickQuality(val rating: String, val votes: String, val strictlyAboveRating: Boolean = false)

private val qualitySteps = listOf(
    FlickPickQuality("6", "501", strictlyAboveRating = true),
    FlickPickQuality("5", "250"),
    FlickPickQuality("4", "100"),
    FlickPickQuality("", "")
)

internal suspend fun pickDiscoveredMovie(
    filters: FlickPickFilters,
    today: LocalDate,
    load: suspend (DiscoveryQuery, Int, Set<Int>) -> DiscoveryPage,
    unwatched: suspend (MetaPreview) -> MetaPreview?,
    random: Random = Random.Default,
    recent: List<String> = emptyList()
): MetaPreview? {
    val base = filters.query()
    require(base.validationError() == null)
    val cutoff = today.toString()
    if (base.dateFrom > cutoff) return null
    val releasedBy = minOf(base.dateTo.ifBlank { cutoff }, cutoff)
    var fallback: MetaPreview? = null
    val checked = hashSetOf<String>()
    for ((rating, votes, strictlyAboveRating) in qualitySteps) {
        val query = base.copy(dateTo = releasedBy, minimumRating = rating, minimumVotes = votes)
        val pick = randomDiscoveryMatch({ page -> load(query, page, filters.anyOfGenres) }, random) { movie ->
            if (strictlyAboveRating && (movie.imdbRating ?: 0f) <= rating.toFloat()) return@randomDiscoveryMatch null
            if (!checked.add(movie.id)) return@randomDiscoveryMatch null
            val eligible = unwatched(movie) ?: return@randomDiscoveryMatch null
            val index = recent.indexOf(movie.id)
            if (index < 0) return@randomDiscoveryMatch eligible
            if (index > (fallback?.let { recent.indexOf(it.id) } ?: -1)) fallback = eligible
            null
        }
        if (pick != null) return pick
    }
    return fallback
}

internal suspend fun randomDiscoveryMatch(
    load: suspend (Int) -> DiscoveryPage,
    random: Random = Random.Default,
    eligible: suspend (MetaPreview) -> MetaPreview?
): MetaPreview? {
    val first = load(1)
    val pages = mutableMapOf(1 to first.items)
    // A title search can merge multiple provider pages. Sample positions so compacted
    // and partial pages give every remaining title the same chance.
    val capacity = maxOf(first.pageCapacity, first.items.size)
    val count = if (first.totalPages == 1) first.items.size else first.totalPages * capacity
    val positions = IntArray(count) { it }.apply { shuffle(random) }
    val seen = hashSetOf<String>()
    for (position in positions) {
        val number = position / capacity + 1
        val items = pages.getOrPut(number) { load(number).items }
        val item = items.getOrNull(position % capacity) ?: continue
        if (seen.add(item.id)) eligible(item)?.let { return it }
    }
    return null
}

internal fun LibraryEntry.hasWatchedId(watched: Set<String>): Boolean =
    id.removePrefix("imdb:") in watched || imdbId?.removePrefix("imdb:") in watched ||
        tmdbId?.let { "tmdb:$it" in watched } == true || traktId?.let { "trakt:$it" in watched } == true ||
        simklId?.let { "simkl:$it" in watched } == true
