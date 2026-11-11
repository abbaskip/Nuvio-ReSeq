package com.nuvio.tv.core.usenet

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.util.zip.GZIPInputStream

internal object NzbDownload {
    private val cache = NzbMemoryCache()
    // Redirects are processed here to keep addon credentials on their original origin.
    private val client by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build() }

    fun fetch(url: String, headers: Map<String, String>, preparation: UsenetPreparation, trace: UsenetStartupTrace? = null): NzbDocument {
        preparation.own(Closeable {}) // A cache hit must still respect cancelled preparation ownership.
        cache.get(url, headers)?.let { trace?.add("nzb_cache_hits"); return it }
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
                    val wire = bounded(body.byteStream())
                    val decoded = if (wire.size >= 2 && wire[0] == 0x1f.toByte() && wire[1] == 0x8b.toByte())
                        GZIPInputStream(wire.inputStream()).use(::bounded) else wire
                    return NzbManifest.parse(decoded).also {
                        trace?.add("nzb_bytes", decoded.size.toLong())
                        cache.put(url, headers, it, decoded.size)
                    }
                }
            }
        }
        throw UsenetFailure("Too many NZB redirects.")
    }

    private fun bounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk, 0, minOf(chunk.size, NzbManifest.MAX_BYTES + 1 - output.size()))
            if (count < 0) return output.toByteArray()
            output.write(chunk, 0, count)
            ensureUsenet(output.size() <= NzbManifest.MAX_BYTES, "NZB exceeds the size limit.")
        }
    }
}
