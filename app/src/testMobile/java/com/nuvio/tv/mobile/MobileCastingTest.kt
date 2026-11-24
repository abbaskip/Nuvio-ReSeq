package com.nuvio.tv.mobile

import android.app.Application
import com.google.android.gms.cast.*
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class MobileCastingTest {
    private val context = RuntimeEnvironment.getApplication() as Application
    private val video = MobileVideo(2, "series", "tt1234567", "tt1234567:2:4", "Test series", 2, 4, "A title", "provider")
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(2) }
    private val tracking = mockk<MobileCastTracking>(relaxed = true)
    private val callback = slot<RemoteMediaClient.Callback>()
    private val client = mockk<RemoteMediaClient>(relaxed = true)
    private val status = mockk<MediaStatus>(relaxed = true)
    private val session = mockk<CastSession>(relaxed = true)
    private val cast = MobileCasting(context, tracking, profiles, mockk(relaxed = true))

    private fun reconnect(iptv: Boolean, live: Boolean, profile: Int = 2, tracks: List<MediaTrack> = emptyList()) {
        val saved = CastItem("owned-media", profile, "Fixture", video, live, iptv)
        context.getSharedPreferences("cast_session", 0).edit().putString("item", Gson().toJson(saved)).commit()
        every { session.remoteMediaClient } returns client
        every { session.castDevice?.inetAddress } returns java.net.InetAddress.getByName("127.0.0.1") as java.net.Inet4Address
        every { client.registerCallback(capture(callback)) } just Runs
        every { client.mediaStatus } returns status
        val media = MediaInfo.Builder("https://video.example/media")
            .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("video/mp4").setMediaTracks(tracks)
            .setCustomData(JSONObject().put("reseqCastId", saved.token)).build()
        every { status.mediaInfo } returns media
        every { status.playerState } returns MediaStatus.PLAYER_STATE_PLAYING
        every { client.isPlaying } returns true
        every { client.streamDuration } returns 50_000L
        every { client.approximateStreamPosition } returns 8_000L
        cast.onSessionResumed(session, true)
    }

    @Test fun remoteVodControlsAndProgressUseTheOriginalEpisodeAfterReconnection() {
        reconnect(false, false)
        assertEquals(video, cast.state.value.item?.video)
        verify { tracking.observe(match { it.video == video && it.position == 8000L && it.playing }) }
        cast.toggle()
        verify { client.pause() }
        cast.seek(20_000)
        verify { client.seek(match<MediaSeekOptions> { it.position == 20_000L }) }
        every { status.playerState } returns MediaStatus.PLAYER_STATE_PAUSED
        every { client.isPlaying } returns false
        every { client.isPaused } returns true
        callback.captured.onStatusUpdated()
        verify { tracking.observe(match { !it.playing && it.video == video }) }
        every { status.playerState } returns MediaStatus.PLAYER_STATE_IDLE
        every { status.idleReason } returns MediaStatus.IDLE_REASON_FINISHED
        callback.captured.onStatusUpdated()
        assertNull(cast.state.value.item)
        verify(exactly = 1) { tracking.finish(true) }
    }

    @Test fun replacementSourceKeepsPositionAndOnlyHandsOffAfterTheReceiverReportsItsToken() {
        reconnect(false, false)
        val request = slot<MediaLoadRequestData>()
        every { client.load(capture(request)) } returns mockk(relaxed = true)
        var accepted = false
        cast.load(CastSource("https://video.example/other.mp4", "Another source", video = video)) { accepted = true }
        assertFalse(accepted)
        assertEquals(8_000L, request.captured.currentTime)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, request.captured.mediaInfo?.streamType)
        every { status.mediaInfo } returns request.captured.mediaInfo
        callback.captured.onStatusUpdated()
        assertTrue(accepted)
        assertEquals(video, cast.state.value.item?.video)
        verify(exactly = 0) { tracking.finish(any()) }
    }

    @Test fun liveNeverTracksOrSeeksAndAudioKeepsTheActiveSubtitles() {
        val audio = MediaTrack.Builder(101, MediaTrack.TYPE_AUDIO).setName("English").build()
        val subtitles = MediaTrack.Builder(201, MediaTrack.TYPE_TEXT).setName("Captions").build()
        reconnect(true, true, tracks = listOf(audio, subtitles))
        every { status.activeTrackIds } returns longArrayOf(201)
        callback.captured.onStatusUpdated()
        assertEquals(listOf(audio, subtitles), cast.state.value.tracks)
        assertEquals(setOf(201L), cast.state.value.selected)
        cast.track(MediaTrack.TYPE_AUDIO, 101)
        verify { client.setActiveMediaTracks(match { it.toSet() == setOf(101L, 201L) }) }
        cast.seek(5000)
        verify(exactly = 0) { client.seek(any<MediaSeekOptions>()) }
        verify(exactly = 0) { tracking.observe(any()) }
    }

    @Test fun liveBufferingNeverCompletesHandoffAndTimesOutAnOwnedLoad() {
        reconnect(true, true)
        val request = slot<MediaLoadRequestData>()
        every { client.load(capture(request)) } returns mockk(relaxed = true)
        var accepted = false
        cast.load(CastSource("https://video.example/news.m3u8", "News", live = true, iptv = true)) { accepted = true }
        every { status.mediaInfo } returns request.captured.mediaInfo
        every { status.playerState } returns MediaStatus.PLAYER_STATE_BUFFERING
        every { client.isPlaying } returns false
        callback.captured.onStatusUpdated()
        assertFalse(accepted)
        assertTrue(cast.state.value.loading)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31))
        assertFalse(accepted)
        assertFalse(cast.state.value.loading)
        assertTrue(cast.state.value.error.orEmpty().contains("30 seconds"))
        verify { client.stop() }
    }

    @Test fun directDebridDeliveryFailureRetriesThroughThePhoneAndPreservesPosition() {
        reconnect(false, false)
        val requests = mutableListOf<MediaLoadRequestData>()
        every { client.load(capture(requests)) } returns mockk(relaxed = true)
        val url = "https://media.example.test/episode.mp4"
        var accepted = false
        cast.load(CastSource(url, "Episode", video = video)) { accepted = true }
        assertEquals(url, requests.single().mediaInfo?.contentId)
        every { status.mediaInfo } returns requests.single().mediaInfo
        every { client.isPlaying } returns false
        callback.captured.onMediaError(mockk {
            every { type } returns MediaError.ERROR_TYPE_LOAD_FAILED
            every { detailedErrorCode } returns MediaError.DetailedErrorCode.MEDIA_NETWORK
        })
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        main.idleFor(java.time.Duration.ofMillis(10))
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (requests.size < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10)
            main.idle()
        }
        try {
            assertEquals(2, requests.size)
            assertFalse(accepted)
            assertEquals("127.0.0.1", java.net.URI(requests.last().mediaInfo!!.contentId).host)
            assertEquals(8_000L, requests.last().currentTime)
            every { status.mediaInfo } returns requests.last().mediaInfo
            every { client.isPlaying } returns true
            callback.captured.onStatusUpdated()
            assertTrue(accepted)
        } finally { cast.onSessionEnded(session, 0) }
    }

    @Test fun catchupIsSeekableAndDoesNotWriteTracking() {
        reconnect(true, false)
        cast.seek(5000)
        verify { client.seek(match<MediaSeekOptions> { it.position == 5000L }) }
        verify(exactly = 0) { tracking.observe(any()) }
    }

    @Test fun otherProfilesAndUnownedReceiverMediaCannotCreateHistory() {
        reconnect(false, false, profile = 3)
        assertNull(cast.state.value.item)
        verify(exactly = 0) { tracking.observe(any()) }
    }

    @Test fun deliveryErrorsTakePrecedenceAndOnlyFormatErrorsAutomaticallyConvert() {
        assertEquals(CastFailure.DELIVERY, classifyCastFailure(104, true))
        assertEquals(CastFailure.DELIVERY, classifyCastFailure(103, false))
        assertEquals(CastFailure.FORMAT, classifyCastFailure(104, false))
        assertEquals(CastFailure.FORMAT, classifyCastFailure(102, false))
        assertEquals(CastFailure.UNKNOWN, classifyCastFailure(null, false))
        assertEquals(CastFormat.MP4, nextCastFormat(CastFormat.ORIGINAL, "audio/mp4a-latm", false))
        assertEquals(CastFormat.AAC, nextCastFormat(CastFormat.ORIGINAL, "audio/vnd.dts", false))
        assertEquals(CastFormat.H264, nextCastFormat(CastFormat.AAC, "audio/vnd.dts", false))
        assertNull(nextCastFormat(CastFormat.H264, "audio/mp4a-latm", true))
    }

    @Test fun rawLocalUrlsAreNeverHandedToTheReceiver() {
        for (url in listOf("file:///saved/show.mkv", "content://downloads/item", "usenet://example", "http://127.0.0.1:4872/usenet/a/video.mkv",
                "http://localhost:8000/video", "http://[::1]/video")) assertFalse(url, receiverUrl(url))
        assertTrue(receiverUrl("https://media.example.test/movie.mp4"))
        assertTrue(receiverUrl("http://192.168.1.20/channel.m3u8"))
    }
}
