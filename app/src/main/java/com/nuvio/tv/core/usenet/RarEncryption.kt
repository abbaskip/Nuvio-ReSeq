package com.nuvio.tv.core.usenet

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// RAR5 key schedule and CBC range layout follow the GPL-3.0 implementation in
// haaihond/NuvioTV@8f451a8 native/usenet/internal/engine/rar_crypt.go.
internal class RarEncryption(val roundsLog: Int, val salt: ByteArray, val iv: ByteArray, val verifier: ByteArray) {
    fun sameCipher(other: RarEncryption?) = other != null && roundsLog == other.roundsLog &&
        salt.contentEquals(other.salt) && iv.contentEquals(other.iv) && verifier.contentEquals(other.verifier)
}

/** Per-preparation key cache. The selected file retains just its AES key and IV. */
internal class RarPasswords(private val password: String?, private val checkActive: () -> Unit) {
    private class Derived(val key: SecretKeySpec, val verifier: ByteArray)
    private val keys = linkedMapOf<Pair<Int, List<Byte>>, Derived>()

    fun key(params: RarEncryption): SecretKeySpec {
        val text = password ?: throw UsenetFailure("This RAR needs an archive password in the NZB.")
        ensureUsenet(params.verifier.size == 12, "This RAR has no supported password check.")
        ensureUsenet(params.roundsLog in 0..20, "RAR password verification exceeds the work limit.")
        val id = params.roundsLog to params.salt.toList()
        val result = keys[id] ?: derive(text, params).also {
            if (keys.size == 8) keys.remove(keys.keys.first())
            keys[id] = it
        }
        ensureUsenet(MessageDigest.isEqual(result.verifier, params.verifier), "The NZB password does not open this RAR.")
        return result.key
    }

    private fun derive(text: String, params: RarEncryption): Derived {
        val mac = Mac.getInstance("HmacSHA256")
        val bytes = text.toByteArray(Charsets.UTF_8)
        try { mac.init(SecretKeySpec(bytes, "HmacSHA256")) } finally { bytes.fill(0) }
        mac.update(params.salt)
        val u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val sum = u.copyOf()
        fun rounds(n: Int) {
            repeat(n) { i ->
                if (i and 1023 == 0) checkActive()
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in sum.indices) sum[j] = (sum[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        try {
            rounds((1 shl params.roundsLog) - 1)
            val key = SecretKeySpec(sum, "AES")
            rounds(32)
            val check = sum.copyOf(8)
            for (i in 8 until sum.size) check[i % 8] = (check[i % 8].toInt() xor sum[i].toInt()).toByte()
            return Derived(key, check + MessageDigest.getInstance("SHA-256").digest(check).copyOf(4))
        } finally {
            u.fill(0)
            sum.fill(0)
        }
    }
}

/** Random-access CBC decryption only needs the preceding ciphertext block. */
internal class RarPlaintext(private val source: UsenetFile, private val base: Long, override val size: Long,
                            private val key: SecretKeySpec, private val iv: ByteArray) : UsenetFile {
    override val name get() = source.name

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= size) return -1
        val count = minOf(length.toLong(), size - position, 65536L).toInt()
        val skip = (position % 16).toInt()
        val start = position - skip
        val previous = if (start == 0L) 0 else 16
        val encrypted = source.bytesAt(base + start - previous, previous + ((skip + count + 15) and -16))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val vector = if (previous == 0) IvParameterSpec(iv) else IvParameterSpec(encrypted, 0, 16)
        cipher.init(Cipher.DECRYPT_MODE, key, vector)
        if (skip == 0 && encrypted.size - previous == count && cipher.getOutputSize(count) == count) {
            return cipher.doFinal(encrypted, previous, count, buffer, offset)
        }
        val plaintext = cipher.doFinal(encrypted, previous, encrypted.size - previous)
        plaintext.copyInto(buffer, offset, skip, skip + count)
        return count
    }
}
