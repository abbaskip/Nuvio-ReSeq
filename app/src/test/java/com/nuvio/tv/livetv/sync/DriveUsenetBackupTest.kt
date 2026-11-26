package com.nuvio.tv.livetv.sync

import com.nuvio.tv.core.usenet.UsenetConfiguration
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.core.usenet.UsenetSort
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveUsenetBackupTest {
    @Test
    fun `credentials and preferences restore with the passphrase alone`() {
        val password = "river marble radar willow".toCharArray()
        val provider = UsenetProvider(name = "Provider", host = "news.example.test", username = "subscriber",
            password = "fixture-nntp-password")
        val indexer = UsenetIndexer(name = "Indexer", apiUrl = "https://indexer.example.test",
            apiKey = "fixture-indexer-api-key")
        val sources = UsenetSourceConfiguration(enabled = true, providers = listOf(provider),
            indexers = listOf(indexer), sort = UsenetSort.NEWEST)
        val profile = DriveUsenetProfile(1, "Default", sources,
            mapOf("stream_auto_play_immediate_enabled" to JsonPrimitive(true)), emptyMap())
        val setup = DriveUsenetSetup(listOf(profile), UsenetConfiguration(searchUserAgent = "Custom/1"))
        val sealed = DriveUsenetBackup.seal(setup, password)

        assertFalse(sealed.contains(provider.password))
        assertFalse(sealed.contains(indexer.apiKey))
        assertNotEquals(sealed, DriveUsenetBackup.seal(setup, password))
        val restored = DriveUsenetBackup.open(sealed, password)
        assertEquals(sources, restored.profiles.single().configuration)
        assertEquals(profile.autoplay, restored.profiles.single().autoplay)
        assertEquals(setup.playback, restored.playback)
        assertTrue(runCatching { DriveUsenetBackup.open(sealed, "a different backup passphrase".toCharArray()) }.isFailure)
        val tampered = sealed.replace(Regex("(\"ciphertext\":\")([A-Za-z])")) { match ->
            match.groupValues[1] + if (match.groupValues[2] == "A") "B" else "A"
        }
        assertNotEquals(sealed, tampered)
        assertTrue(runCatching { DriveUsenetBackup.open(tampered, password) }.isFailure)
    }
}
