package com.nuvio.tv.core.usenet

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class LargeNzbTest {
    private val originalProfile = UsenetMemoryProfile.current
    @Before fun normalTier() { UsenetMemoryProfile.current = UsenetMemoryProfile.NORMAL }
    @After fun restoreTier() { UsenetMemoryProfile.current = originalProfile }
    // Generated a piece at a time: the fixture itself never retains the XML document.
    private fun fixture(files: Int, segments: Int, padding: Int = 0, charset: Charset = Charsets.UTF_8): InputStream {
        val parts = sequence {
            yield("${if (charset == Charsets.UTF_16LE) "\uFEFF" else ""}<?xml version=\"1.0\" encoding=\"${charset.name()}\"?><nzb><head><meta type=\"password\">fixture</meta></head>")
            repeat(files) { f ->
                val name = if (f == files - 1 && files > 1) "set.par2" else "set.part${f + 1}.rar"
                yield("<file subject=\"&quot;$name&quot;\"><segments>")
                repeat(segments) { i ->
                    yield("<segment bytes=\"700000\" number=\"${i + 1}\">${id(f, i)}</segment>${" ".repeat(padding)}")
                }
                yield("</segments></file>")
            }
            yield("</nzb>")
        }.iterator()
        return object : InputStream() {
            var current = byteArrayOf().inputStream()
            override fun read(): Int { val one = ByteArray(1); return if (read(one) < 0) -1 else one[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (true) {
                    val n = current.read(b, off, len)
                    if (n >= 0) return n
                    if (!parts.hasNext()) return -1
                    current = parts.next().toByteArray(charset).inputStream()
                }
            }
        }
    }
    private fun id(file: Int, segment: Int) = "article-$file-$segment@fixture.invalid"

    private fun download(files: Int, segments: Int, padding: Int = 0, gzip: Boolean = false,
                         charset: Charset = Charsets.UTF_8): NzbDocument {
        val temp = if (gzip) java.io.File.createTempFile("large-nzb", ".gz").also { file ->
            file.outputStream().use { out -> GZIPOutputStream(out).use { zip -> fixture(files, segments, padding, charset).use { it.copyTo(zip) } } }
        } else null
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = newChunkedResponse(Response.Status.OK,
                "application/x-nzb", temp?.inputStream() ?: fixture(files, segments, padding, charset)).apply {
                    if (gzip && charset == Charsets.UTF_16LE) addHeader("Content-Encoding", "gzip")
                }
        }
        server.start()
        try {
            return UsenetPreparation().use { NzbDownload.fetch("http://127.0.0.1:${server.listeningPort}/fixture", emptyMap(), it) }
        } finally { server.stop(); temp?.delete() }
    }

    @Test fun largeSingleFileAndGzipKeepEveryArticleForRandomSeeks() {
        for (gzip in listOf(false, true)) {
            val doc = download(1, 160000, 32, gzip)
            val nzb = doc.files.single()
            assertEquals(112000000000L, nzb.postedBytes)
            assertEquals(160000, nzb.articles.size)
            val requested = mutableListOf<String>()
            val cache = ArticleCache(profile = UsenetMemoryProfile(4096, 0, 0)) { article, _ ->
                requested += article
                val index = article.substringAfter("article-0-").substringBefore('@').toInt()
                Article(index * 32L, 160000 * 32L, ByteArray(32) { (index + it).toByte() })
            }
            val file = PostedFile(nzb, cache)
            val random = java.util.Random(47)
            repeat(100) {
                val index = 1 + random.nextInt(159998)
                assertArrayEquals(ByteArray(12) { (index + 7 + it).toByte() }, file.bytesAt(index * 32L + 7, 12))
                assertTrue(requested.contains(id(0, index)))
            }
            assertArrayEquals(ByteArray(8) { (159999 + 24 + it).toByte() }, file.bytesAt(file.size - 8, 8))
        }
    }

    @Test fun halfMillionSegmentsWithOver100MbXmlAndUtf16Gzip() {
        for ((padding, charset) in listOf(140 to Charsets.UTF_8, 0 to Charsets.UTF_16LE)) {
            val doc = download(10, 50000, padding, true, charset)
            assertEquals("fixture", doc.password)
            assertEquals(10, doc.files.size)
            doc.files.forEachIndexed { f, file ->
                assertEquals(35000000000L, file.postedBytes)
                assertEquals(50000, file.articles.size)
                assertEquals(id(f, 0), file.articles.first())
                assertEquals(id(f, 49999), file.articles.last())
            }
            assertEquals("set.par2", doc.files.last().name)
            assertTrue(doc.retainedBytes < 24L * 1024 * 1024)
            if (padding > 0) assertTrue(doc.decodedBytes > 100L * 1024 * 1024)
        }
    }

    @Test fun boundsAllocationAndRetentionIndependentlyOfXmlPadding() {
        val input = java.io.File.createTempFile("nzb-memory", ".xml")
        try {
            var previous = 0L
            for (padding in listOf(0, 140)) {
                input.outputStream().buffered().use { out -> fixture(10, 50000, padding).use { it.copyTo(out) } }
                // Run without full-suite mocking agents, whose JDK instrumentation changes
                // allocation counts. -Xmx96m also verifies a hard live-heap ceiling.
                val classpath = listOf(NzbAllocationProbe::class.java, NzbManifest::class.java, Unit::class.java)
                    .map { java.io.File(it.protectionDomain.codeSource.location.toURI()).path }.distinct()
                    .joinToString(java.io.File.pathSeparator)
                val executable = java.io.File(System.getProperty("java.home"), "bin/" +
                    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
                val process = ProcessBuilder(executable.path, "-Xmx96m", "-cp", classpath,
                    NzbAllocationProbe::class.java.name, input.absolutePath).redirectErrorStream(true).start()
                try {
                    assertTrue("Allocation probe timed out", process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
                    val output = process.inputStream.bufferedReader().readText()
                    assertEquals(output, 0, process.exitValue())
                    println(output.trim())
                    val retained = Regex("retained=(\\d+)").find(output)!!.groupValues[1].toLong()
                    if (previous != 0L) assertEquals(previous, retained)
                    previous = retained
                } finally { process.destroyForcibly() }
            }
        } finally { input.delete() }
    }

    @Test fun validationAndEncodingStayCompatible() {
        fun segment(n: Int, id: String = "id$n") = "<segment number=\"$n\" bytes=\"10\">$id</segment>"
        fun file(body: String, name: String = "film.mkv") = "<file subject=\"$name\"><segments>$body</segments></file>"
        val valid = "<nzb>${file(segment(3) + segment(1) + segment(2))}</nzb>"
        val parsed = NzbManifest.parse(valid)
        assertEquals(listOf("id1", "id2", "id3"), parsed.files.single().articles)
        assertEquals(30L, parsed.files.single().postedBytes)
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE, Charsets.ISO_8859_1)) {
            val xml = "<?xml version=\"1.0\" encoding=\"${charset.name()}\"?><nzb><head><meta type=\"password\">páss</meta></head>${file(segment(1), "café.mkv")}</nzb>"
            val bom = if (charset == Charsets.ISO_8859_1) "" else "\uFEFF"
            val doc = NzbManifest.parse((bom + xml).toByteArray(charset))
            assertEquals("café.mkv", doc.files.single().name)
            assertEquals("páss", doc.password)
            assertEquals("café.mkv", NzbManifest.parse(xml).files.single().name)
        }
        val rejected = listOf(
            "<!DOCTYPE nzb [<!ENTITY x SYSTEM 'file:///must-not-read'>]>$valid",
            "<nzb>${"<x>".repeat(32)}${"</x>".repeat(32)}</nzb>",
            "<nzb>${file("").repeat(4097)}</nzb>",
            "<nzb>${file(segment(1, "bad id"))}</nzb>",
            "<nzb>${file(segment(1) + segment(1))}</nzb>",
            "<nzb>${file(segment(Int.MAX_VALUE) + segment(Int.MAX_VALUE))}</nzb>",
            "<nzb>${file(segment(2))}</nzb>",
            "<nzb><head><meta type=\"password\">${"x".repeat(513)}</meta></head>${file(segment(1))}</nzb>"
        )
        rejected.forEach { xml ->
            val failure = assertThrows(UsenetFailure::class.java) { NzbManifest.parse(xml) }
            assertFalse(failure is NzbTooLarge)
        }
        val incomplete = NzbManifest.parse("<nzb>${file(segment(Int.MAX_VALUE))}${file(segment(1))}</nzb>")
        assertTrue(incomplete.files[0].articles.isEmpty())
        assertEquals(listOf("id1"), incomplete.files[1].articles)
    }

    @Test fun deviceAndHardLimitsFailEarlyWithDistinctMessage() {
        val small = NzbLimits(1024 * 1024, 100, 1024 * 1024)
        val failure = assertThrows(NzbTooLarge::class.java) { fixture(1, 101).use { NzbManifest.parse(it, small) } }
        assertEquals("NZB is too large for this device.", failure.message)
        assertThrows(NzbTooLarge::class.java) { fixture(1, 100).use { NzbManifest.parse(it, small.copy(retainedBytes = 1024)) } }
        assertThrows(NzbTooLarge::class.java) { fixture(1, 100, 100).use { NzbManifest.parse(it, small.copy(decodedBytes = 1024)) } }
        assertThrows(NzbTooLarge::class.java) { fixture(1, 250001).use { NzbManifest.parse(it, NzbLimits.LOW) } }
        assertThrows(NzbTooLarge::class.java) { fixture(1, 1000001).use { NzbManifest.parse(it, NzbLimits.NORMAL) } }
        // A valid prefix followed by whitespace proves rejection is the decoded byte cap,
        // not an XML error, and the source is not read to EOF.
        var bytes = 0L
        val start = "<nzb>".toByteArray()
        val endless = object : InputStream() {
            override fun read(): Int = if (bytes < start.size) start[bytes++.toInt()].toInt() else { bytes++; 32 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { repeat(len) { b[off + it] = read().toByte() }; return len }
        }
        assertThrows(NzbTooLarge::class.java) { NzbManifest.parse(endless, NzbLimits.NORMAL) }
        assertEquals(NzbLimits.HARD_DECODED_BYTES + 1, bytes)
    }

    @Test fun cacheCountsRetainedCapacityAndPressureDoesNotLosePlaybackIds() {
        val doc = fixture(1, 100).use { NzbManifest.parse(it) }
        val cache = NzbMemoryCache(limit = { doc.retainedBytes + 512 })
        cache.put("a", emptyMap(), doc)
        cache.put("b", emptyMap(), doc)
        assertNull(cache.get("a", emptyMap()))
        assertSame(doc, cache.get("b", emptyMap()))
        val generation = cache.generation()
        cache.clear()
        cache.put("c", emptyMap(), doc, generation)
        assertEquals(0L, cache.retainedBytes())
        assertEquals(id(0, 99), doc.files.single().articles[99])
    }

    @Test fun guardsXmlTokensAndNeverResolvesExternalDtd() {
        val valid = "<nzb><file subject=\"f.mkv\"><segments><segment number=\"1\">id</segment></segments></file></nzb>"
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response { requests.incrementAndGet(); return newFixedLengthResponse("") }
        }
        server.start()
        try {
            val xml = "<!DOCTYPE nzb SYSTEM \"http://127.0.0.1:${server.listeningPort}/external\">$valid"
            assertEquals(listOf("id"), NzbManifest.parse(xml).files.single().articles)
            assertEquals(0, requests.get())
        } finally { server.stop() }
        val declaration = "<!--${" ".repeat(8200)}<!ENTITY hidden 'x'>-->$valid"
        val split = object : java.io.FilterInputStream(declaration.byteInputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
        }
        assertEquals("Unsupported NZB document.", assertThrows(UsenetFailure::class.java) { NzbManifest.parse(split) }.message)
        for (token in listOf("<!-->${"x".repeat(65536)}-->", "<?test >${"x".repeat(65536)}?>",
                "<![CDATA[>${"x".repeat(65536)}]]>", "<x attr=\"${"x".repeat(65536)}\"/>")) {
            assertThrows(token.take(20), NzbTooLarge::class.java) { NzbManifest.parse("<nzb>$token</nzb>") }
        }
        for (inner in listOf("<!--]> ${"x".repeat(65536)}-->", "<?test ]> ${"x".repeat(65536)}?>")) {
            assertThrows(NzbTooLarge::class.java) { NzbManifest.parse("<!DOCTYPE nzb [$inner]>$valid") }
        }
    }

    @Test fun localNntpLatency() = measureLocalNntpLatency(10000)
    @Test fun largeLocalNntpLatency() = measureLocalNntpLatency(160000)

    private fun measureLocalNntpLatency(segments: Int) {
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            val server = kotlin.concurrent.thread(isDaemon = true) {
                try { listener.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream()
                    output.write("200 fixture ready\r\n".toByteArray()); output.flush()
                    while (true) {
                        val command = input.readLine() ?: break
                        val article = command.substringAfter('<').substringBefore('>')
                        requested += article
                        val index = article.substringAfter("article-0-").substringBefore('@').toInt()
                        val body = "222 body\r\n=ybegin part=${index + 1} total=$segments line=128 size=${segments * 32} name=fixture.mkv\r\n" +
                            "=ypart begin=${index * 32 + 1} end=${(index + 1) * 32}\r\n" +
                            "+".repeat(32) + "\r\n=yend size=32 part=${index + 1}\r\n.\r\n"
                        output.write(body.toByteArray()); output.flush()
                    }
                } } catch (_: java.io.IOException) { }
            }
            val start = System.nanoTime()
            val doc = download(1, segments)
            NewsArticles(listOf("nntp://127.0.0.1:${listener.localPort}")).use { news ->
                val cache = ArticleCache(profile = UsenetMemoryProfile(4096, 0, 0), fetch = news::fetch)
                val file = PostedFile(doc.files.single(), cache)
                assertArrayEquals(ByteArray(8) { 1 }, file.bytesAt(0, 8))
                val first = (System.nanoTime() - start) / 1_000_000.0
                val random = java.util.Random(8)
                val latencies = (0 until 50).map {
                    val index = 1 + random.nextInt(segments - 2)
                    val seekStart = System.nanoTime()
                    assertArrayEquals(ByteArray(8) { 1 }, file.bytesAt(index * 32L + 5, 8))
                    assertTrue(requested.contains(id(0, index)))
                    (System.nanoTime() - seekStart) / 1_000_000.0
                }.sorted()
                println("NZB_LOCAL_NNTP segments=$segments first_byte_ms=$first seek_median_ms=${latencies[25]} seek_p95_ms=${latencies[47]}")
            }
            server.join(2000)
        }
    }
}

/** Standalone JVM probe: no Android process, provider traffic, or test mocking agents. */
object NzbAllocationProbe {
    @JvmStatic fun main(args: Array<String>) {
        NzbManifest.parse("<nzb><file subject=\"warm.mkv\"><segment number=\"1\">warm</segment></file></nzb>")
        // JVM-only APIs are absent from the Android compile classpath.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val type = Class.forName("com.sun.management.ThreadMXBean")
        type.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val method = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        fun allocated() = method.invoke(bean, Thread.currentThread().id) as Long
        val before = allocated()
        val doc = java.io.File(args.single()).inputStream().buffered().use { NzbManifest.parse(it, NzbLimits.NORMAL) }
        val allocation = allocated() - before
        check(doc.files.sumOf { it.articles.size } == 500000)
        check(doc.retainedBytes < 24L * 1024 * 1024) { "Retained=${doc.retainedBytes}" }
        check(allocation < 256L * 1024 * 1024) { "Cumulative allocated=$allocation" }
        println("NZB_MEMORY decoded=${doc.decodedBytes} retained=${doc.retainedBytes} allocated=$allocation max_heap=${Runtime.getRuntime().maxMemory()}")
    }
}
