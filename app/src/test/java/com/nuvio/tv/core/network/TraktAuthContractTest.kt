package com.nuvio.tv.core.network

import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TraktAuthContractTest {
    private val moshi = Moshi.Builder().build()

    @Test
    fun `device login refresh and logout omit the deprecated secret`() {
        assertEquals("{\"client_id\":\"client\"}",
            moshi.adapter(TraktDeviceCodeRequestDto::class.java).toJson(TraktDeviceCodeRequestDto("client")))
        assertEquals("{\"code\":\"device\",\"client_id\":\"client\"}",
            moshi.adapter(TraktDeviceTokenRequestDto::class.java).toJson(TraktDeviceTokenRequestDto("device", "client")))
        assertEquals("{\"refresh_token\":\"refresh\",\"client_id\":\"client\",\"redirect_uri\":\"redirect\",\"grant_type\":\"refresh_token\"}",
            moshi.adapter(TraktRefreshTokenRequestDto::class.java).toJson(TraktRefreshTokenRequestDto("refresh", "client", "redirect")))
        assertEquals("{\"token\":\"access\",\"client_id\":\"client\"}",
            moshi.adapter(TraktRevokeRequestDto::class.java).toJson(TraktRevokeRequestDto("access", "client")))
    }

    @Test
    fun `only official OAuth requests use the authentication host`() {
        listOf("/oauth/device/code", "/oauth/device/token", "/oauth/token", "/oauth/revoke").forEach { path ->
            assertEquals("https://auth.trakt.tv$path", "https://api.trakt.tv$path".toHttpUrl().traktEndpoint().toString())
        }
        listOf("https://api.trakt.tv/users/settings", "https://api.trakt.tv/sync/history",
            "https://api.trakt.tv/scrobble/start", "https://api.trakt.tv/oauth/unknown",
            "http://localhost:1234/oauth/token", "https://proxy.example/oauth/token").forEach { address ->
            val url = address.toHttpUrl()
            assertSame(url, url.traktEndpoint())
        }
    }
}
