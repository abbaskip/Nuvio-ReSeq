package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.StalkerPortalClient
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.PlaylistSource
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvPortalReconnectTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = PlaylistSource("portal", "Portal", "", stalkerPortal = "https://portal.example.test/c/", stalkerMac = "00:1A:79:00:00:01")
    private val channel = LiveChannel("channel", source.id, "Portal", "Test", null, null, null, "group", "Group", 1,
        "reseq-stalker://portal/42?cmd=cached&resolve=0")

    private class Fixture(val controller: LiveTvPlaybackController, val stalker: StalkerPortalClient, val opened: List<String>) {
        fun close() {
            (field(controller, "scope") as CoroutineScope).cancel()
            unmockkObject(controller)
        }
    }

    private fun fixture(): Fixture {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns temporary.root
        val preferences = mockk<LiveTvPreferences>()
        every { preferences.settings } returns emptyFlow()
        every { preferences.userState } returns emptyFlow()
        every { preferences.playlists } returns flowOf(listOf(source))
        val stalker = mockk<StalkerPortalClient>()
        val controller = LiveTvPlaybackController(context, stalker, preferences)
        // Keep the real instance: copying it into a spy leaves its listener bound to
        // the original object and makes lifecycle callbacks mutate different fields.
        mockkObject(controller, recordPrivateCalls = true)
        val opened = mutableListOf<String>()
        val mediaSource = mockk<MediaSource>()
        every { controller["buildMediaSource"](any<String>(), any<Map<String, String>>(), any<Boolean>()) } answers {
            opened += firstArg<String>()
            mediaSource
        }
        setField(controller, "_player", mockk<ExoPlayer>(relaxed = true))
        setField(controller, "currentChannel", channel)
        setField(controller, "currentUrl", "https://media.example.test/old.ts")
        return Fixture(controller, stalker, opened)
    }

    private fun refuse(controller: LiveTvPlaybackController) {
        val spec = DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build()
        val cause = HttpDataSource.InvalidResponseCodeException(460, "Synthetic refusal", null, emptyMap(), spec, byteArrayOf())
        val error = PlaybackException("Synthetic refusal", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        reconnect(controller, error)
    }

    private fun reconnect(controller: LiveTvPlaybackController, error: PlaybackException? = null) {
        LiveTvPlaybackController::class.java.getDeclaredMethod("scheduleReconnect", String::class.java, PlaybackException::class.java)
            .apply { isAccessible = true }.invoke(controller, "Synthetic failure", error)
    }

    @Test fun `460 replaces the stale URL once and a second refusal stops without resetting retry count`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture()
        try {
            coEvery { f.stalker.resolve(source, channel.url, true) } returns StalkerPortalClient.Link("https://media.example.test/fresh.ts", emptyMap())
            refuse(f.controller)
            advanceUntilIdle()
            assertEquals(listOf("https://media.example.test/fresh.ts"), f.opened)
            refuse(f.controller)
            advanceUntilIdle()
            coVerify(exactly = 1) { f.stalker.resolve(source, channel.url, true) }
            assertEquals(1, f.opened.size)
            assertEquals(2, f.controller.state.value.reconnectAttempt)
            // Once playback has actually recovered, a later token expiry gets its own budget.
            (field(f.controller, "listener") as androidx.media3.common.Player.Listener)
                .onPlaybackStateChanged(androidx.media3.common.Player.STATE_READY)
            refuse(f.controller)
            advanceUntilIdle()
            coVerify(exactly = 2) { f.stalker.resolve(source, channel.url, true) }
            assertEquals(2, f.opened.size)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun `ordinary Stalker reconnect obtains a new link without reimporting the channel list`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture()
        try {
            coEvery { f.stalker.resolve(source, channel.url, false) } returns StalkerPortalClient.Link("https://media.example.test/renewed.ts", emptyMap())
            reconnect(f.controller)
            advanceUntilIdle()
            coVerify(exactly = 1) { f.stalker.resolve(source, channel.url, false) }
            assertEquals(listOf("https://media.example.test/renewed.ts"), f.opened)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun `failed fresh lookup never replays the refused URL`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture()
        try {
            coEvery { f.stalker.resolve(source, channel.url, true) } throws IllegalStateException("Synthetic failure")
            refuse(f.controller)
            advanceUntilIdle()
            assertTrue(f.opened.isEmpty())
            assertEquals("Could not refresh the portal channel. Try again.", f.controller.state.value.error)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun `M3U retries keep the original URL without contacting a Stalker portal`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture()
        try {
            setField(f.controller, "currentChannel", channel.copy(url = "https://media.example.test/old.ts"))
            reconnect(f.controller)
            advanceUntilIdle()
            coVerify(exactly = 0) { f.stalker.resolve(any(), any(), any()) }
            assertEquals(listOf("https://media.example.test/old.ts"), f.opened)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun `a slow recovery cannot replace a subsequently selected channel`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture()
        try {
            coEvery { f.stalker.resolve(source, channel.url, true) } coAnswers {
                delay(5_000)
                StalkerPortalClient.Link("https://media.example.test/late.ts", emptyMap())
            }
            refuse(f.controller)
            advanceTimeBy(2_001)
            runCurrent()
            setField(f.controller, "currentChannel", channel.copy(key = "replacement", url = "https://media.example.test/replacement.ts"))
            setField(f.controller, "currentUrl", "https://media.example.test/replacement.ts")
            advanceUntilIdle()
            assertTrue(f.opened.isEmpty())
            assertEquals("https://media.example.test/replacement.ts", field(f.controller, "currentUrl"))
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun `a recovery timeout surfaces an error without replaying the refused URL`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture()
        try {
            coEvery { f.stalker.resolve(source, channel.url, true) } coAnswers { awaitCancellation() }
            refuse(f.controller)
            advanceUntilIdle()
            assertTrue(f.opened.isEmpty())
            assertEquals("Could not refresh the portal channel. Try again.", f.controller.state.value.error)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    private companion object {
        fun field(controller: LiveTvPlaybackController, name: String): Any? = LiveTvPlaybackController::class.java
            .getDeclaredField(name).apply { isAccessible = true }.get(controller)
        fun setField(controller: LiveTvPlaybackController, name: String, value: Any?) = LiveTvPlaybackController::class.java
            .getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
    }
}
