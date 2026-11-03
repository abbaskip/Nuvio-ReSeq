package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.tvdb.TvdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

internal data class VodTitleIdentity(val tmdbId: String?, val titles: Set<String>)

/** Only aliases attached to an identified record are used; never a fuzzy title search. */
@Singleton
class VodTitleAliases @Inject constructor(
    @Named("discoveryTmdb") private val api: TmdbApi,
    private val credentials: TmdbCredentialStore,
    private val profiles: ProfileManager,
    private val tmdb: TmdbService,
    private val tvdb: TvdbClient
) {
    private val cache = com.nuvio.tv.livetv.data.boundedCache<String, VodTitleIdentity>(256)

    internal suspend fun resolve(kind: VodKind, tmdbId: String?, imdbId: String?, tvdbSeriesId: Long?): VodTitleIdentity = withContext(Dispatchers.IO) {
        val profile = profiles.activeProfileId.value
        val access = credentials.access(profile)
        val key = access.apiKey.ifBlank { tmdb.apiKey() }
        val cacheKey = "$profile:${access.revision}:$kind:$tmdbId:$imdbId:$tvdbSeriesId"
        synchronized(cache) { cache[cacheKey] }?.let { return@withContext it }
        var resolvedId = tmdbId
        val titles = linkedSetOf<String>()
        var failed = false
        suspend fun optional(block: suspend () -> Unit) {
            try { if (withTimeoutOrNull(5_000) { block(); true } != true) failed = true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        if (key.isNotBlank()) {
            if (resolvedId == null && imdbId != null) optional {
                val response = api.findByExternalId(imdbId, key)
                check(response.isSuccessful)
                resolvedId = (if (kind == VodKind.SERIES) response.body()?.tvResults else response.body()?.movieResults)
                    ?.singleOrNull()?.id?.toString()
            }
            resolvedId?.toIntOrNull()?.let { id ->
                optional {
                    val response = if (kind == VodKind.SERIES) api.getTvDetails(id, key) else api.getMovieDetails(id, key)
                    check(response.isSuccessful)
                    response.body()?.let { d -> titles += listOfNotNull(d.name, d.title, d.originalName, d.originalTitle) }
                }
                optional {
                    val response = if (kind == VodKind.SERIES) api.getTvAlternativeTitles(id, key) else api.getMovieAlternativeTitles(id, key)
                    check(response.isSuccessful)
                    val body = response.body()
                    titles += (body?.tvTitles.orEmpty() + body?.movieTitles.orEmpty()).mapNotNull { it.title }
                }
            }
        }
        if (kind == VodKind.SERIES && tvdbSeriesId != null) optional { titles += tvdb.seriesTitles(profile, tvdbSeriesId) }
        val result = VodTitleIdentity(resolvedId, titles.map(String::trim).filter(String::isNotBlank).toSet())
        if (profiles.activeProfileId.value != profile || credentials.revision(profile) != access.revision)
            return@withContext VodTitleIdentity(tmdbId, emptySet())
        if (!failed) synchronized(cache) { cache[cacheKey] = result }
        result
    }
}
