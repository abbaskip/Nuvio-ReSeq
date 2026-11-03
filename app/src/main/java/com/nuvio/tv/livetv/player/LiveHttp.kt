package com.nuvio.tv.livetv.player

import java.net.HttpURLConnection
import java.net.URL
import com.nuvio.tv.core.network.consumeCancellableResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Opens a provider link for recording, following redirects all the way, including between
 * http and https and to other servers (Android's built-in following stops at those, so a
 * provider that sends you on to its stream server answered "HTTP 302" and nothing was recorded).
 */
object LiveHttp {
    private val recordingHttp by lazy {
        OkHttpClient.Builder().dns(com.nuvio.tv.core.network.IPv4FirstDns())
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }

    internal suspend fun <T> consume(url: String, headers: Map<String, String>, block: (Response) -> T): T {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
            if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", com.nuvio.tv.livetv.data.LiveTvRepository.DEFAULT_UA)
        }.build()
        return recordingHttp.newCall(request).consumeCancellableResponse { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            block(response)
        }
    }

    internal suspend fun fetchCancellable(url: String, headers: Map<String, String>): Pair<String, String> =
        consume(url, headers) { response ->
            val body = response.body ?: throw java.io.IOException("The provider returned no response body")
            body.string() to response.request.url.toString()
        }

    /** The connection after the last redirect (its [HttpURLConnection.getURL] is where it ended up). */
    fun open(url: String, headers: Map<String, String>, readTimeoutMs: Int = 20_000): HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = readTimeoutMs
            c.instanceFollowRedirects = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (headers.keys.none { it.equals("User-Agent", true) }) {
                c.setRequestProperty("User-Agent", com.nuvio.tv.livetv.data.LiveTvRepository.DEFAULT_UA)
            }
            val code = c.responseCode
            if (code in setOf(301, 302, 303, 307, 308) && hops < 8) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) return URL(current).openConnection() as HttpURLConnection
                current = runCatching { URL(URL(current), location).toString() }.getOrDefault(location)
                hops++
                continue
            }
            return c
        }
    }

    /** The text at [url], and where it was really served from (for links relative to it). */
    fun fetch(url: String, headers: Map<String, String>): Pair<String, String> {
        val c = open(url, headers)
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } to c.url.toString()
    }
}
