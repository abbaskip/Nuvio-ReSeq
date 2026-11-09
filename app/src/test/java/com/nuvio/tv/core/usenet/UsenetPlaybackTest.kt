package com.nuvio.tv.core.usenet

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamDto
import com.squareup.moshi.Moshi
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsenetPlaybackTest {
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/usenet/$name")).use { it.readBytes() }

    private fun article(data: ByteArray, start: Int, total: Int): ByteArray {
        val output = ByteArrayOutputStream()
        fun line(value: String) = output.write((value + "\r\n").toByteArray(Charsets.ISO_8859_1))
        line("=ybegin part=1 line=128 size=$total name=fixture")
        line("=ypart begin=" + (start + 1) + " end=" + (start + data.size))
        for (chunk in data.asList().chunked(64)) {
            val encoded = buildString {
                for (byte in chunk) {
                    val value = (byte.toInt() + 42) and 255
                    if (value in listOf(0, 10, 13, 61)) {
                        append('=')
                        append(((value + 64) and 255).toChar())
                    } else append(value.toChar())
                }
            }
            line(if (encoded.startsWith('.')) ".$encoded" else encoded)
        }
        val checksum = CRC32().apply { update(data) }.value.toString(16)
        line("=yend size=" + data.size + " pcrc32=" + checksum)
        line(".")
        return output.toByteArray()
    }

    private class NewsFixture(private val articles: Map<String, ByteArray>, private val latencyMs: Long = 0) : Closeable {
        private val responses = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val endpoint = "nntp://fixture:p%3Ass+word@127.0.0.1:" + listener.localPort + "/4"
        val requests = AtomicInteger()
        init {
            thread(isDaemon = true) {
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: SocketException) { break }
                    thread(isDaemon = true) {
                        socket.use {
                            val input = socket.getInputStream().bufferedReader()
                            val out = socket.getOutputStream()
                            fun line(s: String) = out.write((s + "\r\n").toByteArray())
                            line("200 ready")
                            var loggedIn = false
                            while (true) {
                                val command = input.readLine() ?: break
                                when {
                                    command == "AUTHINFO USER fixture" -> line("381 password required")
                                    command == "AUTHINFO PASS p:ss+word" -> { loggedIn = true; line("281 accepted") }
                                    loggedIn && command.startsWith("BODY <") -> {
                                        requests.incrementAndGet()
                                        val body = articles[command.removePrefix("BODY <").removeSuffix(">")]
                                        val respond = Runnable {
                                            if (body == null) line("430 unavailable") else {
                                                line("222 article")
                                                out.write(body)
                                            }
                                        }
                                        if (latencyMs == 0L) respond.run() else responses.schedule(respond, latencyMs,
                                            java.util.concurrent.TimeUnit.MILLISECONDS)
                                    }
                                    else -> line("481 denied")
                                }
                            }
                        }
                    }
                }
            }
        }
        override fun close() { listener.close(); responses.shutdownNow() }
    }

    @Test fun delayedProviderReturnsIdenticalSplitAndSequentialBytesAcrossSeeks() {
        val count = 48
        val parts = List(count) { i -> ByteArray(2048) { j -> (i + j).toByte() } }
        val bytes = parts.reduce { a, b -> a + b }
        val ids = List(count) { "part-$it@fixture" }
        for (split in listOf(true, false)) {
            val bodies = ids.indices.associate { i ->
                ids[i] to article(parts[i], if (split) 0 else i * 2048, if (split) 2048 else bytes.size)
            }
            NewsFixture(bodies, 20).use { provider ->
                NewsArticles(listOf(provider.endpoint)).use { reader ->
                    val files = if (split) ids.mapIndexed { i, id ->
                        NzbFile("movie.mkv." + (i + 1).toString().padStart(3, '0'), listOf(id), 2048)
                    } else listOf(NzbFile("movie.mkv", ids, bytes.size.toLong()))
                    val start = System.nanoTime()
                    val file = UsenetSelection.select(files, ArticleCache { id, next -> reader.fetch(id, next) }, null, emptyList())
                    val opened = System.nanoTime()
                    assertArrayEquals(bytes, file.bytesAt(0, bytes.size))
                    val read = System.nanoTime()
                    assertArrayEquals(bytes.copyOfRange(3071, 6123), file.bytesAt(3071, 3052))
                    assertArrayEquals(bytes.takeLast(120).toByteArray(), file.bytesAt(file.size - 120, 120))
                    assertEquals(count, provider.requests.get())
                    println("split=$split openMs=" + (opened-start)/1_000_000 + " readMs=" + (read-opened)/1_000_000 +
                        " bodies=" + provider.requests.get())
                }
            }
        }
    }

    @Test fun seekAndMissingQueuedReplyCannotChangeTheBytesOfTheNextRequest() {
        val ids = listOf("first", "second", "third", "after-seek")
        val bodies = ids.mapIndexed { i, id -> id to article(byteArrayOf(i.toByte()), 0, 1) }.toMap()
        NewsFixture(bodies).use { provider ->
            val endpoint = provider.endpoint.substringBeforeLast('/') + "/1"
            NewsArticles(listOf(endpoint)).use { reader ->
                assertArrayEquals(byteArrayOf(0), reader.fetch("first", listOf("second", "third")).bytes)
                assertArrayEquals(byteArrayOf(3), reader.fetch("after-seek", listOf("missing", "second")).bytes)
                assertThrows(MissingUsenetArticle::class.java) { reader.fetch("missing") }
                assertArrayEquals(byteArrayOf(1), reader.fetch("second").bytes)
            }
        }
    }

    @Test fun pipelineRefusalRetriesTheSameArticleAndSubsequentReadsSerially() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val done = java.util.concurrent.CompletableFuture<Unit>()
            thread(isDaemon = true) {
                try {
                    repeat(2) { attempt ->
                        server.accept().use { socket ->
                            socket.soTimeout = 4000
                            val input = socket.getInputStream().bufferedReader()
                            val output = socket.getOutputStream()
                            output.write("200 ready\r\n".toByteArray())
                            assertEquals("BODY <one>", input.readLine())
                            if (attempt == 0) {
                                assertEquals("BODY <two>", input.readLine())
                                output.write("400 busy\r\n".toByteArray())
                            } else {
                                output.write("222 article\r\n".toByteArray())
                                output.write(article(byteArrayOf(1), 0, 1))
                                assertEquals("BODY <three>", input.readLine())
                                output.write("222 article\r\n".toByteArray())
                                output.write(article(byteArrayOf(3), 0, 1))
                            }
                        }
                    }
                    done.complete(Unit)
                } catch (e: Throwable) { done.completeExceptionally(e) }
            }
            NewsArticles(listOf("nntp://127.0.0.1:" + server.localPort + "/1")).use { reader ->
                assertArrayEquals(byteArrayOf(1), reader.fetch("one", listOf("two")).bytes)
                assertArrayEquals(byteArrayOf(3), reader.fetch("three", listOf("four")).bytes)
            }
            done.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun exercise(files: List<Pair<String, ByteArray>>, password: String? = null) {
        val expected = resource("test-pattern.mp4")
        val bodies = mutableMapOf<String, ByteArray>()
        val xml = buildString {
            append("<!DOCTYPE nzb PUBLIC '-//newzBin//DTD NZB 1.1//EN' 'http://invalid.invalid/nzb.dtd'><nzb>")
            if (password != null) append("<head><meta type='password'>$password</meta></head>")
            files.forEachIndexed { i, (name, bytes) ->
                append("<file subject='[fixture] &quot;" + name + "&quot; yEnc'><segments>")
                var pos = 0
                var ordinal = 1
                val segments = mutableListOf<String>()
                while (pos < bytes.size) {
                    val size = minOf(bytes.size - pos, if (ordinal % 2 == 0) 250 else 380)
                    val id = "file$i-part$ordinal@fixture"
                    bodies[id] = article(bytes.copyOfRange(pos, pos + size), pos, bytes.size)
                    segments += "<segment bytes='" + bodies.getValue(id).size + "' number='$ordinal'>$id</segment>"
                    pos += size
                    ordinal++
                }
                append(segments.reversed().joinToString(""))
                append("</segments></file>")
            }
            append("</nzb>")
        }.toByteArray()
        val http = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response =
                newFixedLengthResponse(Response.Status.OK, "application/x-nzb", xml.inputStream(), xml.size.toLong())
        }
        http.start()
        try {
            NewsFixture(emptyMap()).use { missing ->
                NewsFixture(bodies).use { available ->
                    val json = """{"nzbUrl":"http://127.0.0.1:PORT/fixture.nzb","servers":["FIRST","SECOND"],"fileMustInclude":["/test-pattern\\.mp4$/i"]}"""
                        .replace("PORT", http.listeningPort.toString()).replace("FIRST", missing.endpoint).replace("SECOND", available.endpoint)
                    val dto = Moshi.Builder().build().adapter(StreamDto::class.java).fromJson(json)!!
                    val source = dto.toDomain("fixture", null)
                    assertFalse(source.toString().contains("p%3A"))
                    assertFalse(source.stableKey().contains("fixture.nzb"))
                    val stream = runBlocking { UsenetPlayback.resolve(source) }
                    val url = URL(stream.getStreamUrl())
                    try {
                        val head = url.openConnection() as HttpURLConnection
                        head.requestMethod = "HEAD"
                        assertEquals(200, head.responseCode)
                        assertEquals(expected.size.toLong(), head.contentLengthLong)
                        head.disconnect()
                        fun slice(range: String, start: Int, end: Int) {
                            val connection = url.openConnection() as HttpURLConnection
                            connection.setRequestProperty("Range", range)
                            try {
                                assertEquals(206, connection.responseCode)
                                assertEquals("bytes $start-$end/" + expected.size, connection.getHeaderField("Content-Range"))
                                assertArrayEquals(expected.copyOfRange(start, end + 1), connection.inputStream.use { it.readBytes() })
                            } finally { connection.disconnect() }
                        }
                        slice("bytes=-31", expected.size - 31, expected.lastIndex)
                        slice("bytes=3350-4800", 3350, 4800)
                        slice("bytes=127-1024", 127, 1024)
                        assertArrayEquals(expected, url.openStream().use { it.readBytes() })
                        val invalid = url.openConnection() as HttpURLConnection
                        invalid.setRequestProperty("Range", "bytes=999999-")
                        assertEquals(416, invalid.responseCode)
                        invalid.disconnect()
                        val wrong = URL(url, "../incorrect/video.mp4").openConnection() as HttpURLConnection
                        assertEquals(404, wrong.responseCode)
                        wrong.disconnect()
                        assertTrue(missing.requests.get() > 0 && available.requests.get() > 1)
                    } finally { UsenetPlayback.release(url.toString()) }
                }
            }
        } finally { http.stop() }
    }

    @Test fun playsDirectVideoThroughAddonAndHttpRanges() =
        exercise(listOf("test-pattern.mp4" to resource("test-pattern.mp4")))

    @Test fun seeksAcrossRealWinrar5VolumesAndArticles() =
        exercise(listOf("pattern.part2.rar" to resource("pattern.part2.rar"), "pattern.part1.rar" to resource("pattern.part1.rar")))

    @Test fun seeksAcrossLegacyRar4Volumes() =
        exercise(listOf("old.rar" to resource("old.rar"), "old.r00" to resource("old.r00")))

    @Test fun playsSplitSevenZipThroughAddonAndHttpRanges() {
        val bytes = resource("stored.7z")
        exercise(listOf("release.7z.002" to bytes.copyOfRange(4096, bytes.size),
            "release.7z.001" to bytes.copyOfRange(0, 4096)))
    }

    @Test fun seeksAcrossEncryptedHeadersPayloadAndVolumes() =
        exercise((1..3).reversed().map { "locked.part$it.rar" to resource("locked.part$it.rar") }, "FixtureOnly5")

    @Test fun playsShuffledRar5VolumesWithUnrelatedSubjects() =
        exercise(listOf("09ff34" to resource("pattern.part2.rar"), "837124" to resource("pattern.part1.rar")))

    @Test fun rejectsCorruptArticle() {
        val bytes = article(byteArrayOf(1, 2, 3), 0, 3).toString(Charsets.ISO_8859_1)
        val bad = bytes.replace(Regex("pcrc32=[a-f0-9]+"), "pcrc32=0")
        assertThrows(UsenetFailure::class.java) { decodeArticle(bad.byteInputStream(Charsets.ISO_8859_1).buffered()) }
    }

    @Test fun handlesEscapesAndNntpDotStuffing() {
        val input = ByteArray(256) { (it + 4).toByte() }
        assertArrayEquals(input, decodeArticle(article(input, 0, 256).inputStream().buffered()).bytes)
    }

    @Test fun rejectsMissingArticleAndUnsafeXml() {
        val xml = "<nzb><file subject='clip.mp4'><segments><segment number='1'>one</segment><segment number='3'>three</segment></segments></file></nzb>"
        assertThrows(UsenetFailure::class.java) { NzbManifest.parse(xml) }
        assertThrows(UsenetFailure::class.java) { NzbManifest.parse("<!DOCTYPE nzb [<!ENTITY x 'test'>]><nzb/>") }
        val files = listOf(NzbFile("clip.mp4", listOf("1", "3"), 10))
        val gap = ArticleCache { id, _ -> if (id == "1") Article(0, 10, ByteArray(3)) else Article(7, 10, ByteArray(3)) }
        val selected = UsenetSelection.select(files, gap, null, emptyList())
        assertThrows(UsenetFailure::class.java) { selected.read(5, ByteArray(1), 0, 1) }
    }

    @Test fun incompleteEarlierFileDoesNotShiftEpisodeIndex() {
        val files = NzbManifest.parse("""<nzb><file subject="S01E01.mp4"><segments><segment number="2">tail</segment></segments></file>
            <file subject="S01E02.mp4"><segments><segment number="1">complete</segment></segments></file></nzb>""").files
        val cache = ArticleCache { id, _ ->
            assertEquals("complete", id)
            Article(0, 1, byteArrayOf(1))
        }
        assertEquals("S01E02.mp4", UsenetSelection.select(files, cache, 1, emptyList()).name)
        assertThrows(UsenetFailure::class.java) { UsenetSelection.select(files, cache, 0, emptyList()) }
    }

    @Test fun fileIndexAndFilterDoNotSilentlyChooseAnotherEpisode() {
        val files = listOf(NzbFile("notes.nfo", listOf("0"), 1), NzbFile("S01E02.mp4", listOf("1"), 200),
            NzbFile("S01E03.mp4", listOf("2"), 300))
        val cache = ArticleCache { _, _ -> Article(0, 1, byteArrayOf(1)) }
        assertEquals("S01E02.mp4", UsenetSelection.select(files, cache, 1, listOf("/s01e02/i")).name)
        assertThrows(UsenetFailure::class.java) { UsenetSelection.select(files, cache, 1, listOf("s01e03")) }
        assertThrows(UsenetFailure::class.java) { UsenetSelection.select(files, cache, 42, emptyList()) }
        assertEquals("S01E03.mp4", UsenetSelection.select(files, cache, null, emptyList()).name)
    }

    @Test fun recoversFilenameFromYencWhenNzbSubjectsAreObfuscated() {
        val file = NzbFile("a2c9087", listOf("video"), 77)
        val cache = ArticleCache { _, _ -> Article(0, 3, byteArrayOf(1, 2, 3), "restored.mkv") }
        assertEquals("restored.mkv", UsenetSelection.select(listOf(file), cache, null, listOf("restored")).name)
    }

    @Test fun acceptsSingleStringFilterAndPrefersExistingVideoUrl() {
        val adapter = Moshi.Builder().build().adapter(StreamDto::class.java)
        val nzb = adapter.fromJson("""{"nzbUrl":"https://index.invalid/nzb","fileMustInclude":"/episode/i"}""")!!
        assertEquals(listOf("/episode/i"), nzb.toDomain("fixture", null).usenet?.filters)
        val direct = nzb.copy(url = "https://media.invalid/episode.mp4").toDomain("fixture", null)
        assertNull(direct.usenet)
        assertEquals("https://media.invalid/episode.mp4", direct.getStreamUrl())
    }

    @Test fun largeFileSeekUses64BitOffsetsAndReloadsEvictedParts() {
        val partSize = 512 * 1024
        val count = 16_384
        val total = count * partSize.toLong()
        val reads = mutableListOf<Int>()
        val cache = ArticleCache { id, _ ->
            val index = id.toInt()
            reads += index
            Article(index * partSize.toLong(), total, ByteArray(partSize) { (index % 251).toByte() })
        }
        val file = PostedFile(NzbFile("large.mkv", (0 until count).map { it.toString() }, total), cache)
        assertEquals(total, file.size)
        val first = 13_001
        for (index in first until first + 24) {
            val position = index * partSize.toLong() + partSize - 1
            assertArrayEquals(byteArrayOf((index % 251).toByte(), ((index + 1) % 251).toByte()), file.bytesAt(position, 2))
        }
        assertEquals(1, reads.count { it == first })
        assertEquals((first % 251).toByte(), file.bytesAt(first * partSize.toLong(), 1).single())
        assertEquals(2, reads.count { it == first })
        assertTrue("A seek should fetch only the surrounding articles", reads.size <= 30)
    }

    @Test fun rejectsCompressedAndEncryptedRar() {
        for (name in listOf("compressed.rar", "encrypted.rar")) {
            val bytes = resource(name)
            val cache = ArticleCache { _, _ -> Article(0, bytes.size.toLong(), bytes) }
            assertThrows(UsenetFailure::class.java) {
                UsenetSelection.select(listOf(NzbFile(name, listOf("a"), bytes.size.toLong())), cache, null, emptyList())
            }
        }
    }
}
