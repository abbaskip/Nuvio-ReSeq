package com.nuvio.tv.core.usenet

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.zip.CRC32
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class Article(val start: Long, val total: Long, val bytes: ByteArray, val filename: String? = null) {
    val end get() = start + bytes.size
}

private const val MAX_PART = 4 * 1024 * 1024
private fun BufferedInputStream.newsLine(): String {
    val line = ByteArrayOutputStream(160)
    while (line.size() < 8192) {
        val c = read()
        if (c < 0) throw UsenetFailure("Usenet connection ended early.")
        if (c == 10) return line.toString("ISO-8859-1").removeSuffix("\r")
        line.write(c)
    }
    throw UsenetFailure("Invalid Usenet article line.")
}

internal fun decodeArticle(input: BufferedInputStream): Article {
    var total = 0L
    var start = 0L
    var end = 0L
    var multipart = false
    var decoded: ByteArray? = null
    var filename: String? = null
    val result = ByteArrayOutputStream()
    var wireBytes = 0
    fun String.field(name: String) = split(' ').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    while (true) {
        val line = input.newsLine()
        wireBytes += line.length + 2
        ensureUsenet(wireBytes <= MAX_PART * 3, "Usenet article exceeds the size limit.")
        if (line == ".") break
        when {
            decoded != null -> continue
            total == 0L -> if (line.startsWith("=ybegin ")) {
                // The filename is last and may contain spaces or strings resembling fields.
                val fields = line.substringBefore(" name=")
                filename = line.substringAfter(" name=", "").trim().take(2048).ifEmpty { null }
                total = fields.field("size")?.toLongOrNull() ?: 0L
                ensureUsenet(total > 0, "Invalid yEnc file size.")
                multipart = fields.field("part") != null
                if (!multipart) end = total
            }
            multipart && end == 0L -> {
                ensureUsenet(line.startsWith("=ypart "), "Missing yEnc part range.")
                val first = line.field("begin")?.toLongOrNull() ?: 0
                end = line.field("end")?.toLongOrNull() ?: 0
                ensureUsenet(first in 1..end && end <= total && end - first < MAX_PART, "Invalid yEnc part range.")
                start = first - 1
            }
            line.startsWith("=yend ") -> {
                val size = result.size().toLong()
                ensureUsenet(size > 0 && size == end - start && line.field("size")?.toLongOrNull() == size,
                    "Incomplete Usenet article.")
                val bytes = result.toByteArray()
                val crc = line.field(if (multipart) "pcrc32" else "crc32")
                ensureUsenet(crc == null || crc.toLongOrNull(16) == CRC32().apply { update(bytes) }.value,
                    "Usenet article failed its checksum.")
                decoded = bytes
            }
            else -> {
                var index = if (line.startsWith("..")) 1 else 0
                while (index < line.length) {
                    var value = line[index++].code
                    if (value == 61) {
                        ensureUsenet(index < line.length, "Invalid yEnc escape.")
                        value = line[index++].code - 64
                    }
                    result.write(value - 42)
                }
                ensureUsenet(result.size() <= MAX_PART, "Usenet article exceeds the size limit.")
            }
        }
    }
    return Article(start, total, decoded ?: throw UsenetFailure("Missing or incomplete yEnc article."), filename)
}

private class NewsServer(val host: String, val port: Int, val tls: Boolean, val user: String?, val pass: String?) {
    companion object {
        fun parse(value: String): NewsServer? = runCatching {
            val uri = URI(value)
            val tls = uri.scheme.equals("nntps", true)
            require(tls || uri.scheme.equals("nntp", true))
            val host = requireNotNull(uri.host)
            val port = if (uri.port == -1) (if (tls) 563 else 119) else uri.port
            require(port in 1..65535 && uri.query == null && uri.fragment == null)
            fun decode(part: String) = URLDecoder.decode(part.replace("+", "%2B"), "UTF-8").also {
                require(it.length <= 512 && it.none { c -> c.isISOControl() })
            }
            val auth = uri.rawUserInfo?.split(':', limit = 2)
            NewsServer(host, port, tls, auth?.first()?.let(::decode), auth?.getOrNull(1)?.let(::decode))
        }.getOrNull()
    }
}

private class NewsConnection(server: NewsServer, tlsFactory: SSLSocketFactory, register: (Socket) -> Unit) : Closeable {
    private var socket = connect(server, register)
    private val input: BufferedInputStream
    private var pending: String? = null
    init {
        try {
            socket.soTimeout = 12_000
            if (server.tls) {
                val secure = tlsFactory.createSocket(socket, server.host, server.port, true) as SSLSocket
                socket = secure
                register(secure)
                secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                secure.startHandshake()
            }
            input = socket.getInputStream().buffered()
            ensureUsenet(status() in setOf(200, 201), "Usenet provider refused the connection.")
            if (server.user != null) {
                var auth = command("AUTHINFO USER " + server.user)
                if (auth == 381) auth = command("AUTHINFO PASS " + server.pass.orEmpty())
                ensureUsenet(auth == 281, "Usenet provider rejected sign-in.")
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }
    private companion object {
        fun connect(server: NewsServer, register: (Socket) -> Unit): Socket {
            val addresses = InetAddress.getAllByName(server.host)
            var failure: IOException? = null
            for (address in addresses.take(4)) {
                val candidate = Socket()
                try {
                    register(candidate)
                    candidate.connect(InetSocketAddress(address, server.port), 4_000)
                    return candidate
                } catch (error: IOException) {
                    candidate.close()
                    failure = error
                }
            }
            throw failure ?: UnknownHostException()
        }
    }
    private fun status() = input.newsLine().take(3).toIntOrNull()
    private fun send(value: String) = socket.getOutputStream().write((value + "\r\n").toByteArray(Charsets.UTF_8))
    private fun command(value: String): Int? {
        send(value)
        return status()
    }

    fun canFetch(id: String) = pending == null || pending == id

    fun fetch(id: String, next: String?): Article {
        if (pending == null) send("BODY <$id>")
        val response = status()
        if (response == 430 || response == 423) throw MissingUsenetArticle()
        ensureUsenet(response == 222, "Usenet provider could not return the article.")
        // Pipeline a single article on sequential playback, without increasing the provider's connection count.
        pending = next
        if (next != null) send("BODY <$next>")
        return decodeArticle(input)
    }
    override fun close() = socket.close()
}

internal class MissingUsenetArticle : UsenetFailure("Usenet article unavailable on every configured provider.")

/** One authenticated connection at a time, including for parallel player range requests. */
internal class NewsArticles(serverUrls: List<String>, private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory) : Closeable {
    private val servers = serverUrls.take(8).mapNotNull(NewsServer::parse)
    private var connection: NewsConnection? = null
    private var preferred = 0
    @Volatile private var closed = false
    private val socketLock = Any()
    private var activeSocket: Socket? = null
    private fun registerSocket(socket: Socket) = synchronized(socketLock) {
        if (closed) {
            socket.close()
            throw UsenetFailure("Usenet session expired.")
        }
        activeSocket = socket
    }
    init { ensureUsenet(servers.isNotEmpty(), "The add-on supplied no usable NNTP server.") }

    @Synchronized fun fetch(id: String, next: String? = null): Article {
        ensureUsenet(!closed, "Usenet session expired.")
        var lastFailure: String? = null
        var missingProviders = 0
        for (step in servers.indices) {
            val index = (preferred + step) % servers.size
            // An idle socket can have been dropped by the server. Reconnect once in that case.
            val attempts = if (connection != null) 2 else 1
            for (attempt in 0 until attempts) {
                ensureUsenet(!closed, "Usenet session expired.")
                var readingArticle = false
                try {
                    if (connection?.canFetch(id) == false) {
                        connection?.close()
                        connection = null
                    }
                    val active = connection ?: NewsConnection(servers[index], tlsFactory, ::registerSocket).also { connection = it }
                    readingArticle = true
                    val article = active.fetch(id, next)
                    preferred = index
                    return article
                } catch (error: IOException) {
                    lastFailure = when (error) {
                        is UsenetFailure -> error.message
                        is UnknownHostException -> "Could not resolve the NNTP provider hostname."
                        is SSLException -> "The secure connection to the NNTP provider failed. Check the TLS setting and port."
                        is SocketTimeoutException -> if (readingArticle) "Timed out reading a Usenet article."
                            else "Timed out connecting or signing in to the NNTP provider."
                        is ConnectException -> "Could not reach the NNTP provider on port ${servers[index].port}."
                        else -> if (readingArticle) "The connection dropped while reading a Usenet article."
                            else "The connection to the NNTP provider failed during startup."
                    }
                    runCatching { connection?.close() }
                    connection = null
                    if (error is MissingUsenetArticle) {
                        missingProviders++
                        break
                    }
                }
            }
        }
        if (missingProviders == servers.size) throw MissingUsenetArticle()
        throw UsenetFailure(lastFailure ?: "Could not connect to the Usenet provider.")
    }
    override fun close() = synchronized(socketLock) {
        closed = true
        runCatching { activeSocket?.close() }
        activeSocket = null
    }
}
