package com.nuvio.tv.livetv.sync

import com.nuvio.tv.core.usenet.UsenetConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
internal class DriveUsenetProfile(
    val id: Int,
    val name: String,
    val configuration: UsenetSourceConfiguration,
    val autoplay: Map<String, JsonElement>,
    val sourceOrder: Map<String, String>
)

@Serializable
internal class DriveUsenetSetup(val profiles: List<DriveUsenetProfile>, val playback: UsenetConfiguration)

/** The Drive file contains only authenticated ciphertext. Restore uses the same passphrase on any TV. */
internal object DriveUsenetBackup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()
    private val purpose = "ReSeq Direct Usenet backup v1".encodeToByteArray()
    const val MIN_PASSPHRASE_LENGTH = 16
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12

    @Serializable
    private class Envelope(val format: Int, val salt: String, val nonce: String, val ciphertext: String)

    fun seal(payload: DriveUsenetSetup, password: CharArray): String {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val plaintext = json.encodeToString(DriveUsenetSetup.serializer(), payload).encodeToByteArray()
        val ciphertext = try { cipher(Cipher.ENCRYPT_MODE, password, salt, nonce).doFinal(plaintext) }
        finally { plaintext.fill(0) }
        return json.encodeToString(Envelope.serializer(),
            Envelope(1, Base64.encode(salt), Base64.encode(nonce), Base64.encode(ciphertext)))
    }

    fun open(document: String, password: CharArray): DriveUsenetSetup {
        val envelope = json.decodeFromString<Envelope>(document)
        require(envelope.format == 1)
        val salt = Base64.decode(envelope.salt)
        val nonce = Base64.decode(envelope.nonce)
        require(salt.size == SALT_SIZE && nonce.size == NONCE_SIZE)
        val plaintext = cipher(Cipher.DECRYPT_MODE, password, salt, nonce).doFinal(Base64.decode(envelope.ciphertext))
        return try { json.decodeFromString<DriveUsenetSetup>(plaintext.decodeToString()) }
        finally { plaintext.fill(0) }
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        require(password.size in MIN_PASSPHRASE_LENGTH..256)
        // The same algorithm is available on every supported Android version, including API 24.
        val spec = PBEKeySpec(password, salt, 1_300_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(purpose)
            }
        } finally { key.fill(0) }
    }
}
