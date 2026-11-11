package com.nuvio.tv.core.usenet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class UsenetArchiveTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/usenet/$name")!!.use { it.readBytes() }

    private fun select(vararg input: Pair<String, ByteArray>, index: Int? = null, episode: Int? = null,
                       password: String? = null): UsenetFile {
        val articles = mutableMapOf<String, Article>()
        val files = input.mapIndexed { i, (name, bytes) ->
            var pos = 0
            val ids = mutableListOf<String>()
            while (pos < bytes.size) {
                val end = minOf(pos + 397, bytes.size)
                val id = "$i-$pos"
                ids += id
                articles[id] = Article(pos.toLong(), bytes.size.toLong(), bytes.copyOfRange(pos, end), "opaque")
                pos = end
            }
            NzbFile(name, ids, bytes.size.toLong())
        }
        return UsenetSelection.select(files, ArticleCache { id, _ -> articles.getValue(id) }, index, emptyList(),
            1, episode, password)
    }

    @Test fun readsCompressedMetadataAndSelectsTheRequestedEpisode() {
        val original = resource("test-pattern.mp4")
        for (name in listOf("pack.7z", "index-lzma1.7z", "index-lzma2.7z")) {
            val archive = name to resource(name)
            val first = select(archive, episode = 1)
            assertArrayEquals(ByteArray(256) { it.toByte() }, first.bytesAt(0, 256))
            assertArrayEquals(original, select(archive, episode = 2).bytesAt(0, original.size))
            assertThrows(UsenetFailure::class.java) { select(archive, episode = 3) }
            assertEquals(first.name, select(archive, index = 0, episode = 2).name)
        }
    }

    @Test fun identifiesCombinedEpisodesAndAvoidsSamplesAndWrongSingles() {
        val sample = "sample.Show.S01E01.mkv" to ByteArray(16)
        val combined = "Show.S01E01-E03.mkv" to ByteArray(1)
        assertEquals(combined.first, select(sample, combined, episode = 1).name)
        assertEquals(combined.first, select(combined, episode = 2).name)
        assertThrows(UsenetFailure::class.java) { select("Show.S01E01E03.mkv" to ByteArray(2), episode = 2) }
    }

    @Test fun selectsUsingAddonRequestCoordinatesWhenTvdbDisplayOrderDiffers() {
        assertEquals(1 to 2, UsenetPlayback.requestEpisode("tt1234567:1:2", 2, 7))
        assertEquals(0 to 3, UsenetPlayback.requestEpisode("tmdb:123:0:3", 4, 12))
        assertEquals(1 to 6, UsenetPlayback.requestEpisode("kitsu:123:6", 1, 6))
        assertEquals(null to null, UsenetPlayback.requestEpisode("tmdb:123", null, null))
    }

    @Test fun identifiesWrappedAndSignatureOnlyArchives() {
        val bytes = resource("test-pattern.mp4")
        for (archive in listOf("stored.7z", "wrapper.7z")) {
            val file = select("x5e87" to resource(archive))
            assertEquals(bytes.size.toLong(), file.size)
            assertArrayEquals(bytes, file.bytesAt(0, bytes.size))
        }
    }

    @Test fun validatesArchiveHeaderCrcAndRejectsCompressedPayloads() {
        val corrupt = resource("stored.7z").apply { this[lastIndex] = 1 }
        assertThrows(UsenetFailure::class.java) { select("movie.7z" to corrupt) }
        val error = assertThrows(UsenetFailure::class.java) { select("movie.7z" to resource("compressed.7z")) }
        assertTrue(error.message.orEmpty().contains("compression"))
    }

    @Test fun splitsRawVideoInNumericOrderAndRejectsMissingVolumes() {
        val a = "Show.mp4.001" to byteArrayOf(1, 2)
        val b = "Show.mp4.002" to byteArrayOf(3, 4)
        assertArrayEquals(byteArrayOf(2, 3), select(b, a).bytesAt(1, 2))
        assertThrows(UsenetFailure::class.java) { select(b) }
        assertThrows(UsenetFailure::class.java) { select(a, "Show.mp4.003" to b.second) }
    }

    @Test fun choosesAnIndexedVideoWithoutFetchingUnrelatedArchives() {
        val files = listOf(NzbFile("movie.mkv", listOf("movie"), 8), NzbFile("other.rar", listOf("bad"), 20))
        val cache = ArticleCache { id, _ -> assertEquals("movie", id); Article(0, 8, ByteArray(8)) }
        assertEquals("movie.mkv", UsenetSelection.select(files, cache, 0, emptyList()).name)
    }

    @Test fun verifiesEncryptedRarPasswordBeforeReturningVideoAndSeeksUnaligned() {
        val archive = "visible.rar" to resource("visible.rar")
        for (password in listOf(null, "wrong")) {
            val failure = assertThrows(UsenetFailure::class.java) { select(archive, password = password) }
            assertTrue(failure.message.orEmpty().contains("password"))
            assertFalse(failure.message.orEmpty().contains("wrong"))
        }
        val file = select(archive, password = "FixtureOnly5")
        val original = resource("test-pattern.mp4")
        assertEquals(original.size.toLong(), file.size)
        assertArrayEquals(original.copyOfRange(173, 1788), file.bytesAt(173, 1615))
    }

    @Test fun encryptedRangesPreserveCallerOffsetsAndTailBytes() {
        val file = select("visible.rar" to resource("visible.rar"), password = "FixtureOnly5")
        val expected = resource("test-pattern.mp4")
        for (position in listOf(0, 16, 17, expected.size - 5)) {
            val destination = ByteArray(70) { -1 }
            val count = file.read(position.toLong(), destination, 3, 64)
            val reference = ByteArray(70) { -1 }
            val end = minOf(position + 64, expected.size)
            expected.copyInto(reference, 3, position, end)
            assertEquals(end - position, count)
            assertArrayEquals(reference, destination)
        }
    }

    private fun packet(name: String, content: ByteArray): ByteArray {
        fun md5(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data)
        val filename = name.toByteArray()
        val bytes = ByteArray(120 + ((filename.size + 3) and -4))
        val fields = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fields.put("PAR2\u0000PKT".toByteArray()).putLong(bytes.size.toLong())
        fields.position(48)
        fields.put("PAR 2.0\u0000FileDesc".toByteArray())
        fields.position(80)
        fields.put(md5(content)).put(md5(content.copyOf(minOf(16384, content.size))))
            .putLong(content.size.toLong()).put(filename)
        md5(bytes.copyOfRange(32, bytes.size)).copyInto(bytes, 16)
        return bytes
    }

    @Test fun usesPar2SizeAndPrefixHashAcrossArticlesAndRejectsAmbiguousNames() {
        val bytes = ByteArray(19000) { (it * 7).toByte() }
        val recovery = packet("Show.S01E01.mkv", bytes)
        val hidden = "abcd123" to bytes
        val file = select(hidden, "set.par2" to recovery)
        assertEquals("Show.S01E01.mkv", file.name)
        assertArrayEquals(bytes, file.bytesAt(0, bytes.size))
        assertThrows(UsenetFailure::class.java) {
            select(hidden, "set.par2" to recovery + packet("Show.S01E02.mkv", bytes))
        }
        recovery[16] = recovery[16].inc()
        assertThrows(UsenetFailure::class.java) { select(hidden, "set.par2" to recovery) }
    }
}
