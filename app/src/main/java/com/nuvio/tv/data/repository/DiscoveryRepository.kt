package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.data.remote.api.TmdbLanguage
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveryPage(val items: List<MetaPreview>, val hasMore: Boolean, val totalResults: Int)
enum class DiscoveryFailure { NETWORK, RATE_LIMIT, CONFIGURATION }
class DiscoveryException(val failure: DiscoveryFailure) : Exception(failure.name)

@Singleton
class DiscoveryRepository internal constructor(
    private val api: TmdbApi,
    private val apiKey: String,
    private val clock: () -> Long = System::nanoTime
) {
    @Inject constructor(api: TmdbApi) : this(api, BuildConfig.TMDB_API_KEY)
    private data class Key(val parameters: Map<String, String>, val type: String, val language: String, val page: Int)
    private data class CachedPage(val time: Long, val page: DiscoveryPage)
    private val mutex = Mutex()
    private val pages = LinkedHashMap<Key, CachedPage>(16, 0.75f, true)
    private val genres = mutableMapOf<Pair<Boolean, String>, List<TmdbGenre>>()
    private var languages: List<TmdbLanguage>? = null

    suspend fun genres(television: Boolean, language: String): List<TmdbGenre> = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkConfiguration()
            val key = television to language
            genres[key] ?: (if (television) api.getTvGenres(apiKey, language)
                else api.getMovieGenres(apiKey, language)).checked().genres.orEmpty()
                .also { genres[key] = it }
        }
    }

    suspend fun languages(): List<TmdbLanguage> = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkConfiguration()
            languages ?: api.getLanguages(apiKey).checked()
                .filter { it.code.matches(Regex("[a-z]{2}")) && it.englishName.isNotBlank() }
                .sortedBy { it.englishName }.also { languages = it }
        }
    }

    suspend fun discover(query: DiscoveryQuery, language: String, page: Int): DiscoveryPage = withContext(Dispatchers.IO) {
        require(page in 1..500)
        val key = Key(query.tmdbParameters(), query.mediaType, language, page)
        mutex.withLock {
            checkConfiguration()
            val now = clock()
            pages[key]?.takeIf { now - it.time < CACHE_TTL_NANOS }?.let { return@withLock it.page }
            val body = api.advancedDiscover(query.mediaType, apiKey, language, page, key.parameters).checked()
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
            }
        }
    }

    private fun checkConfiguration() {
        if (apiKey.isBlank()) throw DiscoveryException(DiscoveryFailure.CONFIGURATION)
    }

    private fun <T> Response<T>.checked(): T {
        if (code() == 429) throw DiscoveryException(DiscoveryFailure.RATE_LIMIT)
        if (code() == 401) throw DiscoveryException(DiscoveryFailure.CONFIGURATION)
        if (!isSuccessful) throw DiscoveryException(DiscoveryFailure.NETWORK)
        return body() ?: throw DiscoveryException(DiscoveryFailure.NETWORK)
    }

    private companion object { const val CACHE_TTL_NANOS = 10L * 60 * 1_000_000_000 }
}
