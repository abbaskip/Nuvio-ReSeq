package com.nuvio.tv.mobile

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A small sliding HLS window. An open HTTP response keeps its file descriptor after eviction. */
internal class LiveCastWindow(private val dir: File) : Closeable {
    private data class Segment(val id: Int, val seconds: Double)
    private val segments = ArrayDeque<Segment>()
    @Volatile var sequence = 0
        private set
    private var closed = false
    private var targetSeconds = 0
    private var firstListedId = 0
    private var finished = false
    val ready = kotlinx.coroutines.CompletableDeferred<Unit>()

    @Synchronized fun create(name: String): OutputStream {
        check(!closed)
        return File(dir, name).outputStream()
    }

    @Synchronized fun publish(seconds: Double) {
        check(!closed && seconds.isFinite() && seconds > 0)
        if (!ready.isCompleted) targetSeconds = maxOf(targetSeconds, ceil(seconds).toInt())
        else check(seconds.roundToInt() <= targetSeconds) { "Channel keyframe interval changed" }
        val id = sequence++
        check(File(dir, "pending").renameTo(File(dir, "$id.m4s")))
        segments.addLast(Segment(id, seconds))
        // Keep at least three target durations advertised and three older files for in-flight requests.
        var duration = 0.0
        for (i in segments.lastIndex downTo 0) {
            duration += segments[i].seconds
            if (duration >= 3 * targetSeconds && segments.size - i >= 5) {
                firstListedId = segments[i].id
                break
            }
        }
        while (segments.first().id < firstListedId - 3) File(dir, "${segments.removeFirst().id}.m4s").delete()
        if (segments.size >= 3) ready.complete(Unit)
    }

    @Synchronized fun playlist(): String {
        val visible = segments.filter { it.id >= firstListedId }
        check(visible.isNotEmpty())
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$targetSeconds")
            appendLine("#EXT-X-MEDIA-SEQUENCE:${visible.first().id}")
            appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
            for (segment in visible) {
                appendLine(String.format(Locale.ROOT, "#EXTINF:%.6f,", segment.seconds))
                appendLine("${segment.id}.m4s")
            }
            if (finished) appendLine("#EXT-X-ENDLIST")
        }
    }

    @Synchronized fun finish() {
        finished = true
        if (segments.isNotEmpty()) ready.complete(Unit)
    }

    @Synchronized fun <T> read(name: String, open: (File) -> T): T? {
        if (closed || (name != "init.mp4" && segments.none { name == "${it.id}.m4s" })) return null
        val file = File(dir, name)
        return if (file.isFile) open(file) else null
    }

    @Synchronized override fun close() {
        closed = true
        ready.cancel()
        dir.deleteRecursively()
    }
}

/** Turns the pinned Media3 muxer's sequential MP4 output into independently addressable HLS fragments. */
internal class LiveCastFragments(private val window: LiveCastWindow) : OutputStream() {
    private val header = ByteBuffer.allocate(8)
    private val metadata = ByteArrayOutputStream()
    private val decodeTimes = mutableMapOf<Int, Long>()
    private val firstSampleUs = mutableMapOf<Int, Long>()
    private val timescales = mutableMapOf<Int, Int>()
    private var videoTrack = -1
    private var segmentSeconds = 0.0
    private var type = ""
    private var remaining = 0
    private var out: OutputStream? = null

    fun sampleWritten(trackId: Int, timeUs: Long) {
        firstSampleUs.putIfAbsent(trackId, timeUs)
    }

    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

    override fun write(bytes: ByteArray, off: Int, len: Int) {
        var position = off
        val end = off + len
        while (position < end) {
            if (remaining == 0) {
                val count = minOf(header.remaining(), end - position)
                header.put(bytes, position, count)
                position += count
                if (header.hasRemaining()) continue
                remaining = header.getInt(0) - 8
                type = String(header.array(), 4, 4, Charsets.US_ASCII)
                require(remaining in 1..(if (type == "mdat") 64 shl 20 else 2 shl 20))
                if (type == "mdat") requireNotNull(out).write(header.array())
                else { metadata.reset(); metadata.write(header.array()) }
                header.clear()
            }
            val count = minOf(remaining, end - position)
            (if (type == "mdat") requireNotNull(out) else metadata).write(bytes, position, count)
            position += count
            remaining -= count
            if (remaining == 0) completeBox()
        }
    }

    private fun completeBox() {
        when (type) {
            "ftyp" -> {
                val box = metadata.toByteArray()
                // HLS initialization sections require an iso6-compatible brand.
                "iso6".toByteArray(Charsets.US_ASCII).copyInto(box, 8)
                out = window.create("init.mp4").also { it.write(box) }
            }
            "moov" -> {
                val box = metadata.toByteArray()
                readTracks(ByteBuffer.wrap(box))
                requireNotNull(out).use { it.write(box) }
                out = null
            }
            "moof" -> {
                val fragment = hlsFragment(ByteBuffer.wrap(metadata.toByteArray()))
                out = window.create("pending").buffered().also { it.write(fragment) }
            }
            "mdat" -> {
                requireNotNull(out).close()
                out = null
                window.publish(segmentSeconds)
            }
            else -> error("Unexpected live muxer box")
        }
    }

    private data class Box(val start: Int, val size: Int, val type: String)
    private fun ByteBuffer.children(start: Int, end: Int): List<Box> = buildList {
        var p = start
        while (p < end) {
            require(end - p >= 8)
            val size = getInt(p)
            require(size in 8..end - p)
            add(Box(p, size, String(array(), p + 4, 4, Charsets.US_ASCII)))
            p += size
        }
    }

    private fun readTracks(data: ByteBuffer) {
        for (trak in data.children(8, data.limit()).filter { it.type == "trak" }) {
            val children = data.children(trak.start + 8, trak.start + trak.size)
            val tkhd = children.single { it.type == "tkhd" }.start
            val id = data.getInt(tkhd + if (data.get(tkhd + 8).toInt() == 1) 28 else 20)
            val mdia = children.single { it.type == "mdia" }
            val media = data.children(mdia.start + 8, mdia.start + mdia.size)
            val mdhd = media.single { it.type == "mdhd" }.start
            timescales[id] = data.getInt(mdhd + if (data.get(mdhd + 8).toInt() == 1) 28 else 20)
            val handler = media.single { it.type == "hdlr" }.start
            if (data.getInt(handler + 16) == 0x76696465) videoTrack = id // vide
        }
    }

    private fun hlsFragment(data: ByteBuffer): ByteArray {
        val boxes = data.children(8, data.limit())
        // Media3 1.8 writes an absolute 8-byte base offset in tfhd and omits tfdt.
        // Replace that offset with default-base-is-moof and a 20-byte version 1 decode-time box.
        val growth = 12 * boxes.count { it.type == "traf" }
        val result = ByteBuffer.allocate(data.limit() + growth)
        result.putInt(result.capacity()).putInt(0x6d6f6f66) // moof
        segmentSeconds = 0.0
        for (box in boxes) {
            if (box.type != "traf") {
                result.put(data.array(), box.start, box.size)
                continue
            }
            val children = data.children(box.start + 8, box.start + box.size)
            val tfhd = children.single { it.type == "tfhd" }
            val trun = children.single { it.type == "trun" }
            require(children.size == 2 && tfhd.size == 24 && data.getInt(tfhd.start + 8) == 1)
            val id = data.getInt(tfhd.start + 12)
            val flags = data.getInt(trun.start + 8) and 0xffffff
            require(flags == 0x701 || flags == 0xf01)
            val samples = data.getInt(trun.start + 12)
            val stride = if (flags == 0xf01) 16 else 12
            require(samples > 0 && trun.size.toLong() == 20L + samples.toLong() * stride)
            val scale = timescales.getValue(id)
            val compositionOffset = if (flags == 0xf01) data.getInt(trun.start + 32) else 0
            val firstDts = firstSampleUs.remove(id)?.let { (it * scale / 1_000_000) - compositionOffset }
            // Anchor each run to the original media clock. Summing rounded sample durations drifts on long live casts.
            val time = (firstDts ?: decodeTimes.getOrDefault(id, 0L)).coerceAtLeast(0L)
            result.putInt(box.size + 12).putInt(0x74726166) // traf
            result.putInt(16).putInt(0x74666864).putInt(0x020000).putInt(id) // tfhd
            result.putInt(20).putInt(0x74666474).putInt(0x01000000).putLong(time) // tfdt
            val duration = (0 until samples).sumOf { data.getInt(trun.start + 20 + it * stride).toLong() }
            decodeTimes[id] = time + duration
            if (id == videoTrack || videoTrack == -1)
                segmentSeconds = duration.toDouble() / scale
            data.putInt(trun.start + 16, data.getInt(trun.start + 16) + growth)
            result.put(data.array(), trun.start, trun.size)
        }
        return result.array()
    }

    override fun close() { out?.close(); out = null }
}
