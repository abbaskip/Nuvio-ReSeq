package com.nuvio.tv.core.runtime

import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** A stable resolver reference also updates clients copied by installed CS3 extensions. */
object ExtensionDns : Dns {
    @Volatile internal var selected = "SYSTEM"
        private set
    private val resolvers = java.util.concurrent.ConcurrentHashMap<String, Dns>()
    @Synchronized
    fun select(mode: String) {
        if (selected == mode) return
        selected = mode
        PluginRuntimeHooks.onExtensionDnsChanged()
    }
    override fun lookup(hostname: String): List<InetAddress> = resolver(selected).lookup(hostname)

    internal fun resolver(mode: String): Dns = resolvers.getOrPut(mode) {
        val (endpoint, addresses) = when (mode) {
            "CLOUDFLARE" -> "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")
            "GOOGLE" -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")
            "QUAD9" -> "https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")
            else -> return@getOrPut IPv4FirstDns()
        }
        val bootstrap = OkHttpClient.Builder().dns(Dns.SYSTEM)
            .callTimeout(15, TimeUnit.SECONDS).build()
        IPv4FirstDns(DnsOverHttps.Builder().client(bootstrap).url(endpoint.toHttpUrl())
            .bootstrapDnsHosts(addresses.map(InetAddress::getByName)).build())
    }
}
