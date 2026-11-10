/*
 * Behaviour adapted from haaihond/NuvioTV, GPL-3.0:
 * native/usenet/internal/engine/rar.go and select.go
 * commit bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6.
 * Kotlin implementation; uncertain layouts use verified header discovery.
 */
package com.nuvio.tv.core.usenet

internal data class RarLayout(
    val version: Int, val number: Long = 0, val numberWidth: Int = 0,
    val mainSize: Long = 0, val sizeWidth: Int = 0, val packedWidth: Int = 0
) {
    private fun width(value: Long): Int {
        var n = value
        var result = 1
        while (n >= 128) { n = n ushr 7; result++ }
        return result
    }
    fun shift(relative: Int, packed: Long): Long? {
        if (version == 4) return 0
        var delta = (maxOf(numberWidth, width(number + relative)) - numberWidth).toLong()
        delta += maxOf(sizeWidth, width(mainSize + delta)) - sizeWidth
        val length = packed - delta
        return delta.takeIf { length > 0 && (packedWidth != width(packed) || width(length) == packedWidth) }
    }
}

/** Publication of a new layout never holds up reads of already verified extents. */
private class PredictedRarFile(
    override val name: String, override val size: Long, initial: List<RarPart>,
    private val verify: (Int) -> RarPart, private val rebuild: () -> List<RarPart>
) : UsenetFile {
    private data class Layout(val parts: List<RarPart>, val ends: LongArray, val verified: Set<Int>)
    private fun layout(parts: List<RarPart>, verified: Set<Int>): Layout {
        var sum = 0L
        val ends = LongArray(parts.size) { i ->
            ensureUsenet(parts[i].extent.length in 1..(size - sum), "Incomplete multipart RAR file.")
            sum += parts[i].extent.length
            sum
        }
        ensureUsenet(sum == size, "Incomplete multipart RAR file.")
        return Layout(parts, ends, verified)
    }
    @Volatile private var current = layout(initial, setOf(0, 1, initial.lastIndex))
    private val layoutGate = Any()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= size) return -1
        while (true) {
            val snapshot = current
            val i = snapshot.ends.binarySearch(position + 1).let { if (it < 0) -it - 1 else it }
            if (i !in snapshot.verified) {
                synchronized(layoutGate) {
                    if (current === snapshot) {
                        val expected = snapshot.parts[i]
                        val actual = verify(i)
                        current = if (actual.name == expected.name && actual.continues == expected.continues &&
                            actual.more == expected.more && actual.stored == expected.stored &&
                            actual.encrypted == expected.encrypted && actual.unpacked == expected.unpacked &&
                            actual.extent.offset == expected.extent.offset && actual.extent.length == expected.extent.length &&
                            actual.extent.volume.size == expected.extent.volume.size &&
                            (actual.encryption == null && expected.encryption == null ||
                                actual.encryption?.sameCipher(expected.encryption) == true)) {
                            snapshot.copy(parts = snapshot.parts.toMutableList().apply { set(i, actual) },
                                verified = snapshot.verified + i)
                        } else {
                            val parts = rebuild()
                            layout(parts, parts.indices.toSet())
                        }
                    }
                }
                continue
            }
            val extent = snapshot.parts[i].extent
            val within = position - (snapshot.ends[i] - extent.length)
            return extent.volume.read(extent.offset + within, buffer, offset,
                minOf(length.toLong(), extent.length - within).toInt())
        }
    }
}

internal class RarWalker(
    private val volumes: List<() -> UsenetFile>, private val passwords: RarPasswords,
    private val checkActive: () -> Unit, private val lazyVolume: ((Int, Long) -> UsenetFile)? = null
) {
    private inner class Cursor(var volume: Int) {
        var parts = emptyList<RarPart>().iterator()
        fun next(): RarPart? {
            while (!parts.hasNext()) {
                if (volume >= volumes.size) return null
                checkActive()
                parts = StoredRar.parts(volumes[volume++](), passwords).iterator()
            }
            return parts.next()
        }
    }
    private fun continuation(first: RarPart, part: RarPart) {
        ensureUsenet(part.continues && part.name == first.name && part.unpacked == first.unpacked,
            "Incomplete multipart RAR file.")
    }
    private fun scan(base: Int, first: RarPart): List<RarPart> {
        val result = mutableListOf(first)
        val cursor = Cursor(base + 1)
        while (result.last().more) {
            val part = cursor.next() ?: throw UsenetFailure("NZB is missing the final RAR volume.")
            continuation(first, part)
            result += part
        }
        validate(result)
        return result
    }
    private fun validate(parts: List<RarPart>) {
        ensureUsenet(parts.all { it.stored }, "This RAR video uses compression and cannot be streamed.")
        val first = parts.first()
        val size = first.unpacked
        if (first.encrypted) {
            val encryption = first.encryption ?: throw UsenetFailure("Unsupported encrypted RAR layout.")
            ensureUsenet(parts.all { encryption.sameCipher(it.encryption) && it.unpacked == size },
                "Inconsistent encrypted RAR volumes.")
        } else ensureUsenet(parts.none { it.encrypted }, "Inconsistent encrypted RAR volumes.")
        if (size != null && size > 0) {
            val payload = payload(first) ?: throw UsenetFailure("Invalid RAR file size.")
            ensureUsenet(parts.sumOf { it.extent.length } == payload, "Incomplete multipart RAR file.")
        }
    }
    private fun payload(first: RarPart): Long? = first.unpacked?.takeIf { it > 0 && it <= Long.MAX_VALUE - 15 }
        ?.let { if (first.encrypted) (it + 15) and -16L else it }

    private fun open(base: Int, first: RarPart): UsenetFile {
        val predicted = try { predict(base, first) } catch (_: UsenetFailure) { checkActive(); null }
        val packed = predicted ?: scan(base, first).let { StoredFile(first.name, it.map(RarPart::extent)) }
        if (!first.encrypted) return packed
        val encryption = first.encryption ?: throw UsenetFailure("Unsupported encrypted RAR layout.")
        val size = first.unpacked ?: throw UsenetFailure("Encrypted RAR file size is unknown.")
        ensureUsenet(size in 1..packed.size && packed.size - size < 16 && packed.size % 16 == 0L,
            "Inconsistent encrypted RAR volumes.")
        return RarPlaintext(packed, 0, size, passwords.key(encryption), encryption.iv)
    }
    private fun predict(base: Int, first: RarPart): UsenetFile? {
        val lazy = lazyVolume ?: return null
        val size = payload(first) ?: return null
        if (!first.more || !first.stored || volumes.size - base < 4) return null
        val second = StoredRar.parts(volumes[base + 1](), passwords).firstOrNull() ?: return null
        if (!second.continues || !second.more || second.name != first.name || second.unpacked != first.unpacked ||
            second.extent.volume.size != first.extent.volume.size || !second.stored) return null
        val template = second.layout ?: return null
        val parts = mutableListOf(first, second)
        var remaining = size - first.extent.length - second.extent.length
        if (remaining <= 0) return null
        for (i in base + 2 until volumes.size) {
            val shift = template.shift(i - base - 1, second.extent.length) ?: return null
            val length = second.extent.length - shift
            if (remaining <= length) {
                val last = StoredRar.parts(volumes[i](), passwords).firstOrNull() ?: return null
                if (!last.continues || last.more || last.name != first.name || last.unpacked != first.unpacked ||
                    last.extent.length != remaining) return null
                parts += last
                validate(parts)
                return PredictedRarFile(first.name, size, parts,
                    verify = { n -> StoredRar.parts(volumes[base + n](), passwords).firstOrNull()
                        ?: throw UsenetFailure("Incomplete multipart RAR file.") },
                    rebuild = { scan(base, first) })
            }
            parts += second.copy(extent = FileExtent(lazy(i, second.extent.volume.size),
                second.extent.offset + shift, length))
            remaining -= length
        }
        return null
    }

    fun candidates(): Sequence<UsenetCandidate> = sequence {
        val cursor = Cursor(0)
        var count = 0
        while (true) {
            val first = cursor.next() ?: break
            ensureUsenet(!first.continues, "Incomplete multipart RAR file.")
            ensureUsenet(++count <= 8192, "Too many archived files.")
            val base = cursor.volume - 1
            yield(UsenetCandidate(first.name, first.unpacked ?: first.extent.length) { open(base, first) })
            if (!first.more) continue
            // Prove that an unselected split entry occupies the entire middle of the set.
            val tail = Cursor(volumes.lastIndex)
            val last = if (base < volumes.lastIndex) tail.next() else null
            if (last != null && last.continues && !last.more && last.name == first.name && last.unpacked == first.unpacked) {
                cursor.volume = tail.volume
                cursor.parts = tail.parts
            } else {
                var part = first
                while (part.more) {
                    part = cursor.next() ?: throw UsenetFailure("NZB is missing the final RAR volume.")
                    continuation(first, part)
                }
            }
        }
    }
}
