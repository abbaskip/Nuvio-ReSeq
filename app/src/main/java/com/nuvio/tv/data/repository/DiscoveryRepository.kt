package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialAccess
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.data.remote.api.TmdbLanguage
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Named

data class DiscoveryPage(val items: List<MetaPreview>, val hasMore: Boolean, val totalResults: Int)
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
    private data class Key(val parameters: Map<String, String>, val type: String, val language: String, val page: Int)
    private data class CachedPage(val time: Long, val page: DiscoveryPage)
    private val mutex = Mutex()
    private val pages = LinkedHashMap<Key, CachedPage>(16, 0.75f, true)
    private val genres = mutableMapOf<Pair<Boolean, String>, List<TmdbGenre>>()
    private var languages: List<TmdbLanguage>? = null
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

    suspend fun discover(query: DiscoveryQuery, language: String, page: Int): DiscoveryPage = withContext(Dispatchers.IO) {
        require(page in 1..500)
        val auth = credentials()
        val key = Key(query.tmdbParameters(), query.mediaType, language, page)
        mutex.withLock {
            activate(auth)
            val now = clock()
            pages[key]?.takeIf { now - it.time < CACHE_TTL_NANOS }?.let { return@withLock it.page.withTitleFilter(query) }
            val body = api.advancedDiscover(query.mediaType, auth.apiKey, language, page, key.parameters).checked(auth)
            val items = body.results.orEmpty().mapNotNull { result ->
                val name = (if (query.television) result.name else result.title)?.takeIf(String::isNotBlank)
                    ?: result.originalTitle?.takeIf(String::isNotBlank) ?: result.originalName?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val date = if (query.television) result.firstAirDate else result.releaseDate
                MetaPreview(
                    id = "tmdb:${result.id}", type = if (query.television) ContentType.SERIES else ContentType.MOVIE,
                    name = name, poster = result.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    posterShape = PosterShape.POSTER,
                    background = result.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    logo = null, description = result.overview, releaseInfo = date?.take(4), released = date,
                    // Grid cards use a legacy field name for their numeric rating. This screen labels the source TMDB.
                    imdbRating = result.voteAverage?.toFloat(), voteCount = result.voteCount, genres = emptyList()
                )
            }.distinctBy { it.id }
            DiscoveryPage(items, page < minOf(body.totalPages ?: 1, 500), body.totalResults ?: items.size).also {
                pages[key] = CachedPage(now, it)
                while (pages.size > 40) pages.remove(pages.keys.first())
            }.withTitleFilter(query)
        }
    }

    // Cache the complete provider page, so editing/clearing a local title filter
    // cannot hide results from later queries that share the same TMDB parameters.
    private fun DiscoveryPage.withTitleFilter(query: DiscoveryQuery): DiscoveryPage =
        if (query.titleContains.isBlank()) this else copy(items = items.filter { query.matchesTitle(it.name) })

    suspend fun imdbId(tmdbId: Int, television: Boolean): String? = try {
        withContext(Dispatchers.IO) {
            val auth = credentials()
            mutex.withLock {
                activate(auth)
                val key = tmdbId to television
                if (externalIds.containsKey(key)) return@withLock externalIds[key]
                val response = if (television) api.getTvExternalIds(tmdbId, auth.apiKey) else api.getMovieExternalIds(tmdbId, auth.apiKey)
                verifyCurrent(auth)
                // Optional watched matching is best effort; failed lookups remain retryable.
                if (!response.isSuccessful) return@withLock null
                response.body()?.imdbId.also {
                    externalIds[key] = it
                    while (externalIds.size > 500) externalIds.remove(externalIds.keys.first())
                }
            }
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    private fun activate(auth: TmdbCredentialAccess) {
        if (auth.apiKey.isBlank()) throw DiscoveryException(DiscoveryFailure.CONFIGURATION)
        verifyCurrent(auth)
        val identity = auth.profileId to auth.revision
        if (identity != activeCredential) {
            pages.clear()
            genres.clear()
            languages = null
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
