package com.nuvio.tv.livetv.ondemand

import android.app.Application
import com.nuvio.tv.core.report.ReportRedactor
import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class M3uVodIntegrationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun reset() {
        app.deleteDatabase("livetv_on_demand.db")
        app.deleteDatabase("m3u-vod-links.db")
    }
    private inline fun withDatabase(action: (OnDemandDatabase) -> Unit) {
        val db = OnDemandDatabase(app)
        try { action(db) } finally { db.close() }
    }

    private fun preferences(sources: MutableStateFlow<List<PlaylistSource>>): LiveTvPreferences {
        val prefs = mockk<LiveTvPreferences>(relaxed = true)
        every { prefs.playlists } returns sources
        every { prefs.settings } returns flowOf(LiveTvSettings())
        every { prefs.userState } returns flowOf(LiveUserState())
        coEvery { prefs.onDemandImportTimes() } returns emptyMap()
        return prefs
    }
    private fun writePlaylist(id: String, body: String, stamp: String) {
        val dir = File(app.cacheDir, "livetv_sources/playlists").apply { mkdirs() }
        GZIPOutputStream(File(dir, "$id.m3u").outputStream()).bufferedWriter().use { it.write(body.trimIndent()) }
        File(dir, "$id.m3u.stamp").writeText(stamp)
    }

    @Test fun plainPlaylistKeepsStableResumeLinksAcrossRefreshAndReopen() = runBlocking {
        reset()
        val base = PlaylistSource("plain", "Local fixture", "http://example.test/list", importLive = false, userAgent = "fixture-agent")
        assertFalse(base.importVod)
        assertTrue(base.liveEnabled)
        val pl = base.copy(importVod = true)
        val prefs = preferences(MutableStateFlow(listOf(pl)))
        val fixture = """
            #EXTM3U
            #EXTINF:-1 group-title="Live",Channel
            http://example.test/tv/live.ts
            #EXTINF:-1 group-title="Films",Example Feature (2022)
            http://example.test/film.mp4?token=fixturePassword|Referer=https%3A%2F%2Fmedia.example.test
            #EXTINF:-1 group-title="Shows",Example Show (2020) S02E05
            http://example.test/show05.mkv
            #EXTINF:-1 group-title="Shows",Example Show (2020) S02E03
            #EXTVLCOPT:http-user-agent=episode-agent
            http://example.test/show03.mkv
        """
        writePlaylist(pl.id, fixture, "1")
        var resume = ""
        var seriesUid = ""
        val existing = VodItem("original", VodKind.MOVIE, "777", "Existing (2021)", null, "1", "mp4", null, 2021, null, 0L)
        val native = PlaylistSource("original", "Existing", "", xtreamServer = "https://example.test", importVod = true)
        val both = preferences(MutableStateFlow(listOf(pl, native)))
        withDatabase { db ->
            db.replace(native.id, existing.kind, listOf("1" to "Original")) { emit -> emit(existing); true }
            val repo = OnDemandRepository(db, both)
            repo.refresh(true, pl.id)
            assertNull(repo.status.value.message)
            assertEquals(11, db.readableDatabase.version)
            assertEquals(existing.uid, db.item(native.id, existing.kind, existing.id)?.uid)
            assertEquals(2, db.count(VodKind.MOVIE))
            val movies = repo.integratedSources(VodStreamTarget(VodKind.MOVIE, null, "Example Feature", 2022), pl.id)
            resume = movies.single().url
            assertFalse(resume.contains("fixturePassword"))
            assertFalse(resume.contains("example.test"))
            val movie = repo.resolvePortalMarker(resume)
            assertEquals("fixture-agent", movie.headers["User-Agent"])
            assertEquals("https://media.example.test", movie.headers["Referer"])
            assertFalse(ReportRedactor.clean(movie.url).contains("fixturePassword"))
            val show = db.byTitle(VodKind.SERIES, "Example Show").single()
            seriesUid = show.uid
            assertEquals(listOf(3, 5), repo.info(show)!!.episodes.map { it.episode })
            val episode = repo.integratedSources(VodStreamTarget(VodKind.SERIES, null, "Example Show", 2020,
                providerCoordinates = EpisodeCoordinates(2, 3)), pl.id).single()
            val link = repo.resolvePortalMarker(episode.url)
            assertEquals("episode-agent", link.headers["User-Agent"])
            assertEquals("http://example.test/show03.mkv", link.url)

        }
        writePlaylist(pl.id, fixture.replace("fixturePassword", "rotated").replace("show05", "show005"), "2")
        withDatabase { db ->
            val repo = OnDemandRepository(db, prefs)
            repo.refresh(true, pl.id)
            assertTrue(repo.resolvePortalMarker(resume).url.endsWith("token=rotated"))
            assertNotNull(repo.itemByUid(seriesUid))

        }
    }

    @Test fun directAndDiscoveredLoginsUseOnlyAcceptedCredentialsAndKeepNativeOrder() = runBlocking {
        reset()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val body = when (url.queryParameter("action")) {
                        null -> if (url.queryParameter("username") == "fixtureUser" && url.queryParameter("password") == "fixturePass")
                            """{"user_info":{"auth":1}}""" else """{"user_info":{"auth":0}}"""
                        "get_vod_categories", "get_series_categories" -> """[{"category_id":"1","category_name":"All"}]"""
                        "get_vod_streams" -> """[{"stream_id":17,"name":"Feature (2022)","container_extension":"mp4","category_id":"1","tmdb":"17"}]"""
                        "get_series" -> """[{"series_id":19,"name":"Series (2020)","category_id":"1","tmdb":"19"}]"""
                        "get_series_info" -> """{"info":{},"episodes":{"1":[{"id":"83","episode_num":"3","container_extension":"mkv","title":"Third"}]}}"""
                        else -> "{}"
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val origin = server.url("/").toString().trimEnd('/')
            val direct = PlaylistSource("direct", "Link", "$origin/get.php?username=fixtureUser&password=fixturePass", importVod = true)
            val short = direct.copy(id = "short", url = "$origin/short")
            val rejected = short.copy(id = "rejected", url = "$origin/rejected")
            val native = direct.copy(id = "native", url = "", xtreamServer = origin, xtreamUsername = "fixtureUser", xtreamPassword = "fixturePass")
            val nonAccountChannels = (1..400).joinToString("\n") { "#EXTINF:-1,Live $it\n$origin/station$it.ts" }
            writePlaylist(short.id, "#EXTM3U\n$nonAccountChannels\n#EXTINF:-1,Live\n$origin/live/fixtureUser/fixturePass/101.ts", "1")
            writePlaylist(rejected.id, "#EXTM3U\n#EXTINF:-1,Film (2000)\n$origin/movie/rejected/bad/4.mp4", "1")
            val sources = listOf(direct, short, rejected, native)
            val prefs = preferences(MutableStateFlow(sources))
            withDatabase { db ->
                val repo = OnDemandRepository(db, prefs)
                for (pl in sources.take(3)) {
                    repo.refresh(true, pl.id)
                    assertNull(repo.status.value.message)
                }
                for (pl in listOf(direct, short)) {
                    val item = db.item(pl.id, VodKind.MOVIE, "17")!!
                    assertEquals("$origin/movie/fixtureUser/fixturePass/17.mp4", repo.resolvePortalMarker(repo.movieUrl(item)!!).url)
                    val show = db.item(pl.id, VodKind.SERIES, "19")!!
                    val ep = repo.info(show)!!.episodes.single()
                    assertFalse(ep.url.contains("fixturePass"))
                    assertEquals("$origin/series/fixtureUser/fixturePass/83.mkv", repo.resolvePortalMarker(ep.url).url)
                }
                val activeProfile = mockk<com.nuvio.tv.core.profile.ProfileManager>()
                every { activeProfile.activeProfileId } returns MutableStateFlow(1)
                val order = mockk<IptvStreamSources>()
                coEvery { order.alternatives(native.id) } returns listOf(short, direct)
                val streams = IptvVodStreams(repo, mockk(), mockk(), prefs, activeProfile, sourceOrdering = order)
                // The native account was deliberately not imported. Its enabled M3U copies can still supply the title.
                val merged = streams.load(IptvStreamSources.PREFIX + native.id, "Native", VodStreamTarget(
                    VodKind.MOVIE, "17", "Feature", 2022), 1)
                assertEquals(1, merged.size)
                assertEquals(short.id, merged.single().iptvPlaylistId)
                assertNull(repo.m3uProviderKey(rejected))
                assertTrue(db.byTitle(VodKind.MOVIE, "Film").any { it.playlistId == rejected.id && it.id.startsWith("m3u-") })
                fun included(order: List<String>) = IptvStreamSources.uniqueM3uProviders(sources, order, emptySet()) {
                    if (it.isM3u) repo.m3uProviderKey(it) else M3uLogin.native(it)?.key()
                }.map { it.id }
                assertEquals(listOf("rejected", "native"), included(emptyList()))
                assertEquals(listOf("short", "rejected"), included(listOf(IptvStreamSources.PREFIX + short.id)))

            }
        }
    }

    @Test fun interruptedPlainImportRetainsThePlayableGeneration() = runBlocking {
        reset()
        val source = PlaylistSource("broken", "Fixture", "https://example.test/list", importVod = true)
        val prefs = preferences(MutableStateFlow(listOf(source)))
        val fixture = "#EXTM3U\n#EXTINF:-1,Kept (2022)\nhttps://example.test/old.mp4"
        writePlaylist(source.id, fixture, "original")
        withDatabase { db ->
            val repo = OnDemandRepository(db, prefs)
            repo.refresh(true, source.id)
            val old = repo.movieUrl(db.byTitle(VodKind.MOVIE, "Kept").single())!!
            val many = buildString {
                appendLine("#EXTM3U")
                for (n in 1..600) {
                    appendLine("#EXTINF:-1,Replacement $n")
                    appendLine("https://example.test/new$n.mp4")
                }
            }
            writePlaylist(source.id, many, "damaged")
            val file = File(app.cacheDir, "livetv_sources/playlists/${source.id}.m3u")
            java.io.RandomAccessFile(file, "rw").use { it.setLength(it.length() - 5) }
            repo.refresh(true, source.id)
            assertNotNull(repo.status.value.message)
            assertEquals("https://example.test/old.mp4", repo.resolvePortalMarker(old).url)
            assertEquals(1, db.count(VodKind.MOVIE))
            val signal = com.nuvio.tv.core.player.PlayerSessionTracker.livePlayback
            val before = signal.value
            try {
                signal.value = true
                db.m3u.retain(emptySet())
                assertFalse(db.m3u.current(source))
                assertTrue(db.m3u.readableDatabase.rawQuery("SELECT 1 FROM links LIMIT 1", null).use { it.moveToFirst() })
            } finally { signal.value = before }
            db.m3u.retain(emptySet())
            assertFalse(db.m3u.readableDatabase.rawQuery("SELECT 1 FROM links LIMIT 1", null).use { it.moveToFirst() })
        }
    }
}
