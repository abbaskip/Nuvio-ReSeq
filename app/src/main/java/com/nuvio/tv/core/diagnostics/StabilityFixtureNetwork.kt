package com.nuvio.tv.core.diagnostics

import com.nuvio.tv.BuildConfig
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import okhttp3.Interceptor

/** Test-build-only transport isolation. A deny-only local proxy prevents built-in services
 * from reaching providers. Fixture data uses adb reverse on the disposable emulator. */
object StabilityFixtureNetwork {
    fun install() {
        if (!BuildConfig.STABILITY_FIXTURES) return
        check(BuildConfig.APPLICATION_ID in setOf("io.github.abbaskip.nuvioreseq.debug", "io.github.abbaskip.nuvioreseq.iptvdebug"))
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = if (uri.host in setOf("127.0.0.1", "localhost", "::1"))
                listOf(Proxy.NO_PROXY) else listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 38793)))
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
        })
    }

    fun tvdb(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!BuildConfig.STABILITY_FIXTURES) chain.proceed(request)
        else chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http")
            .host("127.0.0.1").port(38791).encodedPath("/tvdb" + request.url.encodedPath).build()).build())
    }

    fun sports(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!BuildConfig.STABILITY_FIXTURES) chain.proceed(request)
        else chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http")
            .host("127.0.0.1").port(38791).encodedPath("/scores" + request.url.encodedPath).build()).build())
    }
}
