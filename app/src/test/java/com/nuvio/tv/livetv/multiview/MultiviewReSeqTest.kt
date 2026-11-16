package com.nuvio.tv.livetv.multiview

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.PlaylistAccount
import com.nuvio.tv.livetv.model.PlaylistSource
import org.junit.Assert.*
import org.junit.Test

class MultiviewReSeqTest {
    private fun channel(source: String, key: String = source) = LiveChannel(key, source, source, "Fixture", null,
        null, null, "group", "Group", 1, "http://fixture.invalid/stream")

    @Test fun `device limits reserve a decoder and respect heap and physical RAM tiers`() {
        assertEquals(2, MultiviewDevicePolicy.limit(16, 16, 512, false, 2048))
        assertEquals(3, MultiviewDevicePolicy.limit(16, 16, 512, false, 3072))
        assertEquals(2, MultiviewDevicePolicy.limit(16, 16, 384, false, 3072))
        assertEquals(2, MultiviewDevicePolicy.limit(16, 16, 2048, true, 4096))
        assertEquals(4, MultiviewDevicePolicy.limit(5, 16, 1024, false, 4096))
        assertEquals(3, MultiviewDevicePolicy.limit(16, 4, 1024, false, 4096))
        assertEquals(1, MultiviewDevicePolicy.limit(null, null, 1024, false))
        assertEquals(0, MultiviewDevicePolicy.limit(1, 16, 1024, false))
    }

    @Test fun `Xtream known activity and local reservations block before start across duplicate playlists`() {
        val source = PlaylistSource("a", "A", "", xtreamServer = "https://fixture.invalid", xtreamUsername = "u", xtreamPassword = "p")
        val copy = source.copy(id = "b")
        val account = mapOf("a" to PlaylistAccount(maxConnections = 2, activeConnections = 1))
        assertNull(MultiviewConnectionPolicy.denial(channel("a"), emptyList(), listOf(source), account))
        assertEquals("Your provider allows 2 streams at once", MultiviewConnectionPolicy.denial(
            channel("b"), listOf(channel("a")), listOf(source, copy), account))
        assertNotNull(MultiviewConnectionPolicy.denial(channel("a"), emptyList(), listOf(source),
            mapOf("a" to PlaylistAccount(maxConnections = 1, activeConnections = 1))))
        assertNull(MultiviewConnectionPolicy.denial(channel("a"), emptyList(), listOf(source), emptyMap()))
    }

    @Test fun `Stalker is one per portal and M3U activity is unknown`() {
        val portal = PlaylistSource("a", "A", "", stalkerPortal = "https://fixture.invalid/c/")
        val same = portal.copy(id = "b", stalkerPortal = "https://fixture.invalid/portal.php", stalkerMac = "00:11:22:33:44:55")
        assertNotNull(MultiviewConnectionPolicy.denial(channel("b"), listOf(channel("a")), listOf(portal, same), emptyMap()))
        val m3u = PlaylistSource("m", "M3U", "http://fixture.invalid/list.m3u")
        assertNull(MultiviewConnectionPolicy.denial(channel("m"), List(4) { channel("m", "$it") }, listOf(m3u), emptyMap()))
    }

    @Test fun `session enforces device limit and checks account before player creation including resume and retry`() {
        var created = 0
        var released = 0
        val source = PlaylistSource("a", "A", "", stalkerPortal = "http://fixture.invalid/c")
        val core = MultiviewSessionCore(MultiviewLayout.TWO, MultiviewBufferBudget.totalBytes(384),
            object : MultiviewAudioFocus {
                override fun request(onFocusChanged: (Boolean) -> Unit) = true
                override fun abandon() {}
            }, { _, _, _ ->
                created++
                object : MultiviewPanePlayer {
                    override val playback = MultiviewPanePlayback()
                    override fun prepare(channel: LiveChannel) {}
                    override fun setAudible(audible: Boolean) {}
                    override fun setPlaying(playing: Boolean) {}
                    override fun release() { released++ }
                }
            }, {}, maxPanes = 2, startFailure = { ch, active ->
                MultiviewConnectionPolicy.denial(ch, active, listOf(source), emptyMap())
            })
        core.onForeground()
        core.assignChannel(0, channel("a", "first"))
        core.assignChannel(1, channel("a", "second"))
        assertEquals(1, created)
        assertNotNull(core.playbackAt(1).error)
        core.retryPane(1)
        assertEquals(1, created)
        core.setLayout(MultiviewLayout.FOUR)
        assertEquals(MultiviewLayout.TWO, core.layout)
        core.onBackground()
        assertEquals(1, released)
        core.onForeground()
        assertEquals(2, created)
        core.stopAll()
        assertEquals(2, released)
        assertNull(core.channelAt(0))
        assertNull(core.channelAt(1))
    }

    @Test fun `single player releases first and the open session owns the same signal through background`() {
        val events = mutableListOf<String>()
        val owner = MultiviewLifecycle(releaseSingle = { events += "single-release" }, create = { events += "create"; Unit },
            signal = { events += "liveChannelOpen:$it" }, foreground = { events += "foreground" },
            background = { events += "release-all-panes" }, release = { events += "release-session" })
        assertEquals(listOf("single-release", "create", "liveChannelOpen:true"), events)
        owner.onForeground()
        owner.onBackground()
        assertEquals("release-all-panes", events.last())
        assertFalse(events.contains("liveChannelOpen:false"))
        owner.close()
        owner.close()
        assertEquals(listOf("release-session", "liveChannelOpen:false"), events.takeLast(2))
        assertEquals(1, events.count { it == "release-session" })
    }

    @Test fun `restored Multiview route has no playback permission`() {
        MultiviewRouteAccess.take()
        assertFalse(MultiviewRouteAccess.take())
        MultiviewRouteAccess.request()
        assertTrue(MultiviewRouteAccess.take())
        assertFalse(MultiviewRouteAccess.take())
    }

    @Test fun `all pane buffers fit within the single player allowance`() {
        for (heap in listOf(64L, 128, 256, 384, 512, 1024, 4096)) {
            val total = MultiviewBufferBudget.totalBytes(heap)
            val single = (heap * 1024 * 1024 * 3 / 10).coerceIn(32L shl 20, 160L shl 20)
            assertTrue(total <= single)
            assertTrue(4L * MultiviewBufferBudget.bytesPerPane(total) <= total)
        }
    }
}
