// Synthetic RAR helper inspired by haaihond/NuvioTV (GPL-3.0),
// native/usenet/internal/engine/rar_seek_test.go, commit bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6.
package com.nuvio.tv.core.usenet

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class UsenetPerformanceTest {
    private fun vint(value: Long): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        do { out.write(((n and 127) or if (n >= 128) 128 else 0).toInt()); n = n ushr 7 } while (n != 0L)
        return out.toByteArray()
    }
    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (it * 8)).toByte() }
    private fun header5(body: ByteArray): ByteArray {
        val header = vint(body.size.toLong()) + body
        return le(CRC32().apply { update(header) }.value, 4) + header
    }
    private fun header4(type: Int, flags: Int, body: ByteArray): ByteArray {
        val header = byteArrayOf(type.toByte()) + le(flags.toLong(), 2) + le((7 + body.size).toLong(), 2) + body
        return le(CRC32().apply { update(header) }.value, 2) + header
    }
    private data class Archive(val volumes: List<ByteArray>, val source: ByteArray)

    // Synthetic stored-volume generator inspired by upstream rar_seek_test.go's regularRARSet;
    // GPL-3.0, haaihond/NuvioTV bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6.
    private fun archive(version: Int, count: Int, name: String = "Show.S01E02.mkv", changed: Int = -1): Archive {
        fun main(i: Int) = if (version == 4) header4(0x73, 1, ByteArray(6))
            else header5(vint(1) + vint(0) + vint(3) + vint(i.toLong()))
        fun file(i: Int, packed: Int, total: Long): ByteArray {
            val filename = name.toByteArray()
            return if (version == 4) {
                header4(0x74, 0x8000 or (if (i > 0) 1 else 0) or (if (i < count - 1) 2 else 0),
                    le(packed.toLong(), 4) + le(total, 4) + byteArrayOf(3) + ByteArray(8) +
                        byteArrayOf(20, 0x30) + le(filename.size.toLong(), 2) + ByteArray(4) + filename +
                        ByteArray(if (i == changed) 4 else 0))
            } else {
                val extra = if (i == changed) vint(4) + vint(99) + ByteArray(3) else byteArrayOf()
                header5(vint(2) + vint((2 or (if (i > 0) 8 else 0) or (if (i < count - 1) 16 else 0) or
                    (if (extra.isNotEmpty()) 1 else 0)).toLong()) +
                    (if (extra.isNotEmpty()) vint(extra.size.toLong()) else byteArrayOf()) + vint(packed.toLong()) +
                    vint(0) + vint(total) + vint(0) + vint(0) + vint(0) + vint(filename.size.toLong()) + filename + extra)
            }
        }
        val signature = StoredRar.signature + if (version == 4) byteArrayOf(0) else byteArrayOf(1, 0)
        val end = if (version == 4) header4(0x7b, 0, byteArrayOf()) else header5(vint(5) + vint(0) + vint(0))
        // Fix the payload width before deriving regular volume lengths. Changed headers grow their
        // volume without changing payload lengths, exercising deferred repair rather than startup fallback.
        val lengths = IntArray(count) { i -> if (i == count - 1) 311 else
            4096 - signature.size - main(i).size - file(if (i == changed) 0 else i, 4000, count * 4000L).size - end.size }
        val total = lengths.sum()
        val source = ByteArray(total) { ((it * 31 + it / 37) and 255).toByte() }
        var offset = 0
        val volumes = lengths.mapIndexed { i, n ->
            (signature + main(i) + file(i, n, total.toLong()) + source.copyOfRange(offset, offset + n) + end).also { offset += n }
        }
        return Archive(volumes, source)
    }
    private class Harness(input: List<Pair<String, ByteArray>>, profile: UsenetMemoryProfile = UsenetMemoryProfile.LOW,
                          beforeFetch: (String) -> Unit = {}) {
        val fetched = ConcurrentHashMap<String, AtomicInteger>()
        val articles = mutableMapOf<String, Article>()
        val files = input.mapIndexed { i, (name, bytes) ->
            val ids = bytes.asList().chunked(397).mapIndexed { j, part ->
                "$i-$j".also { articles[it] = Article(j * 397L, bytes.size.toLong(), part.toByteArray(), "opaque") }
            }
            NzbFile(name, ids, bytes.size.toLong())
        }
        val cache = ArticleCache(profile = profile) { id, _ ->
            beforeFetch(id)
            fetched.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
            articles.getValue(id)
        }
        fun select(files: List<NzbFile> = this.files, episode: Int? = null, index: Int? = null) =
            UsenetSelection.select(files, cache, index, emptyList(), 1, episode)
    }
    private fun Archive.named(stem: String = "set") = volumes.mapIndexed { i, bytes -> "$stem.part${i + 1}.rar" to bytes }

    @Test fun tenVolumeRarOpensWithThreeHeadsAndReadsEveryByteAndRandomSeek() {
        for (version in listOf(4, 5)) {
            val archive = archive(version, 10)
            val harness = Harness(archive.named())
            val file = harness.select()
            assertEquals(setOf("0-0", "1-0", "9-0"), harness.fetched.keys)
            val random = java.util.Random(741)
            repeat(40) {
                val offset = random.nextInt(archive.source.size - 100)
                assertArrayEquals(archive.source.copyOfRange(offset, offset + 100), file.bytesAt(offset.toLong(), 100))
            }
            assertArrayEquals(archive.source, file.bytesAt(0, archive.source.size))
        }
    }
    @Test fun changedMiddleHeaderRebuildsWithoutChangingPublishedSize() {
        for (version in listOf(4, 5)) {
            val archive = archive(version, 10, changed = 4)
            val harness = Harness(archive.named())
            val file = harness.select()
            assertEquals(3, harness.fetched.size)
            assertEquals(archive.source.size.toLong(), file.size)
            assertArrayEquals(archive.source, file.bytesAt(0, archive.source.size))
        }
    }
    @Test fun verifiedRangeRemainsReadableWhileAnotherRangeVerifiesItsVolume() {
        val archive = archive(5, 10)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val harness = Harness(archive.named(), beforeFetch = { id ->
            if (id == "4-0") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        })
        val file = harness.select()
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val slow = workers.submit<ByteArray> { file.bytesAt(17000, 100) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertArrayEquals(archive.source.copyOfRange(0, 100),
                workers.submit<ByteArray> { file.bytesAt(0, 100) }.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertArrayEquals(archive.source.copyOfRange(17000, 17100), slow.get(2, TimeUnit.SECONDS))
        } finally { release.countDown(); workers.shutdownNow() }
    }
    @Test fun missingPredictedMiddleVolumeFailsWithoutReturningSubstituteBytes() {
        val archive = archive(5, 10)
        val harness = Harness(archive.named(), beforeFetch = { id -> if (id == "4-0") throw MissingUsenetArticle() })
        val file = harness.select()
        assertEquals(3, harness.fetched.size)
        assertThrows(MissingUsenetArticle::class.java) { file.bytesAt(17000, 100) }
    }
    @Test fun realPaddedWidthFixtureMatchesKnownPayload() {
        val input = (1..14).map { i ->
            val name = "archive.part0$i.rar"
            name to javaClass.getResourceAsStream("/usenet/rar_widthmismatch/$name")!!.use { it.readBytes() }
        }
        val harness = Harness(input)
        val openers = harness.files.map { file -> { PostedFile(file, harness.cache, verifyTail = false) as UsenetFile } }
        val file = RarWalker(openers, RarPasswords(null) {}, {},
            { i, size -> LazyPostedFile(harness.files[i], harness.cache, size) }).candidates().first().open()
        assertEquals(3, harness.fetched.size)
        assertEquals(200000L, file.size)
        assertArrayEquals(ByteArray(200000) { it.toByte() }, file.bytesAt(0, 200000))
    }
    @Test fun splitRarFixturesDoNotFetchNonfinalTrailersDuringSelection() {
        for ((stem, count, password) in listOf(Triple("pattern", 2, null), Triple("locked", 3, "FixtureOnly5"))) {
            val input = (1..count).map { i ->
                val name = "$stem.part$i.rar"
                name to javaClass.getResourceAsStream("/usenet/$name")!!.use { it.readBytes() }
            }
            val harness = Harness(input)
            val file = UsenetSelection.select(harness.files, harness.cache, null, emptyList(), password = password)
            harness.files.dropLast(1).forEach { assertFalse(harness.fetched.containsKey(it.articles.last())) }
            val source = javaClass.getResourceAsStream("/usenet/test-pattern.mp4")!!.use { it.readBytes() }
            assertArrayEquals(source, file.bytesAt(0, source.size))
        }
    }
    @Test fun rar5VolumeNumberWidthGrowthRemainsSeekable() {
        val archive = archive(5, 132)
        val harness = Harness(archive.named())
        val file = harness.select()
        assertEquals(3, harness.fetched.size)
        assertArrayEquals(archive.source, file.bytesAt(0, archive.source.size))
    }
    @Test fun selectsNamedEpisodeSetBeforeTouchingOtherSets() {
        val archive = archive(5, 10)
        val harness = Harness(archive(5, 10, "Show.S01E01.mkv").named("Show.S01E01") +
            archive.named("Show.S01E02") + archive(5, 10, "Show.S01E03.mkv").named("Show.S01E03"))
        val file = harness.select(episode = 2)
        assertEquals(setOf("10-0", "11-0", "19-0"), harness.fetched.keys)
        assertArrayEquals(archive.source, file.bytesAt(0, archive.source.size))
    }
    @Test fun filenameFilterCannotOverrideAConflictingEpisodeMarker() {
        val harness = Harness(listOf("Show.S01E01.mkv" to ByteArray(100)))
        assertThrows(UsenetFailure::class.java) {
            UsenetSelection.select(harness.files, harness.cache, null, listOf("Show"), season = 1, episode = 2)
        }
        assertTrue(harness.fetched.isEmpty())
        val unmarked = Harness(listOf("Show.mkv" to ByteArray(100)))
        assertEquals("Show.mkv", UsenetSelection.select(unmarked.files, unmarked.cache, null,
            listOf("Show"), season = 1, episode = 2).name)
    }
    @Test fun skipsUnmatchedAnonymousSetUsingOnlyFirstAndLastHeads() {
        val target = archive(4, 10)
        val harness = Harness(archive(4, 10, "Show.S01E01.mkv").named("abc") + target.named("xyz"))
        val file = harness.select(episode = 2)
        assertEquals(setOf("0-0", "9-0", "10-0", "11-0", "19-0"), harness.fetched.keys)
        assertArrayEquals(target.source, file.bytesAt(0, target.source.size))
    }
    @Test fun shuffledSeventyVolumeObfuscatedSetKeepsHeadsAfterArticleEviction() {
        val archive = archive(5, 70)
        val input = archive.volumes.mapIndexed { i, bytes -> "opaque$i" to bytes }.shuffled(kotlin.random.Random(37))
        val harness = Harness(input, UsenetMemoryProfile(4096, 0, 0))
        val file = harness.select()
        assertTrue(harness.fetched.values.all { it.get() == 1 })
        assertTrue(harness.cache.retainedBytes() <= 4096)
        assertArrayEquals(archive.source, file.bytesAt(0, archive.source.size))
    }
    @Test fun probesEightUnknownHeadsConcurrently() {
        val entered = CountDownLatch(8)
        val cache = ArticleCache { _, _ ->
            entered.countDown()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Article(0, 1, byteArrayOf(1))
        }
        cache.probeHeads((1..8).map(Int::toString)) {}
        assertEquals(0, entered.count)
    }
    @Test fun duplicateAndNumericRar4VolumesAndLongLegacyNamesPlay() {
        val archive = archive(4, 3)
        val harness = Harness(archive.named())
        val duplicate = harness.files[1].copy(articles = emptyList())
        assertArrayEquals(archive.source, harness.select(harness.files + duplicate).bytesAt(0, archive.source.size))
        for (stem in listOf("set.rar", "set")) {
            val numeric = Harness(archive.volumes.mapIndexed { i, bytes -> "$stem.${(i + 1).toString().padStart(3, '0')}" to bytes })
            assertArrayEquals(archive.source, numeric.select().bytesAt(0, archive.source.size))
        }
        val hidden = Harness(archive.volumes.mapIndexed { i, bytes -> "opaque$i" to bytes })
        assertArrayEquals(archive.source, hidden.select().bytesAt(0, archive.source.size))
        val long = archive(4, 103)
        val legacy = Harness(long.volumes.mapIndexed { i, bytes ->
            (if (i == 0) "set.rar" else "set.r${(i - 1).toString().padStart(2, '0')}") to bytes })
        assertArrayEquals(long.source, legacy.select().bytesAt(0, long.source.size))
    }
    @Test fun splitVideoOpensOnlyEndsAndRejectsWrongMiddleSize() {
        val harness = Harness((1..10).map { "movie.mkv.${it.toString().padStart(3, '0')}" to ByteArray(800) { it.toByte() } })
        val file = harness.select()
        assertEquals(setOf("0-0", "9-0"), harness.fetched.keys)
        assertEquals(8000L, file.size)
        val bad = Harness((1..4).map { "movie.mkv.${it.toString().padStart(3, '0')}" to ByteArray(if (it == 3) 801 else 800) })
        assertThrows(UsenetFailure::class.java) { bad.select().bytesAt(1600, 1) }
    }
    @Test fun nzbMemoryCacheExpiresAndSeparatesHeadersAndEvictsOldest() {
        var now = 0L
        val cache = NzbMemoryCache { now }
        val document = NzbDocument(emptyList(), "private")
        cache.put("url", mapOf("Authorization" to "a"), document, 100)
        assertSame(document, cache.get("url", mapOf("Authorization" to "a")))
        assertNull(cache.get("url", mapOf("Authorization" to "b")))
        now = TimeUnit.MINUTES.toNanos(30)
        assertNull(cache.get("url", mapOf("Authorization" to "a")))
        repeat(5) { cache.put("$it", emptyMap(), document, 100) }
        assertNull(cache.get("0", emptyMap()))
        assertSame(document, cache.get("4", emptyMap()))
    }
    @Test fun diagnosticsContainOnlyStagesAndCountersAndReportOnce() {
        val logs = mutableListOf<String>()
        val trace = UsenetStartupTrace(logs::add)
        trace.mark("selection_started")
        trace.add("demand", 3)
        trace.finish(true)
        trace.finish(false)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("demand=3"))
        assertTrue(logs.single().matches(Regex("[a-z_0-9= truefalsms]+")))
    }
}
