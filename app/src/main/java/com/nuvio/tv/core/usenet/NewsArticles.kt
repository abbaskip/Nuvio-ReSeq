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
    var multipart = false
    var payload: ByteArray? = null
    var written = 0
    var finished = false
    var filename: String? = null
    val line = ByteArray(8192)
    var wireBytes = 0
    fun String.field(name: String) = split(' ').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    fun buffer(size: Long): ByteArray {
        ensureUsenet(size in 1..MAX_PART.toLong(), "Usenet article exceeds the size limit.")
        return ByteArray(size.toInt())
    }
    while (true) {
        var length = 0
        while (true) {
            val c = input.read()
            if (c < 0) throw UsenetFailure("Usenet connection ended early.")
            if (c == 10) break
            ensureUsenet(length < line.size, "Invalid Usenet article line.")
            line[length++] = c.toByte()
        }
        wireBytes += length + 1
        ensureUsenet(wireBytes <= MAX_PART * 3, "Usenet article exceeds the size limit.")
        if (length > 0 && line[length - 1] == 13.toByte()) length--
        if (length == 1 && line[0] == 46.toByte()) break
        if (finished) continue
        if (payload == null) {
            val header = String(line, 0, length, Charsets.ISO_8859_1)
            if (total == 0L) {
                if (!header.startsWith("=ybegin ")) continue
                val fields = header.substringBefore(" name=")
                filename = header.substringAfter(" name=", "").trim().take(2048).ifEmpty { null }
                total = fields.field("size")?.toLongOrNull() ?: 0L
                ensureUsenet(total > 0, "Invalid yEnc file size.")
                multipart = fields.field("part") != null
                if (!multipart) payload = buffer(total)
            } else {
                ensureUsenet(header.startsWith("=ypart "), "Missing yEnc part range.")
                val first = header.field("begin")?.toLongOrNull() ?: 0
                val end = header.field("end")?.toLongOrNull() ?: 0
                ensureUsenet(first in 1..end && end <= total, "Invalid yEnc part range.")
                start = first - 1
                payload = buffer(end - start)
            }
            continue
        }
        if (length >= 6 && line[0] == 61.toByte() && line[1] == 121.toByte() &&
            String(line, 0, 6, Charsets.US_ASCII) == "=yend ") {
            val trailer = String(line, 0, length, Charsets.US_ASCII)
            ensureUsenet(written == payload.size && trailer.field("size")?.toLongOrNull() == written.toLong(),
                "Incomplete Usenet article.")
            val crc = trailer.field(if (multipart) "pcrc32" else "crc32")
            ensureUsenet(crc == null || crc.toLongOrNull(16) == CRC32().apply { update(payload) }.value,
                "Usenet article failed its checksum.")
            finished = true
            continue
        }
        var index = if (length >= 2 && line[0] == 46.toByte() && line[1] == 46.toByte()) 1 else 0
        while (index < length) {
            var value = line[index++].toInt()
            if (value == 61) {
                ensureUsenet(index < length, "Invalid yEnc escape.")
                value = line[index++].toInt() - 64
            }
            ensureUsenet(written < payload.size, "Usenet article exceeds its declared size.")
            payload[written++] = (value - 42).toByte()
        }
    }
    if (!finished || payload == null) throw UsenetFailure("Missing or incomplete yEnc article.")
    return Article(start, total, payload, filename)
}

private class NewsServer(val host: String, val port: Int, val tls: Boolean, val user: String?, val pass: String?,
                         val limit: Int) {
    fun sameAccount(other: NewsServer) = host.equals(other.host, true) && user == other.user
    fun sameLogin(other: NewsServer) = sameAccount(other) && port == other.port && tls == other.tls && pass == other.pass

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
            val limit = uri.path.orEmpty().trim('/').toIntOrNull()?.coerceIn(1, 2) ?: 1
            NewsServer(host, port, tls, auth?.first()?.let(::decode), auth?.getOrNull(1)?.let(::decode), limit)
        }.getOrNull()
    }
}

private class NewsConnection(server: NewsServer, tlsFactory: SSLSocketFactory, private var deadline: Long,
                             register: (Socket) -> Unit) : Closeable {
    private var socket = connect(server, register)
    private val input: BufferedInputStream
    private val pending = java.util.ArrayDeque<String>(3)
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
            // A socket idle timeout alone can be kept alive indefinitely by trickled bytes.
            val raw = socket.getInputStream()
            input = object : java.io.InputStream() {
                var timeout = 12_000
                private fun updateTimeout() {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw SocketTimeoutException()
                    val ms = ((remaining / 1_000_000) + 1).coerceAtMost(12_000).toInt()
                    if (ms != timeout) {
                        socket.soTimeout = ms
                        timeout = ms
                    }
                }
                override fun read(): Int {
                    updateTimeout()
                    return raw.read()
                }
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    updateTimeout()
                    return raw.read(buffer, off, len)
                }
            }.buffered()
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

    val hasQueuedReplies get() = pending.isNotEmpty()
    fun canFetch(id: String, allowQueued: Boolean) = pending.isEmpty() || (allowQueued && pending.peekFirst() == id)

    fun fetch(id: String, next: List<String>, readDeadline: Long): Article {
        deadline = readDeadline
        // Commands share one authenticated connection. Responses stay in wire order.
        // On a seek the pool selects a compatible socket or replaces the old one.
        val commands = buildString {
            if (pending.isEmpty()) {
                append("BODY <").append(id).append(">\r\n")
            } else {
                check(pending.removeFirst() == id)
            }
            if (pending.withIndex().all { (i, queued) -> next.getOrNull(i) == queued }) {
                for (i in pending.size until minOf(2, next.size)) {
                    pending.addLast(next[i])
                    append("BODY <").append(next[i]).append(">\r\n")
                }
            }
        }
        if (commands.isNotEmpty()) socket.getOutputStream().write(commands.toByteArray(Charsets.UTF_8))
        val response = status()
        if (response == 430 || response == 423) throw MissingUsenetArticle()
        ensureUsenet(response == 222, "Usenet provider could not return the article.")
        return decodeArticle(input)
    }
    override fun close() = socket.close()
}

internal class MissingUsenetArticle : UsenetFailure("Usenet article unavailable on every configured provider.")

/** A lease lasts for one BODY response. Idle authenticated sockets can follow playback between sessions. */
private object NewsPool {
    private val lock = Object()
    class Slot(val server: NewsServer, val factory: SSLSocketFactory, var owner: NewsArticles) {
        var busy = true
        var socket: Socket? = null
        var connection: NewsConnection? = null
    }
    private val slots = mutableListOf<Slot>()

    fun acquire(server: NewsServer, factory: SSLSocketFactory, owner: NewsArticles, id: String, allowQueued: Boolean): Slot {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        synchronized(lock) {
            while (true) {
                ensureUsenet(!owner.closed, "Usenet session expired.")
                val account = slots.filter { it.server.sameAccount(server) }
                account.firstOrNull { !it.busy && it.factory === factory && it.server.sameLogin(server) &&
                    it.connection?.canFetch(id, allowQueued) == true }?.let {
                    it.busy = true
                    it.owner = owner
                    return it
                }
                val cap = minOf(server.limit, account.minOfOrNull { it.server.limit } ?: server.limit)
                val atAccountLimit = account.size >= cap
                val pool = if (atAccountLimit) account else slots
                if (atAccountLimit || slots.size >= 8) {
                    val idle = pool.firstOrNull { !it.busy }
                    if (idle == null) {
                        val left = deadline - System.nanoTime()
                        ensureUsenet(left > 0, "The NNTP provider connection limit is busy.")
                        java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lock, left)
                        continue
                    }
                    remove(idle)
                }
                return Slot(server, factory, owner).also(slots::add)
            }
        }
    }

    fun register(slot: Slot, socket: Socket) = synchronized(lock) {
        if (slot !in slots) {
            socket.close()
            throw UsenetFailure("Usenet session expired.")
        }
        slot.socket = socket
    }

    private fun remove(slot: Slot) {
        slots.remove(slot)
        runCatching { slot.socket?.close() }
    }

    fun finish(slot: Slot, healthy: Boolean) = synchronized(lock) {
        if (healthy) slot.busy = false else remove(slot)
        lock.notifyAll()
    }

    fun close(owner: NewsArticles) = synchronized(lock) {
        slots.filter { it.owner === owner }.forEach(::remove)
        lock.notifyAll()
    }
}

private val defaultTls by lazy { SSLSocketFactory.getDefault() as SSLSocketFactory }

/** Each session serializes its reads. Provider leases and account limits span sessions. */
internal class NewsArticles(serverUrls: List<String>, private val tlsFactory: SSLSocketFactory = defaultTls,
                            private val readBudgetMs: Long = 45_000L) : Closeable {
    private val servers = serverUrls.take(8).mapNotNull(NewsServer::parse)
    private var preferred = 0
    private val serialOnly = BooleanArray(servers.size)
    @Volatile var closed = false
        private set
    init { ensureUsenet(servers.isNotEmpty(), "The add-on supplied no usable NNTP server.") }

    @Synchronized fun fetch(id: String, next: List<String> = emptyList()): Article {
        ensureUsenet(!closed, "Usenet session expired.")
        var lastFailure: String? = null
        var missingProviders = 0
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(readBudgetMs)
        for (step in servers.indices) {
            val index = (preferred + step) % servers.size
            // An idle socket can have been dropped by the server. Reconnect once in that case.
            for (attempt in 0..1) {
                ensureUsenet(!closed, "Usenet session expired.")
                ensureUsenet(System.nanoTime() < deadline, "Timed out retrieving the Usenet article.")
                var readingArticle = false
                var pipelined = false
                var slot: NewsPool.Slot? = null
                var reused = false
                var healthy = false
                try {
                    val lease = NewsPool.acquire(servers[index], tlsFactory, this, id, allowQueued = !serialOnly[index])
                    slot = lease
                    reused = lease.connection != null
                    val active = lease.connection ?: NewsConnection(servers[index], tlsFactory, deadline) { NewsPool.register(lease, it) }
                        .also { lease.connection = it }
                    readingArticle = true
                    val ahead = if (serialOnly[index]) emptyList() else next
                    pipelined = active.hasQueuedReplies || ahead.isNotEmpty()
                    val article = active.fetch(id, ahead, deadline)
                    healthy = true
                    preferred = index
                    return article
                } catch (error: IOException) {
                    if (error is MissingUsenetArticle) {
                        missingProviders++
                        break
                    }
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
                    if (pipelined && !serialOnly[index]) {
                        // Some servers/proxies reject overlapping BODY commands. Retry the
                        // requested article alone on a fresh socket and remember this provider.
                        serialOnly[index] = true
                    } else if (!reused) break
                } finally {
                    slot?.let { NewsPool.finish(it, healthy) }
                }
            }
        }
        if (missingProviders == servers.size) throw MissingUsenetArticle()
        throw UsenetFailure(lastFailure ?: "Could not connect to the Usenet provider.")
    }
    override fun close() {
        closed = true
        NewsPool.close(this)
    }
}
