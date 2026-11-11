package com.nuvio.tv.core.usenet

import java.io.InputStream
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
/** One reader owns all read-ahead, including status lines between pipelined articles. */
internal class NewsWireReader(private val input: InputStream) {
    private val block = ByteArray(64 * 1024)
    private var position = 0
    private var end = 0
    val line = ByteArray(8192)

    fun readLine(limit: Int = line.size): Int {
        var length = 0
        while (true) {
            if (position == end) {
                end = input.read(block)
                if (end < 0) throw UsenetFailure("Usenet connection ended early.")
                position = 0
            }
            val start = position
            while (position < end && block[position] != 10.toByte()) position++
            val count = position - start
            ensureUsenet(count <= limit - length, "Invalid Usenet article line.")
            block.copyInto(line, length, start, position)
            length += count
            if (position < end) {
                position++
                return length
            }
        }
    }

    fun statusCode(): Int? {
        val length = readLine(line.size - 1)
        return String(line, 0, length, Charsets.ISO_8859_1).removeSuffix("\r").take(3).toIntOrNull()
    }
}

internal fun decodeArticle(input: NewsWireReader): Article {
    var total = 0L
    var start = 0L
    var multipart = false
    var payload: ByteArray? = null
    var written = 0
    var finished = false
    var filename: String? = null
    val line = input.line
    var wireBytes = 0
    fun String.field(name: String) = split(' ').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    fun buffer(size: Long): ByteArray {
        ensureUsenet(size in 1..MAX_PART.toLong(), "Usenet article exceeds the size limit.")
        return ByteArray(size.toInt())
    }
    while (true) {
        var length = input.readLine()
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
                         val limit: Int, val limitFromAddon: Boolean) {
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
            val addonLimit = uri.path.orEmpty().trim('/').toIntOrNull()
            val limit = addonLimit?.coerceIn(1, 12) ?: 2
            NewsServer(host, port, tls, auth?.first()?.let(::decode), auth?.getOrNull(1)?.let(::decode),
                limit, limitFromAddon = addonLimit != null)
        }.getOrNull()
    }
}

private class NewsConnection(server: NewsServer, tlsFactory: SSLSocketFactory, private var deadline: Long,
                             trace: UsenetStartupTrace?, register: (Socket) -> Unit) : Closeable {
    private val connectStarted = System.nanoTime()
    private var socket = connect(server, register)
    private val input: NewsWireReader
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
            input = NewsWireReader(object : InputStream() {
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
            })
            ensureUsenet(status() in setOf(200, 201), "Usenet provider refused the connection.")
            if (server.user != null) {
                var auth = command("AUTHINFO USER " + server.user)
                if (auth == 381) auth = command("AUTHINFO PASS " + server.pass.orEmpty())
                ensureUsenet(auth == 281, "Usenet provider rejected sign-in.")
            }
            trace?.mark("first_login")
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
    // Includes TCP, TLS and authentication. Compare this connection's observed costs.
    private val setupNanos = System.nanoTime() - connectStarted
    private var transferNanos = 0L
    var transferLimited: Boolean? = null
        private set
    // The queued BODY commands already shared the preceding request's round trip.
    fun economicalDrain(): Boolean = pending.isNotEmpty() && transferNanos > 0 &&
        transferNanos * pending.size < setupNanos

    private fun status() = input.statusCode()
    private fun send(value: String) = socket.getOutputStream().write((value + "\r\n").toByteArray(Charsets.UTF_8))
    private fun command(value: String): Int? {
        send(value)
        return status()
    }

    val hasQueuedReplies get() = pending.isNotEmpty()
    fun canFetch(id: String, allowQueued: Boolean) = pending.isEmpty() || (allowQueued && pending.peekFirst() == id)

    fun fetch(id: String, next: List<String>, readDeadline: Long): Article {
        deadline = readDeadline
        // An incompatible idle lease is reused only when draining is cheaper than login.
        if (pending.isNotEmpty() && pending.peekFirst() != id) {
            deadline = minOf(readDeadline, System.nanoTime() + setupNanos)
        }
        while (pending.isNotEmpty() && pending.peekFirst() != id) {
            pending.removeFirst()
            when (status()) {
                222 -> decodeArticle(input)
                430, 423 -> Unit
                else -> throw UsenetFailure("Unexpected queued Usenet response.")
            }
        }
        deadline = readDeadline
        val start = System.nanoTime()
        val queued = pending.isNotEmpty()
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
        val statusAt = System.nanoTime()
        if (response == 430 || response == 423) throw MissingUsenetArticle()
        ensureUsenet(response == 222, "Usenet provider could not return the article.")
        return decodeArticle(input).also {
            // A prefetched reply may already be in the kernel buffer; it would underestimate a cold transfer.
            if (!queued) {
                val end = System.nanoTime()
                transferNanos = end - statusAt
                transferLimited = transferNanos > statusAt - start
            }
        }
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

    fun acquire(server: NewsServer, factory: SSLSocketFactory, owner: NewsArticles, id: String, allowQueued: Boolean, readDeadline: Long): Slot {
        val poolDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        synchronized(lock) {
            while (true) {
                ensureUsenet(!owner.closed, "Usenet session expired.")
                val account = slots.filter { it.server.sameAccount(server) }
                account.filter { !it.busy && it.factory === factory && it.server.sameLogin(server) &&
                    it.connection?.canFetch(id, allowQueued) == true }.maxByOrNull { if (it.connection?.hasQueuedReplies == true) 1 else 0 }?.let {
                    it.busy = true
                    it.owner = owner
                    return it
                }
                val cap = minOf(server.limit, account.minOfOrNull { it.server.limit } ?: server.limit)
                val atAccountLimit = account.size >= cap
                val pool = if (atAccountLimit) account else slots
                if (atAccountLimit || slots.size >= 24) {
                    if (atAccountLimit && allowQueued) account.firstOrNull { !it.busy &&
                        it.factory === factory && it.server.sameLogin(server) && it.connection?.economicalDrain() == true
                    }?.let {
                        it.busy = true
                        it.owner = owner
                        return it
                    }
                    val idle = pool.firstOrNull { !it.busy }
                    if (idle == null) {
                        val limit = if (pool.any { it.owner === owner }) readDeadline else minOf(readDeadline, poolDeadline)
                        val left = limit - System.nanoTime()
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

    /** Speculation may consume a spare lease, never wait, evict a slot or take the only allowance. */
    fun spare(server: NewsServer, factory: SSLSocketFactory, owner: NewsArticles, id: String): Slot? = synchronized(lock) {
        if (owner.closed) return null
        val account = slots.filter { it.server.sameAccount(server) }
        val cap = minOf(server.limit, account.minOfOrNull { it.server.limit } ?: server.limit)
        if (cap < 2 || account.count { it.busy } >= cap - 1 || slots.count { it.busy } >= 23) return null
        // Leave any queued sequential replies for their current reader.
        account.firstOrNull { !it.busy && it.owner === owner && it.factory === factory &&
            it.server.sameLogin(server) && it.connection?.canFetch(id, false) == true }?.let {
            it.busy = true
            return it
        }
        if (account.size >= cap || slots.size >= 24) return null
        Slot(server, factory, owner).also(slots::add)
    }

    fun warmSlot(server: NewsServer, factory: SSLSocketFactory, owner: NewsArticles): Slot? = synchronized(lock) {
        if (owner.closed) return null
        val account = slots.filter { it.server.sameAccount(server) }
        val cap = minOf(server.limit, account.minOfOrNull { it.server.limit } ?: server.limit)
        if (account.size >= cap || slots.size >= 24) return null
        Slot(server, factory, owner).also(slots::add)
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

/** Each socket has one exclusive lease. Account limits span readers and sessions. */
internal class NewsArticles(serverUrls: List<String>, private val tlsFactory: SSLSocketFactory = defaultTls,
                            private val readBudgetMs: Long = 45_000L, private val trace: UsenetStartupTrace? = null) : Closeable {
    private val servers = serverUrls.take(8).mapNotNull(NewsServer::parse)
    @Volatile private var preferred = 0
    private val serialOnly = java.util.concurrent.atomic.AtomicIntegerArray(servers.size)
    private val transferLimited = java.util.concurrent.atomic.AtomicIntegerArray(servers.size)
    val sequentialReadAhead get() = canPrefetch && transferLimited.get(preferred) != 0
    val canPrefetch get() = servers[preferred].limit > 1 && serialOnly.get(preferred) == 0
    @Volatile var closed = false
        private set
    init {
        ensureUsenet(servers.isNotEmpty(), "The add-on supplied no usable NNTP server.")
        prefer(0)
    }

    @Synchronized private fun prefer(index: Int) {
        preferred = index
        trace?.connections(servers[index].limit, servers[index].limitFromAddon)
    }

    fun warm(count: Int) {
        repeat(minOf(count, servers[preferred].limit)) {
            kotlinx.coroutines.Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
                val server = servers[preferred]
                val slot = NewsPool.warmSlot(server, tlsFactory, this) ?: return@dispatch
                var healthy = false
                try {
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
                    slot.connection = NewsConnection(server, tlsFactory, deadline, trace) { NewsPool.register(slot, it) }
                    healthy = true
                } catch (_: Exception) {
                    trace?.add("warm_failures")
                } finally { NewsPool.finish(slot, healthy) }
            }
        }
    }

    fun fetchAhead(id: String): Article? {
        val index = preferred
        val server = servers[index]
        if (serialOnly.get(index) != 0) return null
        val slot = NewsPool.spare(server, tlsFactory, this, id) ?: return null
        var healthy = false
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(readBudgetMs)
            val connection = slot.connection ?: NewsConnection(server, tlsFactory, deadline, trace) { NewsPool.register(slot, it) }
                .also { slot.connection = it }
            return connection.fetch(id, emptyList(), deadline).also { healthy = true }
        } finally {
            NewsPool.finish(slot, healthy)
        }
    }

    fun fetch(id: String, next: List<String> = emptyList()): Article {
        ensureUsenet(!closed, "Usenet session expired.")
        var lastFailure: String? = null
        var missingProviders = 0
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(readBudgetMs)
        val firstProvider = preferred
        for (step in servers.indices) {
            val index = (firstProvider + step) % servers.size
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
                    val lease = NewsPool.acquire(servers[index], tlsFactory, this, id, allowQueued = serialOnly.get(index) == 0, readDeadline = deadline)
                    slot = lease
                    reused = lease.connection != null
                    val active = lease.connection ?: NewsConnection(servers[index], tlsFactory, deadline, trace) { NewsPool.register(lease, it) }
                        .also { lease.connection = it }
                    readingArticle = true
                    val draining = !active.canFetch(id, allowQueued = true)
                    // A speculative drain exceeding its budget should retry on a fresh connection.
                    // It says nothing about whether this provider accepts pipelined commands.
                    val ahead = if (draining || serialOnly.get(index) != 0) emptyList() else next
                    pipelined = !draining && (active.hasQueuedReplies || ahead.isNotEmpty())
                    val article = active.fetch(id, ahead, deadline)
                    active.transferLimited?.let { transferLimited.set(index, if (it) 1 else 0) }
                    healthy = true
                    prefer(index)
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
                    if (pipelined && serialOnly.get(index) == 0) {
                        // Some servers/proxies reject overlapping BODY commands. Retry the
                        // requested article alone on a fresh socket and remember this provider.
                        serialOnly.set(index, 1)
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
