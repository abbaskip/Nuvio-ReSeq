package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.EpisodeCoordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TvdbException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NO_KEY, AUTHENTICATION, RATE_LIMIT, UNAVAILABLE, NETWORK, INVALID_RESPONSE, CREDENTIALS_CHANGED }
}

data class TvdbOrder(val type: String, val name: String)
data class TvdbEpisode(
    val id: Long, val coordinates: EpisodeCoordinates, val title: String,
    val overview: String?, val image: String?, val aired: String?, val runtime: Int?
)

@Singleton
class TvdbClient @Inject constructor(
    private val credentials: TvdbCredentialStore,
    @Named("tvdb") private val http: OkHttpClient
) {
    private class Token(val revision: Long, val value: String, val expiresAt: Long)
    private val mutex = Mutex()
    private val tokens = mutableMapOf<Int, Token>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Login only: never persist or display the bearer token. */
    suspend fun testConnection(profileId: Int) = mutex.withLock {
        withContext(Dispatchers.IO) {
            tokens.remove(profileId)
            token(profileId)
            Unit
        }
    }

    suspend fun orders(profileId: Int, seriesId: Long): List<TvdbOrder> {
        require(seriesId > 0)
        val series = get(profileId, "series/$seriesId/extended").objectData()
        return series["seasons"].array().mapNotNull { value ->
            val type = value.jsonObject["type"] as? JsonObject ?: return@mapNotNull null
            val id = type["type"].string() ?: return@mapNotNull null
            TvdbOrder(id, type["name"].string() ?: id)
        }.distinctBy { it.type }
    }

    suspend fun seriesByRemoteId(profileId: Int, remoteId: String): Long? {
        require(remoteId.matches(Regex("[A-Za-z0-9:_-]+")))
        return get(profileId, "search/remoteid/$remoteId")["data"].array()
            .mapNotNull { (it.jsonObject["series"] as? JsonObject)?.get("id").long() }
            .distinct().singleOrNull()
    }

    /** All pages must succeed. Never return a truncated order after a page error. */
    suspend fun episodes(profileId: Int, seriesId: Long, order: String, language: String = "eng"): List<TvdbEpisode> {
        require(seriesId > 0 && order.matches(Regex("[a-zA-Z0-9_-]+")) && language.matches(Regex("[a-z]{3}")))
        val revision = credentials.revision(profileId)
        val result = mutableListOf<TvdbEpisode>()
        val seenIds = mutableSetOf<Long>()
        var page = 0
        do {
            val response = get(profileId, "series/$seriesId/episodes/$order/$language?page=$page")
            val rows = response.objectData()["episodes"] as? JsonArray
                ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
            val pageEpisodes = rows.map { parseTvdbEpisode(it.jsonObject) }
            if (pageEpisodes.isNotEmpty() && pageEpisodes.all { it.id in seenIds }) {
                throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
            }
            pageEpisodes.forEach { seenIds += it.id }
            result += pageEpisodes
            val next = (response["links"] as? JsonObject)?.get("next").string()
            if (next == null) break
            if (rows.isEmpty() || ++page > 1000) throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
        } while (true)
        if (result.groupBy { it.id }.any { it.value.distinct().size != 1 }) {
            throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
        }
        checkRevision(profileId, revision)
        val unique = result.distinctBy { it.id }
        if (unique.groupBy { it.coordinates }.any { it.value.size > 1 }) throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
        return unique
    }

    suspend fun episodesFromSeasons(profileId: Int, seriesId: Long, order: String): List<TvdbEpisode> {
        require(seriesId > 0 && order.matches(Regex("[a-zA-Z0-9_-]+")))
        val series = get(profileId, "series/$seriesId/extended").objectData()
        val result = mutableListOf<TvdbEpisode>()
        for (season in series["seasons"].array().map { it.jsonObject }) {
            val type = season["type"] as? JsonObject ?: continue
            if (type["type"].string() != order) continue
            val id = season["id"].long()?.takeIf { it > 0 } ?: continue
            val number = season["number"].long() ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
            val details = get(profileId, "seasons/$id/extended").objectData()
            for (episode in details["episodes"].array()) {
                result += parseTvdbEpisode(JsonObject(episode.jsonObject + ("seasonNumber" to JsonPrimitive(number))))
            }
        }
        if (result.groupBy { it.id }.any { it.value.size > 1 } || result.groupBy { it.coordinates }.any { it.value.size > 1 }) {
            throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
        }
        return result
    }

    private suspend fun get(profileId: Int, path: String): JsonObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                val token = token(profileId)
                val request = Request.Builder().url("$BASE/$path")
                    .header("Authorization", "Bearer ${token.value}").build()
                try {
                    val (code, body) = executeRequest(request)
                    coroutineContext.ensureActive()
                    checkRevision(profileId, token.revision)
                    if (code == 401 && attempt == 0) {
                        tokens.remove(profileId)
                    } else {
                        checkStatus(code)
                        return@withContext decode(body)
                    }
                } catch (e: TvdbException) { throw e }
                catch (_: IOException) { throw TvdbException(TvdbException.Reason.NETWORK) }
            }
            throw TvdbException(TvdbException.Reason.AUTHENTICATION)
        }
    }

    private suspend fun token(profileId: Int): Token {
        val revision = credentials.revision(profileId)
        tokens[profileId]?.takeIf { it.revision == revision && it.expiresAt > System.currentTimeMillis() }?.let { return it }
        val key = credentials.read(profileId) ?: throw TvdbException(TvdbException.Reason.NO_KEY)
        // PIN deliberately omitted: API-key-only authentication is supported.
        val body = buildJsonObject { put("apikey", key) }.toString().toRequestBody("application/json".toMediaType())
        try {
            val (code, responseBody) = executeRequest(Request.Builder().url("$BASE/login").post(body).build())
            checkRevision(profileId, revision)
            checkStatus(code)
            val value = decode(responseBody).objectData()["token"].string()
                ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
            return Token(revision, value, System.currentTimeMillis() + 28L * 24 * 60 * 60 * 1000)
                .also { tokens[profileId] = it }
        } catch (e: TvdbException) { throw e }
        catch (_: IOException) { throw TvdbException(TvdbException.Reason.NETWORK) }
    }

    /** Cancellation must stop the socket too, so a timed-out show releases the
     * client lock instead of blocking every other TVDB request until HTTP timeout. */
    private suspend fun executeRequest(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.resumeWithException(TvdbException(TvdbException.Reason.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val body = it.body?.string() ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
                        it.code to body
                    }
                } catch (error: Exception) {
                    val reason = if (error is IOException) TvdbException.Reason.NETWORK else TvdbException.Reason.INVALID_RESPONSE
                    continuation.resumeWithException(error as? TvdbException ?: TvdbException(reason))
                    return
                }
                continuation.resume(result)
            }
        })
    }

    private fun checkRevision(profileId: Int, revision: Long) {
        if (credentials.revision(profileId) != revision) throw TvdbException(TvdbException.Reason.CREDENTIALS_CHANGED)
    }
    private fun decode(body: String): JsonObject = try { json.parseToJsonElement(body).jsonObject }
        catch (_: Exception) { throw TvdbException(TvdbException.Reason.INVALID_RESPONSE) }

    private fun checkStatus(code: Int) {
        if (code in 200..299) return
        throw TvdbException(when (code) {
            401, 403 -> TvdbException.Reason.AUTHENTICATION
            404 -> TvdbException.Reason.UNAVAILABLE
            429 -> TvdbException.Reason.RATE_LIMIT
            else -> TvdbException.Reason.NETWORK
        })
    }
    private companion object { const val BASE = "https://api4.thetvdb.com/v4" }
}

internal fun parseTvdbEpisode(row: JsonObject): TvdbEpisode {
    val id = row["id"].long()?.takeIf { it > 0 }
        ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
    val season = row["seasonNumber"].long()?.toInt()?.takeIf { it >= 0 }
        ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
    val number = row["number"].long()?.toInt()?.takeIf { it > 0 }
        ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
    return TvdbEpisode(id, EpisodeCoordinates(season, number), row["name"].string().orEmpty(),
        row["overview"].string(), row["image"].string()?.let { image ->
            when {
                image.startsWith("https://") || image.startsWith("http://") -> image
                image.startsWith("//") -> "https:$image"
                else -> "https://artworks.thetvdb.com/${image.trimStart('/')}"
            }
        }, row["aired"].string(), row["runtime"].long()?.toInt())
}
private fun JsonObject.objectData(): JsonObject = this["data"] as? JsonObject
    ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()
