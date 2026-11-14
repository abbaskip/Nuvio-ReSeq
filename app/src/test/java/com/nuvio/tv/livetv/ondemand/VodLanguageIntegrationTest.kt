package com.nuvio.tv.livetv.ondemand

import android.app.Application
import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import io.mockk.every
import io.mockk.mockk
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
@Config(manifest = Config.NONE, sdk = [28], application = Application::class, qualifiers = "en-rAU")
@ConscryptMode(ConscryptMode.Mode.OFF)
class VodLanguageIntegrationTest {
    @Test fun `indexed episode lookup inherits folders ranks English and exposes hints to manual and autoplay selection`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_on_demand.db")
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val id = request.requestUrl!!.queryParameter("series_id")!!
                    val audio = when (id) {
                        "4" -> """, "info":{"audio":{"tags":{"language":"deu"}}}"""
                        "2" -> """, "info":{"video":{"height":720,"codec_name":"h264"}}"""
                        "6" -> """, "info":{"video":{"height":1080,"codec_name":"hevc"},"audio":{"channel_layout":"5.1(side)"},"bitrate":2000,"duration_secs":1800}"""
                        else -> ""
                    }
                    return MockResponse().setBody("""{"info":{"tmdb_id":"123"},"episodes":{"1":[
                        {"id":"$id","title":"Episode S01E01","season":1,"episode_num":1,"container_extension":"mp4"$audio}
                    ]}}""")
                }
            }
            server.start()
            val playlist = PlaylistSource("p", "Fixture", "", xtreamServer = server.url("/").toString(), importVod = true)
            val prefs = mockk<LiveTvPreferences>()
            every { prefs.playlists } returns flowOf(listOf(playlist))
            every { prefs.settings } returns flowOf(LiveTvSettings())
            every { prefs.userState } returns flowOf(LiveUserState())
            val db = OnDemandDatabase(context)
            try {
                val folders = listOf("1" to "DE | Cooking", "2" to "English Series", "3" to "Cooking",
                    "4" to "English Series", "5" to "FR | Cuisine", "6" to "English Series 1080p HEVC 5.1")
                db.replace("p", VodKind.SERIES, folders) { emit ->
                    folders.forEach { (id, _) -> emit(VodItem("p", VodKind.SERIES, id,
                        "Jamie's 30 Minute Meals", null, id, null, "123", 2010, null, 0)) }
                    true
                }
                val repo = OnDemandRepository(db, prefs)
                val target = VodStreamTarget(VodKind.SERIES, "123", "Jamie's 30 Minute Meals", 2010,
                    providerCoordinates = EpisodeCoordinates(1, 1))
                val sources = repo.integratedSources(target, "p")
                assertEquals(6, sources.size)
                assertEquals("6.mp4", sources[0].url.substringAfterLast('/'))
                assertEquals("2.mp4", sources[1].url.substringAfterLast('/'))
                assertEquals("3.mp4", sources[2].url.substringAfterLast('/'))
                assertEquals(6, sources[0].tech?.channels)
                assertEquals(setOf("de"), sources.single { it.url.endsWith("4.mp4") }.languages)
                val streams = sources.map { OnDemandStreams.stream(it, "IPTV Fixture") }
                assertTrue(streams.first().title!!.contains("English"))
                assertTrue(streams.first().description!!.contains("English Series"))
                val selected = com.nuvio.tv.core.player.StreamAutoPlaySelector.selectBingeGroupMatch(streams, "iptv-playlist:p")
                assertEquals(streams.first(), selected)
                assertEquals("6", repo.versions(db.item("p", VodKind.SERIES, "1")!!).first().item.id)
                RuntimeEnvironment.setQualifiers("fr-rFR")
                assertEquals("fr", OnDemandRepository.systemLanguage().language)
                val french = repo.integratedSources(target, "p")
                assertTrue(french.first().url.endsWith("5.mp4"))
                assertTrue(french[1].url.endsWith("6.mp4"))
                assertEquals("Sorting must not fetch or probe additional media", 6, server.requestCount)
            } finally { db.close() }
        }
    }
}
