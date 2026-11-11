package com.nuvio.tv.core.usenet

import com.nuvio.tv.data.mapper.toDomain
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsenetReliabilityTest {
    private val xml = """<nzb><file subject="episode.mp4"><segments><segment number="1">one</segment></segments></file></nzb>"""

    @Test fun stalledArticleDoesNotBlockACachedReadOrAnIndependentSeek() {
        val held = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val ids = (0..7).map(Int::toString)
        val requests = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val cache = ArticleCache { id, _ ->
            requests.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
            if (id == "1") {
                held.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
            Article(id.toLong(), 8L, byteArrayOf(id.toByte()))
        }
        val file = PostedFile(NzbFile("fixture.mp4", ids, 8L), cache)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val slow = workers.submit<ByteArray> { file.bytesAt(1, 1) }
            assertTrue(held.await(2, TimeUnit.SECONDS))
            val same = workers.submit<ByteArray> { file.bytesAt(1, 1) }
            val seek = workers.submit<ByteArray> { file.bytesAt(5, 1) + file.bytesAt(0, 1) }
            assertArrayEquals(byteArrayOf(5, 0), seek.get(2, TimeUnit.SECONDS))
            resume.countDown()
            assertArrayEquals(slow.get(2, TimeUnit.SECONDS), same.get(2, TimeUnit.SECONDS))
            assertEquals(1, requests["1"]?.get())
        } finally { resume.countDown(); workers.shutdownNow() }
    }

    @Test fun missingArticlesAreRememberedButTransportFailuresRemainRetryable() {
        val requests = AtomicInteger()
        val absent = ArticleCache { _, _ -> requests.incrementAndGet(); throw MissingUsenetArticle() }
        repeat(3) { assertThrows(MissingUsenetArticle::class.java) { absent["one"] } }
        assertEquals(1, requests.get())
        val transient = ArticleCache { _, _ -> requests.incrementAndGet(); throw IOException("transient") }
        repeat(2) { assertThrows(IOException::class.java) { transient["one"] } }
        assertEquals(3, requests.get())
    }

    @Test fun accountWithOneSlotHandsItsAuthenticatedSocketToAnOverlappingSession() {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { listener ->
            val endpoint = "nntp://127.0.0.1:" + listener.localPort + "/1"
            val entered = CountDownLatch(1)
            val sendFirst = CountDownLatch(1)
            val attempts = AtomicInteger()
            val server = thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val peer = try { listener.accept() } catch (_: java.net.SocketException) { break }
                    attempts.incrementAndGet()
                    thread(isDaemon = true) {
                        peer.use { socket ->
                            val input = socket.getInputStream().bufferedReader()
                            val output = socket.getOutputStream()
                            output.write("200 ready\r\n".toByteArray())
                            while (true) {
                                val cmd = input.readLine() ?: break
                                if (cmd == "BODY <hold>") {
                                    entered.countDown()
                                    sendFirst.await(3, TimeUnit.SECONDS)
                                }
                                output.write(("222 body\r\n=ybegin line=128 size=1 name=x\r\n" +
                                    "+\r\n=yend size=1\r\n.\r\n").toByteArray())
                            }
                        }
                    }
                }
            }
            val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                NewsArticles(listOf(endpoint)).use { first ->
                    NewsArticles(listOf(endpoint)).use { second ->
                        val held = workers.submit<Article> { first.fetch("hold") }
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        val overlap = workers.submit<Article> { second.fetch("next") }
                        sendFirst.countDown()
                        held.get(3, TimeUnit.SECONDS)
                        overlap.get(3, TimeUnit.SECONDS)
                        first.close()
                        second.fetch("last")
                        assertEquals(1, attempts.get())
                    }
                }
            } finally {
                sendFirst.countDown()
                workers.shutdownNow()
                listener.close()
                server.join(1000)
            }
        }
    }

    @Test fun slowlyTrickledGreetingCannotExtendTheArticleDeadline() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val peer = thread(isDaemon = true) {
                listener.accept().use { socket ->
                    try {
                        repeat(50) {
                            socket.getOutputStream().write(50)
                            Thread.sleep(30)
                        }
                    } catch (_: IOException) { }
                }
            }
            NewsArticles(listOf("nntp://127.0.0.1:" + listener.localPort), readBudgetMs = 300).use { reader ->
                val error = assertThrows(UsenetFailure::class.java) { reader.fetch("fixture") }
                assertTrue(error.message.orEmpty().contains("Timed out"))
            }
            peer.join(1000)
        }
    }

    @Test fun missingAtTheStartOfAPlayerRangeReturnsGoneWithoutBreakingHead() {
        val file = object : UsenetFile {
            override val name = "movie.mkv"
            override val size = 2048L
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                throw MissingUsenetArticle()
            }
        }
        UsenetHttpSession(file, java.io.Closeable {}).use { session ->
            session.start()
            fun status(method: String): Int {
                val connection = java.net.URL(session.url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = method
                connection.setRequestProperty("Range", "bytes=1024-")
                return try { connection.responseCode } finally { connection.disconnect() }
            }
            assertEquals(206, status("HEAD"))
            assertEquals(410, status("GET"))
        }
    }

    @Test fun closeInterruptsBlockedProviderGreeting() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val accepted = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            thread(isDaemon = true) {
                listener.accept().use { socket ->
                    accepted.countDown()
                    socket.getInputStream().read()
                    disconnected.countDown()
                }
            }
            val articles = NewsArticles(listOf("nntp://127.0.0.1:" + listener.localPort))
            val done = CountDownLatch(1)
            thread(isDaemon = true) {
                try { articles.fetch("one") } catch (_: UsenetFailure) { } finally { done.countDown() }
            }
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            articles.close()
            assertTrue("Cancellation must close a blocked socket", done.await(2, TimeUnit.SECONDS))
            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun definitiveMissingArticleDoesNotReconnectToSameProvider() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { listener ->
            val commands = AtomicInteger()
            thread(isDaemon = true) {
                listener.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream()
                    output.write("200 ready\r\n".toByteArray())
                    while (input.readLine() != null) {
                        val count = commands.incrementAndGet()
                        if (count == 1) output.write(
                            ("222 body\r\n=ybegin line=128 size=1 name=one\r\n" +
                                "+\r\n=yend size=1\r\n.\r\n").toByteArray())
                        else output.write("430 missing\r\n".toByteArray())
                    }
                }
            }
            NewsArticles(listOf("nntp://127.0.0.1:" + listener.localPort)).use { articles ->
                articles.fetch("present")
                assertThrows(MissingUsenetArticle::class.java) { articles.fetch("missing") }
            }
            assertEquals(2, commands.get())
        }
    }

    @Test fun fetchesGzipWithAddonHeadersAndPreservesSignedQuery() {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(xml.toByteArray()) }
        }.toByteArray()
        var authenticated = false
        var query = ""
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                authenticated = session.headers["authorization"] == "fixture"
                query = session.queryParameterString
                return newFixedLengthResponse(Response.Status.OK, "application/x-nzb", compressed.inputStream(), compressed.size.toLong())
            }
        }
        server.start()
        try {
            UsenetPreparation().use { owner ->
                val files = NzbDownload.fetch("http://127.0.0.1:" + server.listeningPort + "/a?token=a%2Bb",
                    mapOf("Authorization" to "fixture"), owner)
                assertEquals("episode.mp4", files.files.single().name)
                assertTrue(authenticated)
                assertEquals("token=a%2Bb", query)
            }
        } finally { server.stop() }
    }

    @Test fun rejectsGzipExpansionPastNzbLimit() {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { gzip ->
                gzip.write("<nzb>".toByteArray())
                val block = ByteArray(8192) { 32 }
                repeat((NzbLimits.HARD_DECODED_BYTES / block.size + 1).toInt()) { gzip.write(block) }
            }
        }.toByteArray()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response =
                newFixedLengthResponse(Response.Status.OK, "application/x-nzb", compressed.inputStream(), compressed.size.toLong())
        }
        server.start()
        try {
            UsenetPreparation().use { owner ->
                assertThrows(UsenetFailure::class.java) {
                    NzbDownload.fetch("http://127.0.0.1:" + server.listeningPort, emptyMap(), owner)
                }
            }
        } finally { server.stop() }
    }

    @Test fun stripsAddonCredentialsWhenRedirectCrossesOrigin() {
        var leaked = false
        val target = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                leaked = session.headers.containsKey("authorization") || session.headers.containsKey("x-api-key")
                return newFixedLengthResponse(xml)
            }
        }
        target.start()
        val redirect = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession) = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "").apply {
                addHeader("Location", "http://127.0.0.1:" + target.listeningPort + "/file")
            }
        }
        redirect.start()
        try {
            UsenetPreparation().use { NzbDownload.fetch("http://127.0.0.1:" + redirect.listeningPort,
                mapOf("Authorization" to "secret", "X-Api-Key" to "key"), it) }
            assertFalse(leaked)
        } finally { redirect.stop(); target.stop() }
    }

    @Test fun rejectsDocumentsThatAreNotNzbFiles() {
        assertThrows(UsenetFailure::class.java) { NzbManifest.parse(xml.replace("nzb", "html")) }
    }

    @Test fun preservesXmlEncodingAndRejectsEntitiesInUtf16() {
        assertEquals("episode.mp4", NzbManifest.parse(("\uFEFF" + xml).toByteArray(Charsets.UTF_16LE)).files.single().name)
        val unsafe = """<!DOCTYPE nzb [<!ENTITY x "bad">]>""" + xml
        assertThrows(UsenetFailure::class.java) { NzbManifest.parse(unsafe.toByteArray(Charsets.UTF_16LE)) }
    }

    @Test fun cancellationClosesPendingNzbHttpRequest() = runBlocking {
        val accepted = CountDownLatch(1)
        val ended = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            thread(isDaemon = true) {
                listener.accept().use { socket ->
                    accepted.countDown()
                    while (socket.getInputStream().read() >= 0) { }
                    ended.countDown()
                }
            }
            val dto = com.nuvio.tv.data.remote.dto.StreamDto(
                nzbUrl = "http://127.0.0.1:" + listener.localPort + "/pending",
                servers = listOf("nntp://127.0.0.1:1"))
            val source = dto.toDomain("fixture", null)
            val attempt = async(Dispatchers.IO) { UsenetPlayback.resolve(source) }
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            attempt.cancelAndJoin()
            assertTrue("Cancelled NZB fetch must release the socket", ended.await(2, TimeUnit.SECONDS))
        }
    }
}
