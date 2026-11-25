package com.nuvio.tv.livetv.thumbs

import android.media.MediaDataSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private val crossOriginHeaders = setOf("host", "connection", "accept", "accept-encoding", "range", "user-agent")

internal val thumbnailHttp = OkHttpClient.Builder()
    .callTimeout(8, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .addNetworkInterceptor { chain ->
        val request = chain.request()
        val origin = request.tag(HttpUrl::class.java)
        val target = request.url
        val sameOrigin = origin?.host == target.host && origin.port == target.port && origin.scheme == target.scheme
        val safe = if (sameOrigin) request else request.newBuilder().apply {
            request.headers.names().filter { it.lowercase() !in crossOriginHeaders }.forEach(::removeHeader)
        }.build()
        chain.proceed(safe)
    }.build()

/** Native file readers can forward custom headers on redirects. Use origin-scoped HTTP byte
 * ranges for these links. Retain one small block for the retriever's nearby reads. */
internal class SeekThumbHttpFile(url: String, private val headers: Map<String, String>) : MediaDataSource() {
    private val origin = url.toHttpUrl()
    private var block = ByteArray(0)
    private var start = 0L
    private var length = -1L

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0 || length in 0..position) return -1
        if (position < start || position - start >= block.size) fetch(position)
        val index = (position - start).toInt()
        val count = minOf(size, block.size - index)
        if (count <= 0) return -1
        block.copyInto(buffer, offset, index, index + count)
        return count
    }

    @Synchronized
    override fun getSize(): Long {
        if (length < 0) fetch(0)
        return length
    }

    private fun fetch(position: Long) {
        val last = minOf(position, Long.MAX_VALUE - BLOCK_SIZE) + BLOCK_SIZE - 1
        val request = Request.Builder().url(origin).tag(HttpUrl::class.java, origin).apply {
            headers.forEach { (name, value) -> header(name, value) }
            header("Range", "bytes=$position-$last")
            header("Accept-Encoding", "identity")
        }.build()
        thumbnailHttp.newCall(request).execute().use { response ->
            val range = response.header("Content-Range")?.let(contentRange::matchEntire)
            val first = range?.groupValues?.get(1)?.toLongOrNull()
            val end = range?.groupValues?.get(2)?.toLongOrNull()
            val total = range?.groupValues?.get(3)?.toLongOrNull()
            if (response.code != 206 || first != position || end == null || end !in position..last || total == null || total <= end)
                throw IOException("Thumbnail source does not support bounded range reads")
            block = response.body.source().readByteArray(end - position + 1)
            start = position
            length = total
        }
    }

    @Synchronized
    override fun close() { block = ByteArray(0) }

    companion object {
        private const val BLOCK_SIZE = 256L * 1024
        private val contentRange = Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        fun needsScopedHeaders(headers: Map<String, String>) = headers.keys.any { !it.equals("User-Agent", true) && !it.equals("Accept", true) } ||
            headers.values.any { '\r' in it || '\n' in it }
    }
}
