package com.nuvio.tv.livetv.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.ProfileSourcesFixtureEntryPoint
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.ondemand.IptvStreamSources
import com.nuvio.tv.livetv.ondemand.VodKind
import com.nuvio.tv.livetv.ondemand.VodStreamTarget
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileSourcesDeviceTest {
    @Test fun twoProfilesRetainTheirOwnAddonOrderPlaylistSelectionAndLastLink() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.abbaskip.nuvioreseq.debug", app.packageName)
        val entry = EntryPointAccessors.fromApplication(app.applicationContext, ProfileSourcesFixtureEntryPoint::class.java)
        val profiles = entry.profiles()
        val order = entry.sources()
        val prefs = entry.live()
        val links = entry.lastLinks()
        val manifests = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(request: IHTTPSession): Response {
                val id = request.uri.trim('/').substringBefore('/')
                val json = """{"id":"fixture.$id","name":"Fixture $id","version":"1.0.0","resources":["stream"],"types":["movie"],"catalogs":[]}"""
                return newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }
        }
        manifests.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            FixtureXtreamServer().use { provider ->
                val a = "http://127.0.0.1:${manifests.listeningPort}/alpha"
                val b = "http://127.0.0.1:${manifests.listeningPort}/beta"
                val native = PlaylistSource("qa-native", "Native", "", xtreamServer = provider.base,
                    xtreamUsername = "fixture", xtreamPassword = "fixture", importVod = true,
                    importLive = false, vodSeries = false)
                val plain = PlaylistSource("qa-plain", "Plain", "${provider.base}/plain.m3u", importVod = false, vodSeries = false)
                val n = IptvStreamSources.PREFIX + native.id
                val p = IptvStreamSources.PREFIX + plain.id
                val universe = setOf(a, b, n, p)
                val contentKey = "movie:profile-source-qualification"
                suspend fun switch(id: Int) {
                    profiles.setActiveProfile(id)
                    withTimeout(10_000) { profiles.activeProfileId.first { it == id } }
                }
                suspend fun known(keys: Set<String>): List<Addon> = withTimeout(60_000) {
                    order.sources().first { list -> list.map { it.baseUrl }.containsAll(keys) }
                }
                suspend fun arrange(keys: List<String>) {
                    val all = known(keys.toSet())
                    val byKey = all.associateBy { it.baseUrl }
                    order.setOrder(keys.map(byKey::getValue) + all.filterNot { it.baseUrl in keys })
                }
                suspend fun assertOrder(keys: List<String>, disabled: Set<String> = emptySet()) {
                    withTimeout(35_000) {
                        order.sources().first { all ->
                            val test = all.filter { it.baseUrl in universe }
                            test.map { it.baseUrl } == keys &&
                                test.filterNot { it.enabled }.map { it.baseUrl }.toSet() == disabled
                        }
                    }
                }
                suspend fun addManifests() {
                    for (url in listOf(a, b)) {
                        entry.addons().fetchAddon(url)
                        entry.addons().addAddon(url)
                    }
                }
                switch(1)
                prefs.updateSettings { it.copy(onDemandInStreams = true) }
                prefs.updatePlaylists { listOf(native, plain) }
                val file = File(app.cacheDir, "livetv_sources/playlists/${plain.id}.m3u")
                file.parentFile!!.mkdirs()
                file.writeText("#EXTM3U\n#EXTINF:-1 group-title=\"Fixture\",Synthetic Movie (2001)\n${provider.base}/plain.mp4\n")
                addManifests()
                arrange(listOf(a, n, b))
                assertOrder(listOf(a, n, b))
                val second = requireNotNull(profiles.createProfile("Source order fixture", "#226699"))
                switch(second.id)
                addManifests()
                arrange(listOf(b, a, n))
                assertOrder(listOf(b, a, n))

                switch(1)
                prefs.updatePlaylists { listOf(native, plain.copy(importVod = true)) }
                entry.catalogue().refresh(force = true)
                assertOrder(listOf(a, n, b, p))
                val target = VodStreamTarget(VodKind.MOVIE, "12345", "Synthetic Movie", 2001)
                val original = entry.matches().load(n, "Native", target, 1).single()
                assertEquals(native.id, original.iptvPlaylistId)
                links.save(contentKey, requireNotNull(original.url), "Native", null)

                switch(second.id)
                assertOrder(listOf(b, a, n, p))
                assertNull(links.getValid(contentKey, 60_000))
                arrange(listOf(p, b, n, a))
                order.setEnabled(n, false)
                assertOrder(listOf(p, b, n, a), setOf(n))
                assertTrue(entry.matches().load(n, "Native", target, 1).isEmpty())
                val additional = entry.matches().load(p, "Plain", target, second.id).single()
                assertEquals(plain.id, additional.iptvPlaylistId)
                links.save(contentKey, requireNotNull(additional.url), "Plain", null)

                switch(1)
                assertOrder(listOf(a, n, b, p))
                assertEquals(original.url, links.getValid(contentKey, 60_000)?.url)
                switch(second.id)
                assertOrder(listOf(p, b, n, a), setOf(n))
                assertEquals(additional.url, links.getValid(contentKey, 60_000)?.url)
                assertTrue(entry.matches().load(p, "Plain", target, 1).isEmpty())
            }
        } finally {
            manifests.stop()
        }
    }
}
