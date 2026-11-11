package com.nuvio.tv.core.usenet

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamDto
import fi.iki.elonen.NanoHTTPD
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsenetConcurrencyTest {
    private class Provider(private val total: Int = 40, private val hold: Set<Int> = emptySet(),
                           private val unavailable: Boolean = false) : Closeable {
        private val listener = ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val loggedIn = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sixBodies = CountDownLatch(6)
        val maxSockets = AtomicInteger()
        val maxBodies = AtomicInteger()
        private val activeBodies = AtomicInteger()
        val endpoint = "nntp://user:password@127.0.0.1:${listener.localPort}"
        init {
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: IOException) { break }
                    sockets.add(socket)
                    maxSockets.accumulateAndGet(sockets.size, ::maxOf)
                    thread(isDaemon = true) {
                        try {
                            socket.use {
                                val reader = socket.getInputStream().bufferedReader()
                                val out = socket.getOutputStream()
                                fun line(text: String) { out.write((text + "\r\n").toByteArray()) }
                                line("200 ready")
                                while (true) {
                                    val command = reader.readLine() ?: break
                                    when {
                                        command.startsWith("AUTHINFO USER") -> line("381 password")
                                        command.startsWith("AUTHINFO PASS") -> { line("281 accepted"); loggedIn.countDown() }
                                        command.startsWith("BODY <") -> {
                                            val id = command.removePrefix("BODY <").removeSuffix(">").toInt()
                                            if (unavailable) { line("430 unavailable"); continue }
                                            val active = activeBodies.incrementAndGet()
                                            maxBodies.accumulateAndGet(active, ::maxOf)
                                            try {
                                                if (id in hold) {
                                                    sixBodies.countDown()
                                                    check(release.await(8, TimeUnit.SECONDS))
                                                }
                                                line("222 body")
                                                line("=ybegin part=1 line=128 size=$total name=private.mkv")
                                                line("=ypart begin=${id + 1} end=${id + 1}")
                                                line("+") // One decoded byte, value 1.
                                                line("=yend size=1")
                                                line(".")
                                            } finally { activeBodies.decrementAndGet() }
                                        }
                                    }
                                }
                            }
                        } catch (_: IOException) {
                        } finally { sockets.remove(socket); disconnected.countDown() }
                    }
                }
            }
        }
        override fun close() { release.countDown(); listener.close(); sockets.forEach { it.close() } }
    }
    private val xml = """<nzb><file subject="movie.mkv"><segments><segment number="1">0</segment></segments></file></nzb>"""

    @Test fun startupSummaryReportsClampedAddonOrDefaultAllowanceWithoutProviderDetails() {
        for ((suffix, expected) in listOf("/5" to "conns=5 (addon)", "" to "conns=2 (default)",
            "/25" to "conns=12 (addon)", "/" to "conns=2 (default)", "/0" to "conns=1 (addon)")) {
            val logs = mutableListOf<String>()
            val trace = UsenetStartupTrace(logs::add)
            NewsArticles(listOf("nntps://fixture-user:fixture-password@provider.invalid$suffix"), trace = trace).use {
                trace.finish(false) // The configured allowance is available even if startup fails before a BODY.
                trace.finish(true)
            }
            assertEquals(1, logs.size)
            assertTrue(logs.single().contains(expected))
            for (privateValue in listOf("fixture-user", "fixture-password", "provider.invalid", "nntps://")) {
                assertFalse(logs.single().contains(privateValue))
            }
        }
    }

    @Test fun startupSummaryUsesThePreferredProviderAfterFallback() {
        Provider(unavailable = true).use { first ->
            Provider().use { fallback ->
                val logs = mutableListOf<String>()
                val trace = UsenetStartupTrace(logs::add)
                NewsArticles(listOf(first.endpoint, fallback.endpoint + "/5"), trace = trace).use { reader ->
                    assertArrayEquals(byteArrayOf(1), reader.fetch("0").bytes)
                    trace.finish(true)
                }
                assertEquals(1, logs.size)
                assertTrue(logs.single().contains("conns=5 (addon)"))
                assertFalse(logs.single().contains("conns=2"))
                for (privateValue in listOf("127.0.0.1", "user", "password", "nntp://", "private.mkv")) {
                    assertFalse(logs.single().contains(privateValue))
                }
            }
        }
    }

    @Test fun preparationLogsInWhileNzbResponseIsDelayedAndRepeatOpenUsesMemoryCache() = runBlocking {
        Provider(total = 1).use { provider ->
            val downloads = AtomicInteger()
            val server = object : NanoHTTPD("127.0.0.1", 0) {
                override fun serve(session: IHTTPSession): Response {
                    downloads.incrementAndGet()
                    assertTrue("Login must run before NZB retrieval completes", provider.loggedIn.await(3, TimeUnit.SECONDS))
                    Thread.sleep(500)
                    return newFixedLengthResponse(xml)
                }
            }
            server.start()
            try {
                val stream = StreamDto(nzbUrl = "http://127.0.0.1:${server.listeningPort}/release",
                    servers = listOf(provider.endpoint + "/1")).toDomain("fixture", null)
                repeat(2) {
                    val resolved = UsenetPlayback.resolve(stream)
                    UsenetPlayback.release(resolved.url!!)
                }
                assertEquals(1, downloads.get())
                assertTrue(provider.disconnected.await(3, TimeUnit.SECONDS))
            } finally { server.stop() }
        }
    }
    @Test fun cancellingPendingNzbClosesTheWarmAuthenticatedSocket() = runBlocking {
        Provider().use { provider ->
            val accepted = CountDownLatch(1)
            val closed = CountDownLatch(1)
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { http ->
                thread(isDaemon = true) {
                    http.accept().use { socket ->
                        accepted.countDown()
                        while (socket.getInputStream().read() >= 0) { }
                        closed.countDown()
                    }
                }
                val stream = StreamDto(nzbUrl = "http://127.0.0.1:${http.localPort}/pending",
                    servers = listOf(provider.endpoint + "/1")).toDomain("fixture", null)
                val pending = async(Dispatchers.IO) { UsenetPlayback.resolve(stream) }
                try {
                    assertTrue(accepted.await(3, TimeUnit.SECONDS))
                    assertTrue(provider.loggedIn.await(3, TimeUnit.SECONDS))
                } finally { pending.cancelAndJoin() }
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                assertTrue(provider.disconnected.await(2, TimeUnit.SECONDS))
            }
        }
    }
    @Test fun sixSpeculativeBodiesLeaveCapacityForColdSeekAndStayWithinAccountAllowance() {
        Provider(hold = (2..13).toSet()).use { provider ->
            NewsArticles(listOf(provider.endpoint + "/8")).use { reader ->
                val cache = ArticleCache(fetchAhead = reader::fetchAhead, profile = UsenetMemoryProfile.NORMAL, fetch = reader::fetch)
                val file = PostedFile(NzbFile("movie.mkv", (0..39).map(Int::toString), 40), cache, verifyTail = false)
                assertArrayEquals(byteArrayOf(1), file.bytesAt(1, 1))
                assertTrue(provider.sixBodies.await(3, TimeUnit.SECONDS))
                val worker = Executors.newSingleThreadExecutor()
                try {
                    val seek = worker.submit<ByteArray> { file.bytesAt(39, 1) }
                    assertArrayEquals(byteArrayOf(1), seek.get(2, TimeUnit.SECONDS))
                    assertTrue(provider.maxBodies.get() >= 6)
                    assertTrue(provider.maxSockets.get() <= 8)
                    assertTrue(cache.retainedBytes() <= UsenetMemoryProfile.NORMAL.cacheBytes)
                } finally { provider.release.countDown(); worker.shutdownNow() }
                assertArrayEquals(ByteArray(40) { 1 }, file.bytesAt(0, 40))
            }
        }
    }
    @Test fun closedOwnerCannotOpenLateWarmupSockets() {
        Provider().use { provider ->
            val reader = NewsArticles(listOf(provider.endpoint + "/8"))
            reader.close()
            reader.warm(8)
            assertFalse(provider.loggedIn.await(200, TimeUnit.MILLISECONDS))
            assertEquals(0, provider.maxSockets.get())
        }
    }
}
