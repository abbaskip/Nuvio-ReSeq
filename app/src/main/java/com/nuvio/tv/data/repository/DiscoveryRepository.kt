package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialAccess
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.data.remote.api.TmdbLanguage
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.domain.model.DiscoveryTerm
import com.nuvio.tv.domain.model.MovieAgeLimit
import com.nuvio.tv.domain.model.MovieRatingSystem
import com.nuvio.tv.data.remote.api.TmdbCertification
import com.nuvio.tv.domain.model.matchTerms
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Named

data class DiscoveryPage(val items: List<MetaPreview>, val hasMore: Boolean, val totalResults: Int,
    val totalPages: Int = 1, val pageCapacity: Int = 20)
enum class DiscoveryFailure { NETWORK, RATE_LIMIT, CONFIGURATION }
class DiscoveryException(val failure: DiscoveryFailure) : Exception(failure.name)

@Singleton
class DiscoveryRepository internal constructor(
    private val api: TmdbApi,
    private val credentials: () -> TmdbCredentialAccess,
    private val clock: () -> Long = System::nanoTime
) {
    @Inject constructor(@Named("discoveryTmdb") api: TmdbApi, store: TmdbCredentialStore, profiles: ProfileManager) : this(
        api, { store.access(profiles.activeProfileId.value) }
    )
    internal constructor(api: TmdbApi, apiKey: String, clock: () -> Long = System::nanoTime) : this(
        api, { TmdbCredentialAccess(1, 0, apiKey) }, clock
    )
    private data class KeywordQuery(val terms: List<DiscoveryTerm>, val all: Boolean)
    private data class Key(val parameters: Map<String, String>, val type: String, val language: String,
        val page: Int, val searchTitle: String?, val checkKeywords: KeywordQuery?, val anyOfGenres: Set<Int>)
    private data class CachedPage(val time: Long, val page: DiscoveryPage)
    private val mutex = Mutex()
    private val pages = LinkedHashMap<Key, CachedPage>(16, 0.75f, true)
    private val keywordFilters = LinkedHashMap<KeywordQuery, Pair<Long, DiscoveryKeywordFilter>>(16, 0.75f, true)
    private val genres = mutableMapOf<Pair<Boolean, String>, List<TmdbGenre>>()
    private var languages: List<TmdbLanguage>? = null
    private var certifications: Map<String, List<TmdbCertification>>? = null
    private val releaseRatings = LinkedHashMap<Int, Map<String, List<String>>>(32, 0.75f, true)
    private val movieIds = LinkedHashMap<String, Int?>(32, 0.75f, true)
    private var activeCredential: Pair<Int, Long>? = null
    private val externalIds = LinkedHashMap<Pair<Int, Boolean>, String?>(16, 0.75f, true)

    suspend fun genres(television: Boolean, language: String): List<TmdbGenre> = withContext(Dispatchers.IO) {
        val auth = credentials()
        mutex.withLock {
            activate(auth)
            val key = television to language
            genres[key] ?: (if (television) api.getTvGenres(auth.apiKey, language)
                else api.getMovieGenres(auth.apiKey, language)).checked(auth).genres.orEmpty()
                .also { genres[key] = it }
        }
    }

    suspend fun languages(): List<TmdbLanguage> = withContext(Dispatchers.IO) {
        val auth = credentials()
        mutex.withLock {
            activate(auth)
            languages ?: api.getLanguages(auth.apiKey).checked(auth)
                .filter { it.code.matches(Regex("[a-z]{2}")) && it.englishName.isNotBlank() }
                .sortedBy { it.englishName }.also { languages = it }
        }
    }

    internal suspend fun ratingSystem(region: String): MovieRatingSystem = withContext(Dispatchers.IO) {
        val auth = credentials()
        mutex.withLock {
            activate(auth)
            val systems = certifications ?: api.getMovieCertifications(auth.apiKey).checked(auth).certifications
                .also { certifications = it }
            MovieRatingSystem.forRegion(region, systems)
        }
    }

    internal suspend fun allowsMovie(tmdbId: Int?, imdbId: String?, limit: MovieAgeLimit): Boolean = withContext(Dispatchers.IO) {
        val auth = credentials()
        mutex.withLock {
            activate(auth)
            val id = tmdbId ?: imdbId?.let { imdb ->
                if (movieIds.containsKey(imdb)) movieIds[imdb] else {
                    val response = api.findByExternalId(imdb, auth.apiKey)
                    verifyCurrent(auth)
                    val found = if (response.code() == 404) null else response.checked(auth).movieResults?.firstOrNull()?.id
                    movieIds[imdb] = found
                    if (movieIds.size > 500) movieIds.remove(movieIds.keys.first())
                    found
                }
            } ?: return@withLock false
            val ratings = releaseRatings[id] ?: run {
                val response = api.getMovieReleaseDates(id, auth.apiKey)
                verifyCurrent(auth)
                val countries = if (response.code() == 404) emptyList() else response.checked(auth).results.orEmpty()
                countries.associate { it.iso31661.orEmpty() to it.releaseDates.orEmpty().mapNotNull { date -> date.certification } }
                    .also { releaseRatings[id] = it }
            }
            if (releaseRatings.size > 500) releaseRatings.remove(releaseRatings.keys.first())
            limit.accepts(ratings[limit.system.country].orEmpty())
        }
    }

    suspend fun discover(query: DiscoveryQuery, language: String, page: Int, anyOfGenres: Set<Int> = emptySet(),
        maximumRating: Pair<String, String>? = null): DiscoveryPage = withContext(Dispatchers.IO) {
        require(page in 1..500)
        val auth = credentials()
        val parameters = query.tmdbParameters().let { params ->
            if (maximumRating == null) params else params + mapOf("region" to maximumRating.first,
                "certification_country" to maximumRating.first, "certification.lte" to maximumRating.second)
        }
        mutex.withLock {
            activate(auth)
            val keywords = KeywordQuery(query.keywordTerms, query.matchAllKeywords)
            val filter = keywordFilter(auth, keywords)
            if (filter.empty) return@withLock DiscoveryPage(emptyList(), false, 0)
            val filters = if (filter.parameter == null) parameters else parameters + ("with_keywords" to filter.parameter)
            val titles = query.titleTerms
            val searches = if (query.sort == "popularity.desc" && query.offersTitleRelevance(parameters)) {
                if (query.matchAllTitles) listOf(titles.maxBy { it.text.length }.text)
                else titles.map { it.text }.distinct()
            } else listOf(null)
            val sources = searches.map { term ->
                providerPage(Key(filters, query.mediaType, language, page, term, keywords.takeIf { filter.checkItemTags }, anyOfGenres),
                    auth, skipExhausted = searches.size > 1)
            }
            // Keep each search's relevance order and give each alternative a place on the page.
            val combined = if (sources.size == 1) sources.single() else {
                val items = (0 until sources.maxOf { it.items.size }).flatMap { rank ->
                    sources.mapNotNull { it.items.getOrNull(rank) }
                }.distinctBy { it.id }
                DiscoveryPage(items, sources.any { it.hasMore }, items.size, sources.maxOf { it.totalPages },
                    sources.sumOf { it.pageCapacity })
            }
            if (titles.isEmpty()) combined else combined.copy(items = combined.items.filter { item ->
                titles.matchTerms(query.matchAllTitles) { it.matchesTitle(item.name) }
            })
        }
    }

    private suspend fun keywordFilter(auth: TmdbCredentialAccess, query: KeywordQuery): DiscoveryKeywordFilter {
        if (query.terms.isEmpty()) return DiscoveryKeywordFilter()
        val now = clock()
        keywordFilters[query]?.takeIf { now - it.first < CACHE_TTL_NANOS }?.let { return it.second }
        val filter = resolveDiscoveryKeywords(query.terms, query.all) { term, page ->
            api.searchKeywords(auth.apiKey, term, page).checked(auth)
        }
        keywordFilters[query] = now to filter
        if (keywordFilters.size > 40) keywordFilters.remove(keywordFilters.keys.first())
        return filter
    }

    private suspend fun providerPage(key: Key, auth: TmdbCredentialAccess, skipExhausted: Boolean): DiscoveryPage {
        val now = clock()
        pages[key]?.takeIf { now - it.time < CACHE_TTL_NANOS }?.let { return it.page }
        if (skipExhausted && key.page > 1) {
            val first = pages[key.copy(page = 1)]?.takeIf { now - it.time < CACHE_TTL_NANOS }?.page
            if (first != null && key.page > first.totalPages) return first.copy(items = emptyList(), hasMore = false)
        }
        val previous = pages[key.copy(page = key.page - 1)]?.takeIf { now - it.time < CACHE_TTL_NANOS }
        if (skipExhausted && previous != null && !previous.page.hasMore) {
            return cachePage(key, now, previous.page.copy(items = emptyList()))
        }
        val body = if (key.searchTitle != null) {
            api.searchTitles(key.type, auth.apiKey, key.language, key.page, key.searchTitle)
        } else {
            api.advancedDiscover(key.type, auth.apiKey, key.language, key.page, key.parameters)
        }.checked(auth)
        val television = key.type == "tv"
        val items = body.results.orEmpty().mapNotNull { result ->
            if (key.anyOfGenres.isNotEmpty() && result.genreIds.orEmpty().none { it in key.anyOfGenres }) return@mapNotNull null
            val name = (if (television) result.name else result.title)?.takeIf(String::isNotBlank)
                ?: result.originalTitle?.takeIf(String::isNotBlank) ?: result.originalName?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val date = if (television) result.firstAirDate else result.releaseDate
            MetaPreview(
                id = "tmdb:${result.id}", type = if (television) ContentType.SERIES else ContentType.MOVIE,
                name = name, poster = result.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                posterShape = PosterShape.POSTER,
                background = result.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                logo = null, description = result.overview, releaseInfo = date?.take(4), released = date,
                // Grid cards use a legacy field name for their numeric rating. This screen labels the source TMDB.
                imdbRating = result.voteAverage?.toFloat(), voteCount = result.voteCount, genres = emptyList()
            )
        }.distinctBy { it.id }
        val matches = key.checkKeywords?.let { filter ->
            coroutineScope {
                items.chunked(4).flatMap { batch ->
                    batch.map { item -> async {
                        item.takeIf { matchesKeywords(key.type, item.id.removePrefix("tmdb:").toInt(), filter, auth) }
                    } }.awaitAll().filterNotNull()
                }
            }
        } ?: items
        val totalPages = (body.totalPages ?: 1).coerceIn(1, 500)
        return cachePage(key, now, DiscoveryPage(matches, key.page < totalPages, body.totalResults ?: items.size, totalPages))
    }

    private suspend fun matchesKeywords(type: String, id: Int, filter: KeywordQuery, auth: TmdbCredentialAccess): Boolean {
        val response = api.getTitleKeywords(type, id, auth.apiKey)
        verifyCurrent(auth)
        if (response.code() == 404) return false
        val tags = response.checked(auth).let { it.movieKeywords ?: it.tvKeywords }.orEmpty()
        return filter.terms.matchTerms(filter.all) { term -> tags.any { tag -> tag.name?.let(term::matchesKeyword) == true } }
    }

    private fun cachePage(key: Key, now: Long, page: DiscoveryPage): DiscoveryPage {
        pages[key] = CachedPage(now, page)
        if (pages.size > 40) pages.remove(pages.keys.first())
        return page
    }

    suspend fun imdbId(tmdbId: Int, television: Boolean): String? = try {
        resolveImdbId(tmdbId, television)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    suspend fun resolveImdbId(tmdbId: Int, television: Boolean): String? =
        withContext(Dispatchers.IO) {
            val auth = credentials()
            mutex.withLock {
                activate(auth)
                val key = tmdbId to television
                if (externalIds.containsKey(key)) return@withLock externalIds[key]
                val response = if (television) api.getTvExternalIds(tmdbId, auth.apiKey) else api.getMovieExternalIds(tmdbId, auth.apiKey)
                verifyCurrent(auth)
                val imdbId = if (response.code() == 404) null else response.checked(auth).imdbId
                imdbId.also {
                    externalIds[key] = it
                    while (externalIds.size > 500) externalIds.remove(externalIds.keys.first())
                }
            }
        }

    private fun activate(auth: TmdbCredentialAccess) {
        if (auth.apiKey.isBlank()) throw DiscoveryException(DiscoveryFailure.CONFIGURATION)
        verifyCurrent(auth)
        val identity = auth.profileId to auth.revision
        if (identity != activeCredential) {
            pages.clear()
            keywordFilters.clear()
            genres.clear()
            languages = null
            certifications = null
            releaseRatings.clear()
            movieIds.clear()
            externalIds.clear()
            activeCredential = identity
        }
    }

    private fun verifyCurrent(auth: TmdbCredentialAccess) {
        val current = credentials()
        if (current.profileId != auth.profileId || current.revision != auth.revision || current.apiKey != auth.apiKey) {
            throw CancellationException("TMDB credentials changed")
        }
    }

    private fun <T> Response<T>.checked(auth: TmdbCredentialAccess): T {
        verifyCurrent(auth)
        if (code() == 429) throw DiscoveryException(DiscoveryFailure.RATE_LIMIT)
        if (code() == 401) throw DiscoveryException(DiscoveryFailure.CONFIGURATION)
        if (!isSuccessful) throw DiscoveryException(DiscoveryFailure.NETWORK)
        return body() ?: throw DiscoveryException(DiscoveryFailure.NETWORK)
    }

    private companion object { const val CACHE_TTL_NANOS = 10L * 60 * 1_000_000_000 }
}
