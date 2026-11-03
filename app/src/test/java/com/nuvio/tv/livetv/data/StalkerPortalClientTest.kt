package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.PlaylistSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLEncoder

class StalkerPortalClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun source(server: MockWebServer) = PlaylistSource("test-playlist", "Test", "",
        stalkerPortal = server.url("/stalker_portal/c/").toString(), stalkerMac = "00:1A:79:00:00:01")
    private fun body(server: MockWebServer, value: String) { server.enqueue(MockResponse().setBody("{\"js\":$value}")) }
    private fun login(server: MockWebServer, token: String = "synthetic-token") {
        body(server, "{\"token\":\"$token\"}"); body(server, "{\"id\":42}")
    }
    private fun marker(cmd: String = "ffmpeg http://localhost/ch/42_") = "reseq-stalker://test-playlist/42?cmd=" + URLEncoder.encode(cmd, "UTF-8")

    @Test fun `incomplete link retries a preencoded command without forcing storage zero`() = runBlocking {
        MockWebServer().use { server ->
            login(server)
            body(server, "{\"cmd\":\"http://media.example.test/play/live.php?stream=&play_token=synthetic\"}")
            body(server, "{\"cmd\":\"ffmpeg http://media.example.test/play/live.php?stream=42&play_token=synthetic\"}")
            val cmd = "ffmpeg%20http%3A%2F%2Flocalhost%2Fch%2F42_%3Fx%3D1%26type%3Dvod%2Btest"
            val link = StalkerPortalClient(OkHttpClient(), { 0L }).resolve(source(server), marker(cmd))
            assertTrue(link.url.contains("stream=42&"))
            server.takeRequest(); server.takeRequest()
            val first = server.takeRequest().requestUrl!!
            assertEquals(cmd, first.queryParameter("cmd"))
            assertEquals("0", first.queryParameter("forced_storage"))
            val retry = server.takeRequest().requestUrl!!
            assertEquals("ffmpeg http://localhost/ch/42_?x=1&type=vod+test", retry.queryParameter("cmd"))
            assertEquals(listOf("itv"), retry.queryParameterValues("type"))
            assertNull(retry.queryParameter("forced_storage"))
            assertFalse(link.headers.containsKey("Cookie"))
            assertFalse(link.headers.containsKey("Authorization"))
        }
    }

    @Test fun `empty stream identifiers never reach the player and errors contain no credentials`() = runBlocking {
        MockWebServer().use { server ->
            login(server)
            repeat(2) { body(server, "{\"cmd\":\"http://media.example.test/play/live.php?stream=&play_token=private-token\"}") }
            val failure = runCatching { StalkerPortalClient(OkHttpClient(), { 0L }).resolve(source(server), marker()) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure!!.message.orEmpty().contains("private-token"))
            assertFalse(failure.message.orEmpty().contains(source(server).stalkerMac))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun `explicit static flags and alternate command fields survive playlist import`() = runBlocking {
        MockWebServer().use { server ->
            login(server); body(server, "[]")
            body(server, """{"data":[{"id":"42","name":"Static","cmd":"","mc_cmd":"auto https://media.example.test/live/42.ts?stream=42","use_http_tmp_link":"0"}]}""")
            val client = StalkerPortalClient(OkHttpClient(), { 0L })
            val target = temporary.newFile()
            target.writeText("#EXTM3U\n#EXTINF:-1,Old\n${marker()}\n")
            assertTrue(client.needsPlaylistRefresh(target))
            client.writePlaylist(source(server), target)
            assertFalse(client.needsPlaylistRefresh(target))
            val channel = target.bufferedReader().use { M3uParser.parse(it) }.entries.single()
            assertTrue(channel.url.contains("resolve=0"))
            assertEquals("https://media.example.test/live/42.ts?stream=42", client.resolve(source(server), channel.url).url)
            val encoded = URLEncoder.encode("auto https://media.example.test/live/42.ts?stream=42", "UTF-8")
            assertEquals("https://media.example.test/live/42.ts?stream=42", client.resolve(source(server), marker(encoded) + "&resolve=0").url)
            // Already valid URLs retain escapes in paths and literal '+' in tokens.
            val escaped = "https://media.example.test/a%2Fb.ts?stream=42&token=a+b"
            assertEquals(escaped, client.resolve(source(server), marker(escaped) + "&resolve=0").url)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun `secondary temporary flags prevent direct playback even when primary flag is zero`() = runBlocking {
        for (flag in listOf("use_load_balancing", "wowza_tmp_link", "flussonic_tmp_link")) {
            MockWebServer().use { server ->
                login(server); body(server, "[]")
                body(server, """{"data":[{"id":"42","name":"Temporary","cmd":"http://media.example.test/old.ts","use_http_tmp_link":"0","$flag":"1"}]}""")
                body(server, "{\"cmd\":\"https://media.example.test/fresh.ts\"}")
                val client = StalkerPortalClient(OkHttpClient(), { 0L })
                val target = temporary.newFile()
                client.writePlaylist(source(server), target)
                val channel = target.bufferedReader().use { M3uParser.parse(it) }.entries.single()
                assertTrue(channel.url.contains("resolve=1"))
                assertEquals("https://media.example.test/fresh.ts", client.resolve(source(server), channel.url).url)
                assertEquals(5, server.requestCount)
            }
        }
    }

    @Test fun `temporary link flags override an otherwise playable command`() = runBlocking {
        MockWebServer().use { server ->
            login(server); body(server, "[]")
            body(server, """{"data":[{"id":"42","name":"Temporary","url":"http://media.example.test/old.ts","use_http_tmp_link":1,"use_load_balancing":0}]}""")
            body(server, "{\"stream_url\":\"ffrt https://media.example.test/fresh.ts\"}")
            val client = StalkerPortalClient(OkHttpClient(), { 0L })
            val target = temporary.newFile()
            client.writePlaylist(source(server), target)
            val channel = target.bufferedReader().use { M3uParser.parse(it) }.entries.single()
            assertTrue(channel.url.contains("resolve=1"))
            assertEquals("https://media.example.test/fresh.ts", client.resolve(source(server), channel.url).url)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun `old cached commands still request a fresh playback link`() = runBlocking {
        MockWebServer().use { server ->
            login(server); body(server, "{\"cmd\":\"https://media.example.test/fresh.ts\"}")
            val link = StalkerPortalClient(OkHttpClient(), { 0L }).resolve(source(server), marker("https://media.example.test/old.ts"))
            assertEquals("https://media.example.test/fresh.ts", link.url)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `loopback and empty stream responses cannot bypass resolution as static channels`() = runBlocking {
        for (cmd in listOf("http://127.0.0.2/live.ts", "http://localhost./live.ts", "http://media.example.test/ch/42_", "http://media.example.test/live.php?stream=")) {
            MockWebServer().use { server ->
                login(server); body(server, "{\"cmd\":\"https://media.example.test/fresh.ts\"}")
                val link = StalkerPortalClient(OkHttpClient(), { 0L }).resolve(source(server), marker(cmd) + "&resolve=0")
                assertEquals("https://media.example.test/fresh.ts", link.url)
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun `portal php fallback stays on the configured origin and is reused`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404)); login(server)
            body(server, "{\"cmd\":\"https://media.example.test/live.ts\"}")
            StalkerPortalClient(OkHttpClient(), { 0L }).resolve(source(server), marker())
            assertEquals("/stalker_portal/server/load.php", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/stalker_portal/portal.php", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/stalker_portal/portal.php", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/stalker_portal/portal.php", server.takeRequest().requestUrl!!.encodedPath)
        }
    }
    @Test fun `login required profile completes the documented second authentication step`() = runBlocking {
        MockWebServer().use { server ->
            body(server, "{\"token\":\"synthetic\"}"); body(server, "{\"status\":2}"); body(server, "true")
            body(server, "{\"id\":42}"); body(server, "{\"cmd\":\"https://media.example.test/live.ts\"}")
            val account = source(server).copy(stalkerUsername = "synthetic-user", stalkerPassword = "synthetic-password")
            assertEquals("https://media.example.test/live.ts", StalkerPortalClient(OkHttpClient(), { 0L }).resolve(account, marker()).url)
            server.takeRequest()
            assertEquals("0", server.takeRequest().requestUrl!!.queryParameter("auth_second_step"))
            val auth = server.takeRequest().requestUrl!!
            assertEquals("do_auth", auth.queryParameter("action")); assertEquals("synthetic-user", auth.queryParameter("login"))
            val profile = server.takeRequest().requestUrl!!
            assertEquals("1", profile.queryParameter("auth_second_step")); assertEquals("MAG250", profile.queryParameter("stb_type"))
        }
    }
    @Test fun `blocked profiles cannot continue to channel or playback requests`() = runBlocking {
        MockWebServer().use { server ->
            body(server, "{\"token\":\"synthetic\"}"); body(server, "{\"status\":1}")
            assertTrue(runCatching { StalkerPortalClient(OkHttpClient(), { 0L }).resolve(source(server), marker()) }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `portal redirects cannot forward the MAC or bearer to another host`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example.test/portal.php"))
            val safeClient = OkHttpClient.Builder().followRedirects(false).build()
            assertTrue(runCatching { StalkerPortalClient(safeClient, { 0L }).resolve(source(server), marker()) }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `portal paths and explicit endpoints are preserved without credentials`() {
        assertEquals("https://example.test/stalker_portal/server/load.php", StalkerPortalClient.endpoint("https://example.test/stalker_portal/c/index.html"))
        assertEquals("https://example.test/portal.php", StalkerPortalClient.endpoint("https://example.test/portal.php"))
        assertNotNull(StalkerPortalClient.validate("https://user:password@example.test/c/", "00:1A:79:00:00:01"))
        assertNotNull(StalkerPortalClient.validate("https://example.test/c/", "not-a-mac"))
    }

    @Test fun `first channel import and guide create cache directories and share stable identities`() = runBlocking {
        MockWebServer().use { server ->
            login(server)
            body(server, "[{\"id\":\"2\",\"title\":\"Sport\"}]")
            body(server, "{\"data\":[{\"id\":\"42\",\"name\":\"Seven & Mate\",\"tv_genre_id\":\"2\",\"cmd\":\"ffmpeg http://localhost/ch/42_\"}]}")
            body(server, "{\"42\":[{\"name\":\"England & PNG\",\"descr\":\"Live <sport>\",\"start_timestamp\":1793000000,\"stop_timestamp\":1793003600}]}")
            val client = StalkerPortalClient(OkHttpClient(), { 0L })
            val playlist = temporary.root.resolve("livetv/playlists/test-playlist.m3u")
            val guide = temporary.root.resolve("livetv/epg/test-guide.xml")
            assertFalse(playlist.parentFile!!.exists())
            assertFalse(guide.parentFile!!.exists())
            client.writePlaylist(source(server), playlist); client.writeGuide(source(server), guide)
            val channel = playlist.bufferedReader().use { M3uParser.parse(it) }.entries.single()
            assertEquals("Sport", channel.group)
            assertEquals("stalker:test-playlist:42", channel.tvgId)
            assertTrue(channel.url.startsWith("reseq-stalker://test-playlist/42?cmd="))
            assertFalse(playlist.readText().contains("synthetic-token"))
            assertTrue(guide.readText().contains("England &amp; PNG"))
            assertTrue(guide.readText().contains("stalker:test-playlist:42"))
            assertFalse(playlist.resolveSibling(playlist.name + ".tmp").exists())
            assertFalse(guide.resolveSibling(guide.name + ".tmp").exists())
            assertEquals(5, server.requestCount)
            assertNull(server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer synthetic-token", server.takeRequest().getHeader("Authorization"))
            assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("mac=00%3A1A%3A79%3A00%3A00%3A01"))
        }
    }

    @Test fun `session expiry renews once and player receives no portal bearer token`() = runBlocking {
        MockWebServer().use { server ->
            login(server); server.enqueue(MockResponse().setResponseCode(401)); login(server, "renewed")
            body(server, "{\"cmd\":\"ffmpeg https://media.example.test/live/42.ts?playback=synthetic\"}")
            val link = StalkerPortalClient(OkHttpClient(), { 0L }).resolve(source(server), marker())
            assertTrue(link.url.startsWith("https://media.example.test/"))
            assertFalse(link.headers.containsKey("Authorization"))
            assertFalse(link.headers.containsKey("Cookie"))
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun `changed account cannot inherit a previous session`() = runBlocking {
        MockWebServer().use { server ->
            login(server); body(server, "{\"cmd\":\"https://media.example.test/a.ts\"}")
            login(server); body(server, "{\"cmd\":\"https://media.example.test/b.ts\"}")
            val client = StalkerPortalClient(OkHttpClient(), { 0L })
            client.resolve(source(server), marker())
            client.resolve(source(server).copy(stalkerMac = "00:1A:79:00:00:02"), marker())
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun `mismatched playlist is rejected before any request`() = runBlocking {
        MockWebServer().use { server ->
            assertTrue(runCatching { StalkerPortalClient(OkHttpClient(), { 0L }).resolve(source(server), marker().replace("test-playlist", "other")) }.isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `invalid channel response preserves the previous cached playlist`() = runBlocking {
        MockWebServer().use { server ->
            login(server); body(server, "[]"); body(server, "{\"error\":\"unavailable\"}")
            val target = temporary.newFile().apply { writeText("previous channels") }
            assertTrue(runCatching { StalkerPortalClient(OkHttpClient(), { 0L }).writePlaylist(source(server), target) }.isFailure)
            assertEquals("previous channels", target.readText())
        }
    }
    @Test fun `first bulk guide creates cache directories and retains every nested listing`() = runBlocking {
        MockWebServer().use { server ->
            login(server)
            val programme = "{\"name\":\"England & PNG\",\"start_timestamp\":1793000000,\"stop_timestamp\":1793003600}"
            body(server, "{\"data\":{\"42\":[" + List(20_000) { programme }.joinToString(",") + "]}}")
            val target = temporary.root.resolve("livetv/epg/test-guide.xml")
            assertFalse(target.parentFile!!.exists())
            StalkerPortalClient(OkHttpClient(), { 0L }).writeGuide(source(server), target)
            assertEquals(20_000, target.useLines { lines -> lines.count { it.startsWith("<programme ") } })
            assertTrue(target.useLines { lines -> lines.any { "England &amp; PNG" in it } })
        }
    }

    @Test fun `truncated guide and empty responses retain the previous cache`() = runBlocking {
        MockWebServer().use { server ->
            login(server)
            server.enqueue(MockResponse().setBody("{\"js\":{\"42\":[{\"name\":\"partial"))
            body(server, "{}")
            val client = StalkerPortalClient(OkHttpClient(), { 0L })
            val target = temporary.newFile().apply { writeText("previous guide") }
            repeat(2) {
                assertTrue(runCatching { client.writeGuide(source(server), target) }.isFailure)
                assertEquals("previous guide", target.readText())
            }
        }
    }

    @Test fun `direct channel arrays are imported without dropping channel alternatives`() = runBlocking {
        MockWebServer().use { server ->
            login(server); body(server, "[]")
            body(server, "[" + (1..2_000).joinToString(",") {
                "{\"id\":\"$it\",\"name\":\"Channel $it\",\"cmd\":\"ffmpeg http://localhost/ch/$it\"}"
            } + "]")
            val target = temporary.newFile()
            StalkerPortalClient(OkHttpClient(), { 0L }).writePlaylist(source(server), target)
            assertEquals(2_000, target.useLines { lines -> lines.count { it.startsWith("#EXTINF") } })
        }
    }

    @Test fun `a slow guide can be cancelled without delaying a playback link`() = runBlocking {
        MockWebServer().use { server ->
            login(server)
            val programmes = List(2_000) { "{\"name\":\"Synthetic\",\"start_timestamp\":1793000000,\"stop_timestamp\":1793003600}" }.joinToString(",")
            server.enqueue(MockResponse().setBody("{\"js\":{\"42\":[$programmes]}}")
                .throttleBody(128, 100, java.util.concurrent.TimeUnit.MILLISECONDS))
            body(server, "{\"cmd\":\"https://media.example.test/live.ts\"}")
            val client = StalkerPortalClient(OkHttpClient(), { 0L })
            val target = temporary.newFile().apply { writeText("previous guide") }
            val guide = async(kotlinx.coroutines.Dispatchers.Default) { client.writeGuide(source(server), target) }
            repeat(3) { assertNotNull(server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)) }
            val link = kotlinx.coroutines.withTimeout(3_000) { client.resolve(source(server), marker()) }
            assertEquals("https://media.example.test/live.ts", link.url)
            kotlinx.coroutines.withTimeout(3_000) { guide.cancel(); guide.join() }
            assertEquals("previous guide", target.readText())
        }
    }

}
