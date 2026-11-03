package com.nuvio.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackagedServicesTvTest {
    @Test fun backendIsConfiguredAndPersonalTmdbBuildInputIsAbsent() {
        val url = runCatching { URI(BuildConfig.SUPABASE_URL) }.getOrNull()
        assertTrue("Backend URL must be configured", url?.scheme in listOf("http", "https") && !url?.host.isNullOrBlank())
        // Retain the configured key in the harness so its finished APK can also be checked
        // before installation. Never put credential values in assertion messages.
        val digest = MessageDigest.getInstance("SHA-256").digest(BuildConfig.SUPABASE_ANON_KEY.toByteArray())
        assertTrue("Backend key must be configured", BuildConfig.SUPABASE_ANON_KEY.isNotBlank() && digest.size == 32)
        assertTrue("Personal TMDB build input must be absent", BuildConfig.TMDB_API_KEY.isBlank())
    }
}
