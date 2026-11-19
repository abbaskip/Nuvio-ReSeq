package com.nuvio.tv.livetv.data

import android.app.Application
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
class PlaylistRefreshJoinTest {
    @Test fun updateNowOnOnePlaylistJoinsItsInFlightAutomaticDownload() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    started.countDown()
                    if (!release.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    return MockResponse().setBody("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",First\nhttps://fixture.test/live/a.ts\n")
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val source = PlaylistSource("overlap", "Fixture", server.url("/fixture.m3u").toString(), useEmbeddedEpg = false)
            val prefs = mockk<LiveTvPreferences>(relaxed = true)
            coEvery { prefs.currentPlaylists() } returns listOf(source)
            coEvery { prefs.currentEpgSources() } returns emptyList()
            coEvery { prefs.currentSettings() } returns LiveTvSettings()
            coEvery { prefs.currentHiddenEverywhere() } returns HiddenEverywhere(emptyList())
            every { prefs.userState } returns flowOf(LiveUserState())
            val db = EpgDatabase(context)
            try {
                val repo = LiveTvRepository(context, prefs, db, mockk(relaxed = true))
                val background = async(Dispatchers.IO) { repo.refreshInternal(false, false) }
                assertTrue(started.await(15, TimeUnit.SECONDS))
                val button = async(start = CoroutineStart.UNDISPATCHED) { repo.updatePlaylist(source) }
                release.countDown()
                background.await()
                button.await()
                assertEquals(1, server.requestCount)
                assertEquals(listOf("overlap|a|First"), repo.channels.value.map { it.key })
            } finally { release.countDown(); db.close() }
        }
    }
}
