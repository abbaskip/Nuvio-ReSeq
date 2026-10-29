package com.nuvio.tv.core.network

import okhttp3.HttpUrl

private val traktAuthPaths = setOf("/oauth/device/code", "/oauth/device/token", "/oauth/token", "/oauth/revoke")

// Trakt now serves secret-free device authentication separately from catalogue/account requests.
internal fun HttpUrl.traktEndpoint(): HttpUrl =
    if (host == "api.trakt.tv" && encodedPath in traktAuthPaths) {
        newBuilder().host("auth.trakt.tv").build()
    } else {
        this
    }
