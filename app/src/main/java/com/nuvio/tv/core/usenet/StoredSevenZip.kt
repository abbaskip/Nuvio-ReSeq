package com.nuvio.tv.core.usenet

import org.tukaani.xz.LZMA2InputStream
import org.tukaani.xz.LZMAInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** 7z Copy folders map directly onto NNTP ranges. Only the bounded index is ever decompressed. */
internal object StoredSevenZip {
    val signature = byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)
    private const val INVALID = "Invalid or truncated 7z index."
    private const val MAX_HEADER = 2 * 1024 * 1024
    private const val MAX_FILES = 8192
    private const val COPY = 0L
    private const val AES = 0x06f10701L

    private class Cursor(val bytes: ByteArray) {
        var pos = 0
        fun byte(): Int {
            ensureUsenet(pos < bytes.size, INVALID)
            return bytes[pos++].toInt() and 255
        }

        fun expect(id: Int) = ensureUsenet(byte() == id, INVALID)

        fun take(n: Int): ByteArray {
            ensureUsenet(n >= 0 && n <= bytes.size - pos, INVALID)
            return bytes.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun number(): Long {
            val first = byte()
            var mask = 128
            var value = 0L
            for (i in 0..7) {
                if (first and mask == 0) return value or ((first and (mask - 1)).toLong() shl (8 * i))
                value = value or (byte().toLong() shl (8 * i))
                mask = mask ushr 1
            }
            ensureUsenet(value >= 0, INVALID)
            return value
        }

        fun count(max: Int = MAX_FILES) = number().also { ensureUsenet(it <= max, INVALID) }.toInt()

        fun flags(n: Int): BooleanArray {
            var bits = 0
            return BooleanArray(n) { i ->
                if (i % 8 == 0) bits = byte()
                bits and (128 ushr (i % 8)) != 0
            }
        }

        fun digests(n: Int): List<Long?> {
            val defined = if (byte() == 0) flags(n) else null
            return List(n) { i ->
                if (defined != null && !defined[i]) null else
                    (0..3).fold(0L) { v, j -> v or (byte().toLong() shl (8 * j)) }
            }
        }
    }

    private data class Folder(val codec: Long, val props: ByteArray, val start: Long, val packed: Long,
                              val size: Long, val crc: Long?)
    private data class Member(val folder: Folder, val offset: Long, val size: Long)

    /** Common Copy/LZMA/LZMA2 archives have one input, output and coder per folder. */
    private fun streams(c: Cursor, limit: Long): List<Member> {
        c.expect(6)
        val relative = c.number()
        ensureUsenet(relative <= limit - 32, INVALID)
        var position = 32 + relative
        val folders = c.count()
        c.expect(9)
        val packed = LongArray(folders) { c.number() }
        var id = c.byte()
        if (id == 10) { c.digests(folders); id = c.byte() }
        ensureUsenet(id == 0, INVALID)
        c.expect(7)
        c.expect(11)
        ensureUsenet(c.count() == folders, "Unsupported 7z coder layout.")
        c.expect(0)
        val methods = List(folders) {
            ensureUsenet(c.count() == 1, "Chained or encrypted 7z coders cannot be streamed.")
            val flags = c.byte()
            val length = flags and 15
            ensureUsenet(flags and 0xd0 == 0 && length in 1..8, "Unsupported 7z coder.")
            var codec = 0L
            repeat(length) { codec = (codec shl 8) or c.byte().toLong() }
            codec to (if (flags and 32 != 0) c.take(c.count(64)) else byteArrayOf())
        }
        c.expect(12)
        val sizes = LongArray(folders) { c.number() }
        id = c.byte()
        val crcs = if (id == 10) c.digests(folders).also { id = c.byte() } else emptyList()
        ensureUsenet(id == 0, INVALID)
        val descriptors = methods.mapIndexed { i, (codec, props) ->
            ensureUsenet(packed[i] <= limit - position && (codec != COPY || packed[i] == sizes[i]), INVALID)
            Folder(codec, props, position, packed[i], sizes[i], crcs.getOrNull(i)).also { position += packed[i] }
        }
        val members = ArrayList<Member>()
        id = c.byte()
        val substreams = id == 8
        if (substreams) id = c.byte()
        val counts = if (substreams && id == 13) IntArray(folders) { c.count() }.also { id = c.byte() }
            else IntArray(folders) { 1 }
        ensureUsenet(counts.sumOf { it.toLong() } <= MAX_FILES, INVALID)
        val hasSizes = substreams && id == 9
        descriptors.forEachIndexed { i, folder ->
            var used = 0L
            repeat(counts[i]) { j ->
                val remaining = folder.size - used
                val size = if (j + 1 == counts[i]) remaining else {
                    ensureUsenet(hasSizes, INVALID)
                    c.number().also { ensureUsenet(it <= remaining, INVALID) }
                }
                members += Member(folder, used, size)
                used += size
            }
        }
        if (hasSizes) id = c.byte()
        if (substreams && id == 10) {
            c.digests(counts.indices.sumOf { i -> if (counts[i] == 1 && descriptors[i].crc != null) 0 else counts[i] })
            id = c.byte()
        }
        ensureUsenet(id == 0, INVALID)
        if (substreams) c.expect(0)
        return members
    }

    private fun unpackIndex(file: UsenetFile, folder: Folder): ByteArray {
        ensureUsenet(folder.size in 1..MAX_HEADER.toLong() && folder.packed in 1..MAX_HEADER.toLong(), INVALID)
        val packed = file.bytesAt(folder.start, folder.packed.toInt())
        val props = folder.props
        val size = folder.size.toInt()
        val result = try {
            val input = when (folder.codec) {
                COPY -> packed.inputStream()
                0x030101L -> {
                    ensureUsenet(props.size == 5, INVALID)
                    val dict = ByteBuffer.wrap(props, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                    val bounded = minOf(dict, folder.size).toInt()
                    ensureUsenet(LZMAInputStream.getMemoryUsage(bounded, props[0]) <= 8192, "7z index requires too much memory.")
                    LZMAInputStream(packed.inputStream(), folder.size, props[0], bounded).apply { enableRelaxedEndCondition() }
                }
                0x21L -> {
                    ensureUsenet(props.size == 1 && (props[0].toInt() and 255) <= 40, INVALID)
                    val p = props[0].toInt()
                    val dict = (2L or (p and 1).toLong()) shl (p / 2 + 11)
                    LZMA2InputStream(packed.inputStream(), minOf(dict, folder.size).coerceAtLeast(4096).toInt())
                }
                AES -> throw UsenetFailure("7z archive headers are password-protected.")
                else -> throw UsenetFailure("Unsupported 7z header compression.")
            }
            input.use {
                val data = ByteArray(size)
                java.io.DataInputStream(it).readFully(data)
                ensureUsenet(it.read() == -1, INVALID)
                data
            }
        } catch (e: UsenetFailure) { throw e }
          catch (_: IOException) { throw UsenetFailure(INVALID) }
        ensureUsenet(folder.crc == null || crc(result) == folder.crc, INVALID)
        return result
    }

    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    fun index(file: UsenetFile): List<UsenetCandidate> {
        val start = file.bytesAt(0, 32)
        ensureUsenet(start.copyOf(6).contentEquals(signature) && start[6].toInt() == 0, "Unrecognized 7z volume.")
        val header = ByteBuffer.wrap(start).order(ByteOrder.LITTLE_ENDIAN)
        ensureUsenet(header.getInt(8) == crc(start.copyOfRange(12, 32)).toInt(), INVALID)
        val offset = header.getLong(12)
        val length = header.getLong(20)
        ensureUsenet(length in 1..MAX_HEADER.toLong() && offset in 0..file.size - 32 - length, INVALID)
        var limit = 32 + offset
        val next = file.bytesAt(limit, length.toInt())
        ensureUsenet(header.getInt(28) == crc(next).toInt(), INVALID)
        var c = Cursor(next)
        if (c.byte() == 23) {
            val member = streams(c, limit).singleOrNull() ?: throw UsenetFailure(INVALID)
            ensureUsenet(member.size == member.folder.size && c.pos == next.size, INVALID)
            limit = member.folder.start
            c = Cursor(unpackIndex(file, member.folder))
            c.expect(1)
        } else ensureUsenet(next[0].toInt() == 1, INVALID)
        c.expect(4)
        val members = streams(c, limit)
        c.expect(5)
        val n = c.count()
        var names = emptyList<String>()
        var empty = BooleanArray(n)
        while (true) {
            val id = c.byte()
            if (id == 0) break
            val property = Cursor(c.take(c.count(MAX_HEADER)))
            when (id) {
                17 -> {
                    property.expect(0)
                    names = List(n) {
                        val begin = property.pos
                        while ((property.byte() or property.byte()) != 0) {
                            ensureUsenet(property.pos - begin <= 8192, INVALID)
                        }
                        String(property.bytes, begin, property.pos - 2 - begin, Charsets.UTF_16LE)
                    }
                    ensureUsenet(property.pos == property.bytes.size, INVALID)
                }
                14 -> empty = property.flags(n)
            }
        }
        c.expect(0)
        ensureUsenet(c.pos == c.bytes.size && names.size == n && empty.count { !it } == members.size, INVALID)
        var nextMember = 0
        return names.mapIndexedNotNull { i, name ->
            if (empty[i]) return@mapIndexedNotNull null
            val member = members[nextMember++]
            UsenetCandidate(name, member.size) {
                val folder = member.folder
                ensureUsenet(folder.codec != AES, "This 7z video is password-protected.")
                ensureUsenet(folder.codec == COPY, "This 7z video uses compression and cannot be streamed.")
                ensureUsenet(member.size > 0, "The archived file is empty.")
                StoredFile(name, listOf(FileExtent(file, folder.start + member.offset, member.size)))
            }
        }
    }
}
