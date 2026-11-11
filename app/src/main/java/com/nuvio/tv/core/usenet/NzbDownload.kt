package com.nuvio.tv.core.usenet

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

internal object NzbDownload {
    private val cache = NzbMemoryCache()
    fun onMemoryPressure() = cache.clear()
    // Redirects are processed here to keep addon credentials on their original origin.
    private val client by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build() }

    fun fetch(url: String, headers: Map<String, String>, preparation: UsenetPreparation, trace: UsenetStartupTrace? = null): NzbDocument {
        preparation.own(Closeable {}) // A cache hit must still respect cancelled preparation ownership.
        cache.get(url, headers)?.let { trace?.add("nzb_cache_hits"); return it }
        val generation = cache.generation()
        val limits = NzbLimits.forProfile(UsenetMemoryProfile.current)
        var request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) ->
                if (name.lowercase() !in setOf("host", "content-length", "connection", "accept-encoding")) header(name, value)
            }
        }.build()
        val origin = request.url
        repeat(11) { redirects ->
            val call = client.newCall(request)
            preparation.own(Closeable { call.cancel() })
            call.execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    ensureUsenet(redirects < 10, "Too many NZB redirects.")
                    val destination = response.header("Location")?.let { response.request.url.resolve(it) }
                        ?: throw UsenetFailure("Invalid NZB redirect.")
                    ensureUsenet(destination.scheme in setOf("http", "https") &&
                        destination.username.isEmpty() && destination.password.isEmpty() &&
                        (!request.url.isHttps || destination.isHttps), "Unsafe NZB redirect.")
                    val sameOrigin = destination.scheme == origin.scheme && destination.host == origin.host && destination.port == origin.port
                    request = if (sameOrigin) request.newBuilder().url(destination).build()
                        else Request.Builder().url(destination).build()
                } else {
                    ensureUsenet(response.isSuccessful, "Could not download the NZB.")
                    val body = response.body ?: throw UsenetFailure("Empty NZB response.")
                    val wire = PushbackInputStream(body.byteStream(), 2)
                    val first = wire.read()
                    val second = wire.read()
                    if (second >= 0) wire.unread(second)
                    if (first >= 0) wire.unread(first)
                    val decoded = if (first == 0x1f && second == 0x8b) GZIPInputStream(wire) else wire
                    return decoded.use { NzbManifest.parse(it, limits) }.also {
                        trace?.add("nzb_bytes", it.decodedBytes)
                        trace?.add("nzb_retained_bytes", it.retainedBytes)
                        cache.put(url, headers, it, generation)
                    }
                }
            }
        }
        throw UsenetFailure("Too many NZB redirects.")
    }

}
