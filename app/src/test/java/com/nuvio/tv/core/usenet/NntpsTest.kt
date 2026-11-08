package com.nuvio.tv.core.usenet

import java.io.File
import java.net.InetAddress
import java.net.SocketException
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NntpsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun tlsChecksCertificateTrustAndProviderHostnameBeforeSendingCredentials() {
        // Generate an ephemeral host-only identity for this test. No private key enters the APK or repository.
        val store = File(temp.root, "localhost.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (File.separatorChar == '\\') ".exe" else "")
        val generate = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "test", "-keyalg", "EC",
            "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost", "-validity", "1",
            "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "fixture").redirectErrorStream(true).start()
        generate.inputStream.use { it.readBytes() }
        assertEquals(0, generate.waitFor())
        val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "fixture".toCharArray()) } }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture".toCharArray()) }
        val trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val client = SSLContext.getInstance("TLS").apply { init(null, trusted.trustManagers, null) }.socketFactory
        val context = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
        val logins = AtomicInteger()
        context.serverSocketFactory.createServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { listener ->
            val server = thread(isDaemon = true) {
                repeat(3) {
                    val peer = try { listener.accept() } catch (_: SocketException) { return@thread }
                    try {
                        peer.use { socket ->
                            val output = socket.getOutputStream()
                            val input = socket.getInputStream().bufferedReader()
                            fun send(s: String) = output.write((s + "\r\n").toByteArray(Charsets.ISO_8859_1))
                            send("200 fixture")
                            if (input.readLine() == "AUTHINFO USER test") {
                                logins.incrementAndGet()
                                send("281 accepted")
                                if (input.readLine() == "BODY <one>") {
                                    send("222 body")
                                    send("=ybegin line=128 size=1 name=one")
                                    send("+")
                                    send("=yend size=1")
                                    send(".")
                                }
                            }
                        }
                    } catch (_: SSLException) {
                        // Clients with an untrusted certificate or hostname mismatch terminate the handshake.
                    } catch (_: SocketException) { }
                }
            }
            val port = listener.localPort
            val local = listOf("nntps://test@localhost:$port/1")
            assertThrows(UsenetFailure::class.java) { NewsArticles(local).use { it.fetch("one") } }
            assertThrows(UsenetFailure::class.java) {
                NewsArticles(listOf("nntps://test@127.0.0.1:$port/1"), client).use { it.fetch("one") }
            }
            val article = NewsArticles(local, client).use { it.fetch("one") }
            assertArrayEquals(byteArrayOf(1), article.bytes)
            server.join(3000)
            assertEquals(1, logins.get())
        }
    }
}
