package com.nuvio.tv.livetv.sports

import android.content.Context
import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.LiveTvStatus
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.player.LivePlaybackState
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SportsPageRestoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `cold page and saved matches restore while bulk workers are blocked and preview is loaded`() = runBlocking {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val context = mockk<Context> { every { filesDir } returns files; every { cacheDir } returns cache }
        val now = System.currentTimeMillis()
        val event = SportsEvent("next-nba", "basketball/nba", now + 3_600_000, true, GameState.PRE,
            "Heat at Lakers", null, "Scheduled", null, null)
        val channel = LiveChannel("espn", "fixture", "Fixture", "ESPN", null, null, null, "sports", "Sports", 1, "https://fixture.invalid")
        val match = ChannelMatch(channel, 95, "Channel name", null, "HD", false)
        SportsPageSnapshot.write(File(cache, "sports/page-snapshot.json"), listOf(event), mapOf(event.id to listOf(match)),
            now, now - 86_400_000, now + 86_400_000) { true }
        val prefs = mockk<LiveTvPreferences>(relaxed = true) {
            every { sportsSettingsJson } returns MutableStateFlow("{}")
            every { sportsMineJson } returns MutableStateFlow("{}")
            every { settings } returns MutableStateFlow(LiveTvSettings())
            every { userState } returns MutableStateFlow(LiveUserState())
        }
        val live = mockk<LiveTvRepository>(relaxed = true) {
            every { channels } returns MutableStateFlow(listOf(channel))
            every { status } returns MutableStateFlow(LiveTvStatus(loadedOnce = true))
            every { guideGeneration } returns MutableStateFlow(1)
        }
        val store = mockk<LiveTvChannelStore>(relaxed = true) {
            every { displayChannels } returns MutableStateFlow(listOf(channel))
        }
        val playback = mockk<LiveTvPlaybackController>(relaxed = true) {
            every { state } returns MutableStateFlow(LivePlaybackState(channelKey = channel.key, isPlaying = true))
        }
        val bulk = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val blocked = CountDownLatch(2)
        val release = CountDownLatch(1)
        val workers = List(2) { launch(bulk) { blocked.countDown(); release.await() } }
        blocked.await()
        mockkObject(BackgroundWork)
        every { BackgroundWork.quiet } returns bulk
        var repo: SportsRepository? = null
        try {
            val created = SportsRepository(context, prefs, live, store, mockk<EpgDatabase>(relaxed = true), playback,
                dagger.Lazy { mockk<SportsGemini>(relaxed = true) })
            repo = created
            created.start()
            delay(100)
            assertTrue(created.events.value.isEmpty()) // No app-start cache load.
            created.setViewing(true)
            withTimeout(5_000) {
                created.events.first { events -> events.any { it.id == event.id } }
                created.matches.first { it[event.id]?.any { m -> m.channel.key == channel.key } == true }
            }
            assertEquals(1L, release.count)
            assertFalse(created.backgroundAllowed.value)
            assertTrue(created.loading.value) // Full load is still waiting behind bulk work.
            created.setViewing(false)
            assertTrue(created.events.value.any { it.id == event.id })
        } finally {
            repo?.let {
                val scope = SportsRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(it) as CoroutineScope
                scope.cancel()
            }
            release.countDown()
            workers.joinAll()
            bulk.close()
            unmockkObject(BackgroundWork)
        }
    }
}
