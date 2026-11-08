package com.nuvio.tv.core.usenet

import java.util.zip.CRC32

private const val BAD_HEADER = "Invalid RAR header."
private class Header(val bytes: ByteArray, var pos: Int = 0) {
    fun byte(): Int {
        ensureUsenet(pos in bytes.indices, BAD_HEADER)
        return bytes[pos++].toInt() and 255
    }
    fun word() = byte() or (byte() shl 8)
    fun dword() = word().toLong() or (word().toLong() shl 16)
    fun vint(): Long {
        var value = 0L
        for (shift in 0..56 step 7) {
            val next = byte()
            value = value or ((next and 127).toLong() shl shift)
            if (next < 128) return value
        }
        throw UsenetFailure(BAD_HEADER)
    }
    fun encryption(payload: Boolean): RarEncryption {
        ensureUsenet(vint() == 0L, "Unsupported RAR encryption version.")
        val flags = vint()
        ensureUsenet(flags in 0..3, "Unsupported RAR encryption options.")
        val rounds = byte()
        fun take(n: Int) = ByteArray(n) { byte().toByte() }
        return RarEncryption(rounds, take(16), if (payload) take(16) else byteArrayOf(),
            if (flags and 1L != 0L) take(12) else byteArrayOf())
    }
    fun length(): Int {
        val n = vint()
        ensureUsenet(n <= bytes.size - pos, BAD_HEADER)
        return n.toInt()
    }
    fun name(length: Int, charset: java.nio.charset.Charset): String {
        ensureUsenet(length in 1..4096 && pos <= bytes.size - length, BAD_HEADER)
        return String(bytes, pos, length, charset).substringBefore('\u0000').also { pos += length }
    }
}

internal data class RarPart(
    val name: String, val extent: FileExtent, val continues: Boolean, val more: Boolean,
    val stored: Boolean, val encrypted: Boolean, val encryption: RarEncryption? = null, val unpacked: Long? = null
)

internal object StoredRar {
    val signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)
    fun index(volume: UsenetFile, passwords: RarPasswords, onVolumeNumber: (Long) -> Unit = {}): List<RarPart> {
        val prefix = volume.bytesAt(0, 8)
        ensureUsenet(prefix.copyOf(6).contentEquals(signature), "Unrecognized RAR volume.")
        return when (prefix[6].toInt()) {
            0 -> readRar4(volume)
            1 -> {
                ensureUsenet(prefix[7].toInt() == 0, BAD_HEADER)
                readRar5(volume, passwords, onVolumeNumber)
            }
            else -> throw UsenetFailure("Unsupported RAR version.")
        }
    }

    private fun readRar4(file: UsenetFile): List<RarPart> {
        val parts = mutableListOf<RarPart>()
        var pos = 7L
        var blocks = 0
        while (pos < file.size) {
            ensureUsenet(++blocks <= 8192, BAD_HEADER)
            val base = Header(file.bytesAt(pos, 7))
            val crc = base.word()
            val kind = base.byte()
            val flags = base.word()
            val length = base.word()
            ensureUsenet(length >= 7, BAD_HEADER)
            val header = Header(file.bytesAt(pos, length), 7)
            ensureUsenet(CRC32().apply { update(header.bytes, 2, length - 2) }.value.toInt() and 65535 == crc, BAD_HEADER)
            var packed = if (flags and 0x8000 != 0 || kind == 0x74) header.dword() else 0L
            pos += length
            if (kind == 0x73) ensureUsenet(flags and 0x80 == 0, "RAR volume headers are encrypted.")
            if (kind == 0x7b) break
            if (kind == 0x74) {
                header.pos = 25
                val method = header.byte()
                val nameLength = header.word()
                header.pos = 32
                if (flags and 0x100 != 0) {
                    val high = header.dword()
                    ensureUsenet(high < 0x80000000L, BAD_HEADER)
                    packed = packed or (high shl 32)
                    header.dword()
                }
                val name = header.name(nameLength, Charsets.ISO_8859_1)
                if (packed > 0 && flags and 0xe0 != 0xe0) {
                    parts += RarPart(name, FileExtent(file, pos, packed), flags and 1 != 0, flags and 2 != 0,
                        method == 0x30, flags and 4 != 0)
                }
            }
            ensureUsenet(packed <= file.size - pos, "Truncated RAR volume.")
            pos += packed
        }
        return parts
    }

    private fun readRar5(file: UsenetFile, passwords: RarPasswords, onVolumeNumber: (Long) -> Unit): List<RarPart> {
        val parts = mutableListOf<RarPart>()
        var headerKey: javax.crypto.spec.SecretKeySpec? = null
        var pos = 8L
        var blocks = 0
        while (pos < file.size) {
            ensureUsenet(++blocks <= 8192, BAD_HEADER)
            val key = headerKey
            val view = if (key == null) file else {
                val iv = file.bytesAt(pos, 16)
                pos += 16
                RarPlaintext(file, pos, (file.size - pos) and -16L, key, iv)
            }
            val offset = if (key == null) pos else 0L
            val prefix = Header(view.bytesAt(offset, minOf(7L, view.size - offset).toInt()))
            val crc = prefix.dword()
            val length = prefix.vint()
            ensureUsenet(length in 1..1048576, BAD_HEADER)
            val header = Header(view.bytesAt(offset + 4, prefix.pos - 4 + length.toInt()), prefix.pos - 4)
            ensureUsenet(CRC32().apply { update(header.bytes) }.value == crc, BAD_HEADER)
            val consumed = 4 + header.bytes.size
            pos += if (key == null) consumed else (consumed + 15) and -16
            val kind = header.vint()
            val flags = header.vint().toInt()
            val extra = if (flags and 1 != 0) header.length() else 0
            val packed = if (flags and 2 != 0) header.vint() else 0L
            ensureUsenet(packed <= file.size - pos, "Truncated RAR volume.")
            if (kind == 4L) {
                ensureUsenet(headerKey == null && packed == 0L, BAD_HEADER)
                headerKey = passwords.key(header.encryption(payload = false))
            }
            if (kind == 1L) {
                val archiveFlags = header.vint()
                if (archiveFlags and 1L != 0L) onVolumeNumber(if (archiveFlags and 2L != 0L) header.vint() else 0)
            }
            if (kind == 5L) break
            if (kind == 2L) {
                val attributes = header.vint().toInt()
                val unpacked = header.vint() // Logical size of the entire split file.
                header.vint() // Host attributes.
                if (attributes and 2 != 0) header.dword()
                if (attributes and 4 != 0) header.dword()
                val method = header.vint().toInt() and 0x380
                header.vint() // Host OS.
                val name = header.name(header.length(), Charsets.UTF_8)
                ensureUsenet(header.pos <= header.bytes.size - extra, BAD_HEADER)
                header.pos = header.bytes.size - extra
                var encryption: RarEncryption? = null
                while (header.pos < header.bytes.size) {
                    val recordSize = header.length()
                    val next = header.pos + recordSize
                    val type = header.vint()
                    ensureUsenet(header.pos <= next, BAD_HEADER)
                    if (type == 1L) {
                        ensureUsenet(encryption == null, BAD_HEADER)
                        encryption = header.encryption(payload = true)
                        ensureUsenet(header.pos <= next, BAD_HEADER)
                    }
                    header.pos = next
                }
                if (packed > 0 && attributes and 1 == 0) {
                    parts += RarPart(name, FileExtent(file, pos, packed), flags and 8 != 0, flags and 16 != 0,
                        method == 0, encryption != null, encryption, unpacked.takeIf { attributes and 8 == 0 })
                }
            }
            pos += packed
        }
        return parts
    }
}
