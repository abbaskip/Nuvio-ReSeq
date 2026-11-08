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

    private class NewsFixture(private val articles: Map<String, ByteArray>) : Closeable {
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
                                        if (body == null) line("430 unavailable") else {
                                            line("222 article")
                                            out.write(body)
                                        }
                                    }
                                    else -> line("481 denied")
                                }
                            }
                        }
                    }
                }
            }
        }
        override fun close() = listener.close()
    }

    private fun exercise(files: List<Pair<String, ByteArray>>) {
        val expected = resource("test-pattern.mp4")
        val bodies = mutableMapOf<String, ByteArray>()
        val xml = buildString {
            append("<!DOCTYPE nzb PUBLIC '-//newzBin//DTD NZB 1.1//EN' 'http://invalid.invalid/nzb.dtd'><nzb>")
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
            <file subject="S01E02.mp4"><segments><segment number="1">complete</segment></segments></file></nzb>""")
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
