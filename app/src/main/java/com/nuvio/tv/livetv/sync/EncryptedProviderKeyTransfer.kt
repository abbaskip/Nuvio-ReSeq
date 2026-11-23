package com.nuvio.tv.livetv.sync

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.livetv.sports.SportsGeminiCredentials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/** The portable file carries ciphertext only. Version 1 pins the KDF, work factor and AEAD parameters. */
internal object ProviderKeyEnvelope {
    private val random = SecureRandom()
    private val purpose = "Nuvio ReSeq provider key backup v1".toByteArray()
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String, max: Int): ByteArray {
        require(text.length <= 2 * max)
        return Base64.decode(text, Base64.DEFAULT).also { require(it.size <= max) }
    }
    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        require(salt.size == 16 && iv.size == 12 && password.size in 1..1024)
        // SHA-1 here is the HMAC PRF in PBKDF2. This algorithm is present on Android 7 as well.
        val spec = PBEKeySpec(password, salt, 1_300_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(purpose)
            }
        } finally { key.fill(0) }
    }
    fun seal(data: JSONObject, password: CharArray): JSONObject {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val bytes = data.toString().toByteArray()
        require(bytes.size <= 96 * 1024)
        val encrypted = try { cipher(Cipher.ENCRYPT_MODE, password, salt, iv).doFinal(bytes) } finally { bytes.fill(0) }
        return JSONObject().put("format", 1).put("salt", encode(salt)).put("iv", encode(iv)).put("ciphertext", encode(encrypted))
    }
    fun open(document: JSONObject, password: CharArray): JSONObject {
        require(document.optInt("format") == 1)
        val clear = cipher(Cipher.DECRYPT_MODE, password, decode(document.getString("salt"), 16), decode(document.getString("iv"), 12))
            .doFinal(decode(document.getString("ciphertext"), 96 * 1024 + 16))
        return try { JSONObject(clear.toString(Charsets.UTF_8)) } finally { clear.fill(0) }
    }
}

internal data class ProviderTransferState(val busy: Boolean = false, val message: String? = null)

@HiltViewModel
internal class ProviderKeyTransferViewModel @Inject constructor(
    private val drive: LiveTvDriveSync, private val profiles: ProfileManager,
    private val tmdb: TmdbCredentialStore, private val gemini: SportsGeminiCredentials,
    private val debrid: DebridSettingsDataStore, private val mdb: MDBListSettingsDataStore,
    private val anime: AnimeSkipSettingsDataStore
) : ViewModel() {
    private val mutable = MutableStateFlow(ProviderTransferState())
    val state = mutable.asStateFlow()
    fun transfer(upload: Boolean, passphrase: String) {
        if (mutable.value.busy) return
        if (passphrase.length !in (if (upload) 10 else 1)..1024) {
            mutable.value = ProviderTransferState(message = if (upload) "Use a backup passphrase of at least 10 characters." else "Enter the backup passphrase.")
            return
        }
        val profile = profiles.activeProfile ?: return
        val password = passphrase.toCharArray()
        mutable.value = ProviderTransferState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            var message = "Provider keys transferred."
            try {
                drive.transferProviderKeys(profile.id) { existing ->
                    val prior = existing?.let { ProviderKeyEnvelope.open(it, password) }
                    checkProfile(profile)
                    if (prior != null && (prior.getInt("profileId") != profile.id || prior.getString("profileName") != profile.name)) {
                        throw TransferError("This backup belongs to a different Nuvio profile.")
                    }
                    val keys = prior?.getJSONObject("keys") ?: JSONObject()
                    val services = DebridProviders.all().map { it.id } + listOf("tmdb", "gemini", "mdblist", "animeskip")
                    require(keys.keys().asSequence().all { it in services && keys.get(it) is String && keys.getString(it).length in 1..8192 })
                    if (upload) {
                        val stored = debrid.providerKeysForProfile(profile.id).first().toMutableMap()
                        listOf("tmdb" to tmdb.access(profile.id).apiKey, "gemini" to gemini.read(profile.id),
                            "mdblist" to mdb.apiKeyForProfile(profile.id).first(), "animeskip" to anime.clientIdForProfile(profile.id).first()).forEach { (service, key) ->
                            if (!key.isNullOrBlank()) stored[service] = key
                        }
                        if (stored.isEmpty()) throw TransferError("This profile has no provider keys to back up.")
                        stored.forEach { (service, key) -> require(key.length <= 8192); keys.put(service, key) }
                        checkProfile(profile)
                        message = "Backed up " + stored.size + " provider keys for " + profile.name + "."
                        ProviderKeyEnvelope.seal(JSONObject().put("profileId", profile.id).put("profileName", profile.name).put("keys", keys), password)
                    } else {
                        if (existing == null) throw TransferError("No provider key backup was found for this profile.")
                        // Validate the entire authenticated document before the first idempotent write.
                        keys.keys().forEach { service ->
                            val key = keys.getString(service)
                            when (service) {
                                "tmdb" -> tmdb.save(profile.id, key)
                                "gemini" -> gemini.save(profile.id, key)
                                "mdblist" -> mdb.setApiKey(key, profile.id)
                                "animeskip" -> anime.setClientId(key, profile.id)
                                else -> debrid.setProviderApiKey(service, key, profile.id)
                            }
                        }
                        message = "Restored " + keys.length() + " provider keys for " + profile.name + "."
                        null
                    }
                }
            } catch (e: CancellationException) {
                message = "Transfer interrupted. Retry from the backup screen."
                throw e
            }
            catch (e: TransferError) { message = e.message.orEmpty() }
            catch (_: GeneralSecurityException) { message = "Could not unlock the backup. Check the passphrase and saved file." }
            catch (_: Exception) { message = "Provider key transfer failed. Check the Drive connection and backup, then retry." }
            finally { password.fill('\u0000'); mutable.value = ProviderTransferState(message = message) }
        }
    }
    private fun checkProfile(profile: UserProfile) {
        if (profiles.activeProfile?.let { it.id == profile.id && it.name == profile.name } != true) throw TransferError("Profile changed. Open the backup again.")
    }
    private class TransferError(message: String) : Exception(message)
}
