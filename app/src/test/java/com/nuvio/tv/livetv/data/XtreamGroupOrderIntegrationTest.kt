package com.nuvio.tv.livetv.data

import android.app.Application
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class XtreamGroupOrderIntegrationTest {
    @Test fun importRefreshRestoreAndFallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = PlaylistSource("p.m3u.fixture", "Provider", "", useEmbeddedEpg = false,
                xtreamServer = server.url("/").toString(), xtreamUsername = "fixture", xtreamPassword = "fixture")
            val context = RuntimeEnvironment.getApplication()
            File(context.filesDir, "livetv").mkdirs()
            val prefs = mockk<LiveTvPreferences>(relaxed = true)
            coEvery { prefs.currentPlaylists() } returns listOf(source)
            coEvery { prefs.currentSettings() } returns LiveTvSettings()
            coEvery { prefs.currentHiddenEverywhere() } returns HiddenEverywhere(emptyList())
            every { prefs.userState } returns flowOf(LiveUserState())
            val database = EpgDatabase(context)
            fun repository() = LiveTvRepository(context, prefs, database, mockk(relaxed = true))
            val repo = repository()
            val news = """{"category_id":"2","category_name":" World \"News\"\r\nHD;extra "}"""
            val sport = """{"category_id":"7","category_name":"Sport"}"""
            val streams = """[{"stream_id":1,"name":"One","category_id":"2"},{"stream_id":2,"name":"Two","category_id":"7"}]"""
            suspend fun refresh(categories: MockResponse) {
                server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1}}"""))
                server.enqueue(categories)
                server.enqueue(MockResponse().setBody(streams))
                repo.refreshInternal(forcePlaylists = true, forceEpg = false)
            }
            val channelGroups = listOf("World 'News' HD", "Sport")
            try {
                refresh(MockResponse().setBody("[$sport,$news]"))
                assertEquals(channelGroups, repo.channels.value.map { it.group })
                assertEquals(mapOf("Sport" to 0, channelGroups.first() to 1), repo.providerGroupRanks.value)
                val cachedPlaylist = File(context.cacheDir, "livetv_sources/playlists/${source.id}.m3u")
                assertTrue(cachedPlaylist.setLastModified(System.currentTimeMillis() - 15L * 24 * 3_600_000))
                repo.maintainStorage()
                assertTrue(cachedPlaylist.exists())
                val restarted = repository()
                assertTrue(restarted.loadSavedChannels())
                assertEquals(repo.providerGroupRanks.value, restarted.providerGroupRanks.value)
                assertEquals(3, server.requestCount)

                refresh(MockResponse().setBody("[$news,$sport]"))
                assertEquals(channelGroups, repo.channels.value.map { it.group })
                assertEquals(0, repo.providerGroupRanks.value[channelGroups.first()])
                refresh(MockResponse().setResponseCode(503))
                assertEquals(channelGroups, repo.channels.value.map { it.group })
                assertEquals(0, repo.providerGroupRanks.value[channelGroups.first()])

                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setHeader("ETag", "\"fallback-1\"").setBody("#EXTM3U\n#EXTINF:-1 group-title=\"Other\",Other\nhttp://example.test/live/1\n"))
                repo.refreshInternal(forcePlaylists = true, forceEpg = false)
                assertEquals(listOf("Other"), repo.channels.value.map { it.group })
                assertTrue(repo.providerGroupRanks.value.isEmpty())
                val validators = File(context.cacheDir, "livetv_sources/playlists/${source.id}.m3u.meta")
                assertTrue(validators.exists())
                refresh(MockResponse().setBody("[$sport,$news]"))
                assertEquals(0, repo.providerGroupRanks.value["Sport"])
                assertFalse("get.php validators only describe the downloaded M3U", validators.exists())
            } finally {
                database.close()
            }
        }
    }
}
