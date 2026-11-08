package com.nuvio.tv.core.usenet

import fi.iki.elonen.NanoHTTPD
import java.io.Closeable
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class UsenetHttpSession(val file: UsenetFile, private val articles: Closeable) :
    NanoHTTPD("127.0.0.1", 0), Closeable {
    private val path = "/usenet/" + UUID.randomUUID() + "/video." + file.name.substringAfterLast('.').lowercase()
    val url get() = "http://127.0.0.1:$listeningPort$path"
    @Volatile private var lastRead = System.nanoTime()
    fun isIdle() = System.nanoTime() - lastRead > TimeUnit.HOURS.toNanos(2)

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != path) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        if (session.method != Method.GET && session.method != Method.HEAD)
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
        lastRead = System.nanoTime()
        val range = session.headers["range"]
        val bounds = if (range == null) 0L until file.size else parseRange(range, file.size)
        if (bounds == null) return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply {
            addHeader("Content-Range", "bytes */" + file.size)
        }
        // Verify the start of the range before committing a successful HTTP response. Retain the
        // bytes so the player gets them without a second NNTP read. HEAD stays metadata-only.
        val prefix = if (session.method == Method.GET) ByteArray(minOf(8192L, bounds.last - bounds.first + 1).toInt())
            else byteArrayOf()
        val available = try {
            if (prefix.isEmpty()) 0 else file.read(bounds.first, prefix, 0, prefix.size).also {
                ensureUsenet(it > 0, "Usenet video ended early.")
            }
        } catch (error: java.io.IOException) {
            val status = if (error is MissingUsenetArticle) Response.Status.GONE else Response.Status.SERVICE_UNAVAILABLE
            return newFixedLengthResponse(status, MIME_PLAINTEXT,
                if (error is UsenetFailure) error.message.orEmpty() else "Could not read the Usenet video.").apply {
                addHeader("Cache-Control", "no-store")
            }
        }
        val stream = object : InputStream() {
            var position = bounds.first
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 255
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position > bounds.last || session.method == Method.HEAD) return -1
                lastRead = System.nanoTime()
                val prefixOffset = position - bounds.first
                val count = if (prefixOffset < available) {
                    val n = minOf(length, available - prefixOffset.toInt())
                    prefix.copyInto(buffer, offset, prefixOffset.toInt(), prefixOffset.toInt() + n)
                    n
                } else file.read(position, buffer, offset, minOf(length.toLong(), bounds.last - position + 1).toInt())
                ensureUsenet(count > 0, "Usenet video ended early.")
                position += count
                return count
            }
        }
        val status = if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        val mime = when (file.name.substringAfterLast('.').lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "m2ts", "ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }
        return newFixedLengthResponse(status, mime, stream, bounds.last - bounds.first + 1).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Cache-Control", "no-store")
            if (range != null) addHeader("Content-Range", "bytes " + bounds.first + "-" + bounds.last + "/" + file.size)
        }
    }
    override fun close() {
        articles.close()
        stop()
    }

    companion object {
        private val sessionUrl = Regex("""http://127\.0\.0\.1:\d+/usenet/[\da-f-]{36}/video\.[\da-z]+""")
        fun isSessionUrl(url: String) = url.startsWith("http://127.0.0.1:") && sessionUrl.matches(url)
        private val rangePattern = Regex("""bytes=(\d*)-(\d*)""", RegexOption.IGNORE_CASE)
        internal fun parseRange(value: String, size: Long): LongRange? {
            val match = rangePattern.matchEntire(value.trim()) ?: return null
            val (from, to) = match.destructured
            if (size <= 0L) return null
            if (from.isEmpty()) {
                val suffix = to.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return (size - suffix).coerceAtLeast(0)..(size - 1)
            }
            val start = from.toLongOrNull() ?: return null
            val end = if (to.isEmpty()) size - 1 else (to.toLongOrNull() ?: return null).coerceAtMost(size - 1)
            return if (start <= end) start..end else null
        }
    }
}
