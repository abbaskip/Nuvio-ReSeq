package com.nuvio.tv.core.plugin.cloudstream

import android.util.Log
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.domain.model.ExternalPluginEntry
import com.nuvio.tv.domain.model.ExternalRepoManifest
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

private const val TAG = "ExternalRepoParser"

/**
 * Result of parsing an external repository URL.
 */
data class ExternalRepoParseResult(
    val name: String,
    val description: String?,
    val plugins: List<ExternalPluginEntry>
)

/**
 * Parses external extension repository formats.
 *
 * Supports two formats:
 * 1. Repo manifest with `pluginLists` URLs pointing to separate plugins.json files
 * 2. Direct plugins array (list of [ExternalPluginEntry])
 */
@Singleton
class ExternalRepoParser @Inject constructor(
    private val moshi: Moshi
) {
    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val repoManifestAdapter = moshi.adapter(ExternalRepoManifest::class.java)
    private val entryAdapter = moshi.adapter(ExternalPluginEntry::class.java)

    private data class Document(val url: HttpUrl, val body: String)

    suspend fun tryParse(url: String): ExternalRepoParseResult? = withContext(Dispatchers.IO) {
        val base = cloudStreamManifestUrl(url) ?: return@withContext null
        fetch(base)?.let { parseDocument(it) }?.let { return@withContext it }
        // Keep extensionless endpoints working, then try the standard directory layout.
        if (base.pathSegments.last().contains('.')) return@withContext null
        fetch(base.newBuilder().addPathSegment("repo.json").build())?.let { parseDocument(it) }
    }

    private suspend fun parseDocument(root: Document): ExternalRepoParseResult? {
        return try {
            val manifest = if (root.body.trimStart().startsWith("{")) {
                repoManifestAdapter.fromJson(root.body) ?: return null
            } else null
            val entries = if (manifest == null) {
                parseList(root)
            } else {
                coroutineScope {
                    manifest.pluginLists.mapNotNull { resolve(root.url, it) }.distinct().map { listUrl ->
                        async {
                            val list = fetch(listUrl) ?: return@async emptyList()
                            try {
                                parseList(list)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.w(TAG, "Skipping unreadable plugin list on ${list.url.host}")
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
            }
            if (entries.isEmpty()) return null
            val byName = linkedMapOf<String, ExternalPluginEntry>()
            for (entry in entries) {
                val existing = byName[entry.internalName]
                if (existing == null || entry.version > existing.version) byName[entry.internalName] = entry
            }
            ExternalRepoParseResult(
                name = manifest?.name?.takeIf { it.isNotBlank() }
                    ?: root.url.pathSegments.last().removeSuffix(".json").ifBlank { root.url.host },
                description = manifest?.description,
                plugins = byName.values.toList()
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(TAG, "Unrecognized repository on ${root.url.host}")
            null
        }
    }

    private fun resolve(base: HttpUrl, candidate: String): HttpUrl? =
        candidate.trim().takeIf { it.isNotEmpty() }?.let(base::resolve)

    private fun parseList(document: Document): List<ExternalPluginEntry> {
        val reader = JsonReader.of(Buffer().writeUtf8(document.body))
        return buildList {
            reader.beginArray()
            while (reader.hasNext()) {
                val fields = reader.readJsonValue() as? Map<*, *> ?: continue
                val entry = try {
                    entryAdapter.fromJsonValue(fields.filterValues { it != null })
                } catch (_: JsonDataException) {
                    null
                } ?: continue
                val id = entry.internalName.trim().takeIf { it.isNotEmpty() } ?: continue
                val packageUrl = resolve(document.url, entry.url) ?: continue
                add(entry.copy(
                    name = entry.name.trim().ifEmpty { id },
                    internalName = id,
                    url = packageUrl.toString(),
                    iconUrl = entry.iconUrl?.let { resolve(document.url, it)?.toString() }
                ))
            }
            reader.endArray()
        }
    }

    private fun fetch(url: HttpUrl): Document? = try {
        val request = Request.Builder().url(url).header("User-Agent", "NuvioTV/1.0").build()
        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.let { Document(response.request.url, it.string()) }
            else null
        }
    } catch (e: java.io.IOException) {
        Log.w(TAG, "Could not fetch repository data from ${url.host}")
        null
    }
}

internal fun unwrapCloudStreamUrl(input: String): String {
    var value = input.trim()
    val prefixes = listOf("cloudstreamrepo://", "https://cs.repo/?", "http://cs.repo/?")
    for (prefix in prefixes) {
        if (value.startsWith(prefix, ignoreCase = true)) {
            value = value.substring(prefix.length).trim()
            if (!value.contains("://")) value = "https://$value"
            break
        }
    }
    return value
}

internal fun cloudStreamManifestUrl(input: String): HttpUrl? {
    val url = unwrapCloudStreamUrl(input).toHttpUrlOrNull() ?: return null
    if (url.host != "github.com") return url
    val path = url.pathSegments.filter { it.isNotEmpty() }
    if (path.size < 2) return url
    val raw = HttpUrl.Builder().scheme("https").host("raw.githubusercontent.com")
        .addPathSegment(path[0]).addPathSegment(path[1].removeSuffix(".git"))
    when {
        path.size == 2 -> raw.addPathSegments("HEAD/repo.json")
        path.size >= 5 && path[2] in setOf("blob", "raw") -> path.drop(3).forEach(raw::addPathSegment)
        else -> return url
    }
    return raw.build()
}
