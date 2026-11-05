package com.nuvio.tv.livetv.ondemand

import android.app.Application
import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.StalkerPortalClient
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
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
class StalkerVodIntegrationTest {
    @Test fun importMatchResolveAndRecover() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_on_demand.db")
        MockWebServer().use { server ->
            var repeated = false
            var links = 0
            val calls = java.util.concurrent.CopyOnWriteArrayList<okhttp3.HttpUrl>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    calls += url
                    if (url.encodedPath.startsWith("/play/")) {
                        return if (request.getHeader("User-Agent") == StalkerPortalClient.USER_AGENT &&
                            request.getHeader("Authorization") == null && request.getHeader("Cookie") == null)
                            MockResponse().setBody("fixture media") else MockResponse().setResponseCode(403)
                    }
                    val payload: Any = when (url.queryParameter("action")) {
                        "handshake" -> JSONObject().put("token", "fixture-session")
                        "get_profile" -> JSONObject().put("status", 0)
                        "get_categories" -> JSONArray("""[{"id":"a","title":"Included"},{"id":"b","title":"Excluded"}]""")
                        "get_ordered_list" -> {
                            val page = url.queryParameter("p")!!.toInt()
                            val rows = JSONArray()
                            var total = 1
                            if (url.queryParameter("movie_id") == "show") {
                                rows.put(JSONObject("""{"id":"season-2","name":"Season 2","series":["3",5],"cmd":"season command"}"""))
                            } else if (url.queryParameter("type") == "series") {
                                rows.put(JSONObject("""{"id":"show","name":"Show","tmdb_id":123,"category_id":"a"}"""))
                            } else {
                                val search = url.queryParameter("search")
                                total = if (search == null) 1101 else 2
                                val first = if (repeated) 1 else (page - 1) * 500 + 1
                                for (index in first..minOf(first + 499, total)) {
                                    val id = if (repeated) "new-$index" else index.toString()
                                    val title = if (index == 2 || search != null) "Unique Feature" else "Movie $id"
                                    rows.put(JSONObject().put("id", id).put("name", title).put("cmd", "movie $id")
                                        .put("screenshot_uri", "/cover.jpg").put("category_id", "a"))
                                }
                            }
                            JSONObject().put("total_items", total).put("data", rows)
                        }
                        "create_link" -> JSONObject().put("cmd", "ffmpeg http://media.example.test:${server.port}/play/${++links}")
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(JSONObject().put("js", payload).toString())
                }
            }
            server.start()
            val pl = PlaylistSource("p", "Portal", "", stalkerPortal = server.url("/c/").toString(),
                stalkerMac = "00:1A:79:00:00:01", importLive = false, importVod = true,
                vodSourceOnly = true, vodMovieCategories = setOf("a"))
            val playlists = MutableStateFlow(listOf(pl))
            val prefs = mockk<LiveTvPreferences>(relaxed = true)
            every { prefs.playlists } returns playlists
            every { prefs.settings } returns flowOf(LiveTvSettings())
            every { prefs.userState } returns flowOf(LiveUserState())
            coEvery { prefs.onDemandImportTimes() } returns emptyMap()
            val db = OnDemandDatabase(context)
            try {
                val repo = OnDemandRepository(db, prefs, StalkerPortalClient(OkHttpClient(), { 0L }, Dispatchers.IO))
                repo.refresh(force = true)
                assertNull(repo.status.value.message)
                assertEquals(1101, db.count(VodKind.MOVIE))
                assertFalse(pl.liveEnabled)
                assertTrue(calls.none { it.queryParameter("action") == "create_link" || it.queryParameter("category") == "b" })
                val movie = db.item("p", VodKind.MOVIE, "2")!!
                assertNull(movie.icon)
                val search = OnDemandLibrarySearch(repo, prefs)
                val query = search.request("Unique Feature", VodKind.MOVIE)
                assertEquals(movie.uid, search.page(query, query.playlists.single(), 0).items.single().uid)
                repo.requestArtwork(listOf(movie))
                kotlinx.coroutines.withTimeout(10_000) {
                    while (db.item("p", VodKind.MOVIE, "2")?.icon == null) kotlinx.coroutines.delay(20)
                }
                val ref = repo.movieUrl(movie)!!
                assertEquals("http://media.example.test:${server.port}/play/1", repo.resolvePortalMarker(ref).url)
                assertEquals("http://media.example.test:${server.port}/play/2", repo.resolvePortalMarker(ref).url)
                assertEquals("movie 2", calls.last().queryParameter("cmd"))
                val target = VodStreamTarget(VodKind.SERIES, "123", "Show", null, providerCoordinates = EpisodeCoordinates(2, 5))
                assertTrue(repo.integratedSources(target.copy(providerCoordinates = null), "p").isEmpty())
                val episode = repo.integratedSources(target, "p").single()
                val resolved = repo.resolvePortalMarker(episode.url)
                assertEquals("vod", calls.last().queryParameter("type"))
                assertEquals("season command", calls.last().queryParameter("cmd"))
                assertEquals("5", calls.last().queryParameter("series"))
                assertEquals(setOf("User-Agent"), resolved.headers.keys)
                val mediaClient = OkHttpClient.Builder().dns { listOf(java.net.InetAddress.getLoopbackAddress()) }.build()
                val mediaRequest = okhttp3.Request.Builder().url(resolved.url)
                resolved.headers.forEach { (name, value) -> mediaRequest.header(name, value) }
                mediaClient.newCall(mediaRequest.build()).execute().use { assertEquals("fixture media", it.body.string()) }
                repeated = true
                repo.refresh(force = true)
                assertNotNull(repo.status.value.message)
                assertNotNull(db.item("p", VodKind.MOVIE, "new-500"))
                assertNotNull(db.item("p", VodKind.MOVIE, "2"))
                playlists.value = listOf(pl.copy(vodMovies = false))
                repo.refresh(force = true)
                assertEquals(0, db.count(VodKind.MOVIE))
                assertTrue(runCatching { repo.resolvePortalMarker(ref) }.isFailure)
            } finally { db.close() }
        }
    }
}
