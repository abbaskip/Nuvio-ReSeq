package com.nuvio.tv.core.usenet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** PAR2 FileDesc packets identify an original filename by size and MD5 of its first 16 KiB. */
internal class Par2Names {
    private data class Key(val size: Long, val prefixHash: List<Byte>)
    private val names = mutableMapOf<Key, String?>()
    private val magic = "PAR2\u0000PKT".toByteArray(Charsets.US_ASCII)
    private val description = "PAR 2.0\u0000FileDesc".toByteArray(Charsets.US_ASCII)

    fun read(file: UsenetFile, checkActive: () -> Unit) {
        val window = minOf(file.size, 512 * 1024L)
        var pos = 0L
        repeat(512) {
            if (pos > window - 64) return
            checkActive()
            val header = file.bytesAt(pos, 64)
            if (!header.copyOf(8).contentEquals(magic)) return
            val length = littleLong(header, 8)
            if (length < 64 || length % 4 != 0L || length > window - pos) return
            if (length in 124..4248 && header.copyOfRange(48, 64).contentEquals(description)) {
                val body = file.bytesAt(pos + 64, length.toInt() - 64)
                val md5 = MessageDigest.getInstance("MD5")
                md5.update(header, 32, 32)
                if (md5.digest(body).contentEquals(header.copyOfRange(16, 32))) {
                    val name = String(body, 56, body.size - 56, Charsets.UTF_8).trimEnd('\u0000')
                    val size = littleLong(body, 48)
                    if (size > 0 && name.isNotBlank() && name.none(Char::isISOControl)) {
                        val key = Key(size, body.copyOfRange(32, 48).toList())
                        // Different descriptions can legitimately share a prefix. Never guess between names.
                        if (key in names && names[key] != name) names[key] = null else names[key] = name
                    }
                }
            }
            pos += length
        }
    }

    fun hasDescriptions() = names.isNotEmpty()

    fun find(file: UsenetFile): String? {
        if (names.keys.none { it.size == file.size }) return null
        val prefix = file.bytesAt(0, minOf(file.size, 16384L).toInt())
        val hash = MessageDigest.getInstance("MD5").digest(prefix)
        return names[Key(file.size, hash.toList())]
    }

    private fun littleLong(bytes: ByteArray, start: Int) =
        ByteBuffer.wrap(bytes, start, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
