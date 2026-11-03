package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.*
import org.junit.Test

class StreamAutoPlayPolicyTest {
    private fun stream(group: String) = Stream("Fixture", null, null, "https://fixture.invalid/video.mp4",
        null, null, null, null, StreamBehaviorHints(notWebReady = null, bingeGroup = group,
            countryWhitelist = null, proxyHeaders = null), "Addon", null)

    private fun select(policy: StreamAutoPlayPolicy.NextEpisodeSelection, group: String?) =
        StreamAutoPlaySelector.selectAutoPlayStream(listOf(stream("quality")),
            policy.settings.streamAutoPlayMode, policy.settings.streamAutoPlayRegex,
            policy.settings.streamAutoPlaySource, setOf("Addon"),
            policy.settings.streamAutoPlaySelectedAddons, policy.settings.streamAutoPlaySelectedPlugins,
            group, policy.settings.streamAutoPlayPreferBingeGroupForNextEpisode, policy.bingeGroupOnly,
            policy.settings)

    @Test fun `manual initial selection still autoplays next episode using its enabled fallback`() {
        val stored = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = true, streamAutoPlayNextEpisodeFallbackEnabled = true,
            streamAutoPlaySource = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            streamAutoPlaySelectedAddons = setOf("Old"), streamAutoPlayRegex = "old")
        val policy = StreamAutoPlayPolicy.nextEpisodeSelection(stored)
        assertTrue(policy.automatic)
        assertFalse(policy.bingeGroupOnly)
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, policy.settings.streamAutoPlayMode)
        assertEquals(StreamAutoPlaySource.ALL_SOURCES, policy.settings.streamAutoPlaySource)
        assertTrue(policy.settings.streamAutoPlaySelectedAddons.isEmpty())
        assertEquals("", policy.settings.streamAutoPlayRegex)
        assertEquals(StreamAutoPlayMode.MANUAL, stored.streamAutoPlayMode)
    }

    @Test fun `disabled fallback requires the current binge group and never chooses another source`() {
        val policy = StreamAutoPlayPolicy.nextEpisodeSelection(PlayerSettings(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL, streamAutoPlayNextEpisodeEnabled = true,
            streamAutoPlayNextEpisodeFallbackEnabled = false, streamAutoPlayPreferBingeGroupForNextEpisode = true))
        assertTrue(policy.bingeGroupOnly)
        assertNull(select(policy, "other"))
        assertNull(select(policy, null))
        assertEquals("quality", select(policy, "quality")?.behaviorHints?.bingeGroup)
    }

    @Test fun `explicit next with autoplay off can reuse only the current preferred group`() {
        val policy = StreamAutoPlayPolicy.nextEpisodeSelection(PlayerSettings(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL, streamAutoPlayNextEpisodeEnabled = false,
            streamAutoPlayPreferBingeGroupForNextEpisode = true))
        assertTrue(policy.automatic)
        assertTrue(policy.bingeGroupOnly)
        assertNull(select(policy, "other"))
        assertNotNull(select(policy, "quality"))
    }

    @Test fun `manual mode without autoplay or group preference remains manual`() {
        val stored = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            streamAutoPlayNextEpisodeEnabled = false, streamAutoPlayPreferBingeGroupForNextEpisode = false)
        val policy = StreamAutoPlayPolicy.nextEpisodeSelection(stored)
        assertFalse(policy.automatic)
        assertSame(stored, policy.settings)
    }

    @Test fun `configured first and regex modes retain their source restrictions`() {
        for (mode in listOf(StreamAutoPlayMode.FIRST_STREAM, StreamAutoPlayMode.REGEX_MATCH)) {
            val stored = PlayerSettings(streamAutoPlayMode = mode, streamAutoPlayRegex = "1080p",
                streamAutoPlaySelectedAddons = setOf("Addon"), streamAutoPlayAddonsEnabled = false,
                streamAutoPlayPlaylistsEnabled = true)
            val policy = StreamAutoPlayPolicy.nextEpisodeSelection(stored)
            assertSame(stored, policy.settings)
            assertFalse(policy.bingeGroupOnly)
            assertTrue(policy.automatic)
            assertEquals(false, policy.settings.streamAutoPlayAddonsEnabled)
        }
    }
}
