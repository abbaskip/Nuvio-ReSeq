package com.nuvio.tv.livetv.player

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TsSegmenterRewindTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun tables(): List<ByteArray> {
        val pat = ByteArray(188) { 0xff.toByte() }
        pat[0] = 0x47; pat[1] = 0x40; pat[2] = 0; pat[3] = 0x10; pat[4] = 0
        pat[5] = 0; pat[6] = 0xb0.toByte(); pat[7] = 13
        pat[13] = 0; pat[14] = 1; pat[15] = 0xe0.toByte(); pat[16] = 100
        val pmt = ByteArray(188) { 0xff.toByte() }
        pmt[0] = 0x47; pmt[1] = 0x40; pmt[2] = 100; pmt[3] = 0x10; pmt[4] = 0
        pmt[5] = 2; pmt[6] = 0xb0.toByte(); pmt[7] = 19
        pmt[13] = 0xe1.toByte(); pmt[14] = 0
        return listOf(pat, pmt)
    }

    private fun frame(milliseconds: Long): ByteArray = ByteArray(188).apply {
        this[0] = 0x47; this[1] = 0x41; this[2] = 0; this[3] = 0x30
        this[4] = 7; this[5] = 0x10 // PCR, deliberately no random-access markers
        val ticks = milliseconds * 90
        this[6] = (ticks shr 25).toByte(); this[7] = (ticks shr 17).toByte()
        this[8] = (ticks shr 9).toByte(); this[9] = (ticks shr 1).toByte()
        this[10] = ((ticks and 1) shl 7).toByte()
    }

    @Test fun `rewind keeps short reload intervals even without keyframe markers and trims legally`() {
        val directory = temporary.newFolder()
        val segmenter = TsSegmenter(directory, targetSec = REWIND_SEGMENT_SECONDS, keepSec = 6.0,
            fixedTargetDurationSec = REWIND_TARGET_SECONDS)
        tables().forEach { segmenter.write(it, 0, it.size) }
        for (time in 0L..16_000L step 250) frame(time).let { segmenter.write(it, 0, it.size) }
        val playlist = segmenter.playlist.readText()
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:3\n"))
        assertFalse(playlist.contains("PLAYLIST-TYPE:EVENT"))
        val durations = playlist.lineSequence().filter { it.startsWith("#EXTINF:") }
            .map { it.substringAfter(':').substringBefore(',').toDouble() }.toList()
        assertTrue(durations.isNotEmpty())
        assertTrue(durations.all { it <= 3.0 })
        assertTrue(segmenter.trimmedSec >= 6.0)
        playlist.lineSequence().filter { it.endsWith(".ts") }.forEach { name ->
            val bytes = directory.resolve(name).readBytes()
            assertTrue(bytes.size >= 188 * 3)
            assertEquals(0x47, bytes[0].toInt()) // buffered writes were flushed before publication
        }
        segmenter.finish()
    }

    @Test fun `provider clock jumps do not inflate rewind durations or reload intervals`() {
        val segmenter = TsSegmenter(temporary.newFolder(), targetSec = REWIND_SEGMENT_SECONDS,
            keepSec = 60.0, fixedTargetDurationSec = REWIND_TARGET_SECONDS)
        tables().forEach { segmenter.write(it, 0, it.size) }
        for (start in listOf(0L, 70_000L, 1_000L)) {
            for (time in start..start + 4_000L step 250) frame(time).let { segmenter.write(it, 0, it.size) }
        }
        segmenter.finish()
        val playlist = segmenter.playlist.readText()
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:3\n"))
        assertEquals(2, playlist.lineSequence().count { it == "#EXT-X-DISCONTINUITY" })
        assertTrue(segmenter.totalSec in 12.0..13.0)
        assertTrue(playlist.lineSequence().filter { it.startsWith("#EXTINF:") }
            .all { it.substringAfter(':').substringBefore(',').toDouble() <= 3.0 })
    }

    @Test fun `ordinary recording behavior retains its existing target and event playlist`() {
        val segmenter = TsSegmenter(temporary.newFolder())
        tables().forEach { segmenter.write(it, 0, it.size) }
        for (time in 0L..8_000L step 250) frame(time).let { segmenter.write(it, 0, it.size) }
        segmenter.finish()
        val playlist = segmenter.playlist.readText()
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:20\n"))
        assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:EVENT\n"))
        assertTrue(playlist.contains("#EXT-X-ENDLIST"))
    }
}
