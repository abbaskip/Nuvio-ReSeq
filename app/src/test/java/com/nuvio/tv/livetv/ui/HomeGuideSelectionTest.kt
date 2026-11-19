package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class HomeGuideSelectionTest {
    @Test fun homeUsesTheGuideChannelOrderWithoutBuildingUnrelatedGroups() {
        val repo = mockk<LiveTvRepository>()
        fun channel(key: String, group: String, number: Int) =
            LiveChannel(key, "source", "Source", key, key, null, null, group, group, number, "https://example.test/$key")
        val channels = listOf(channel("second", "sports", 2), channel("first", "sports", 1), channel("hidden", "news", 3))
        val user = LiveUserState(favorites = listOf("first", "hidden", "second"), recent = listOf("second", "first"),
            hiddenChannels = setOf("hidden"))
        for (group in listOf(ChannelGroup.FAVORITES, ChannelGroup.RECENT, "sports", ChannelGroup.ALL)) {
            val settings = LiveTvSettings(sequentialNumbers = true)
            val guide = repo.buildUi(channels, user, settings, group, "", true, providerRanks = emptyMap())
            val home = repo.buildUi(channels, user, settings, group, "", true, true, emptyMap())
            assertEquals(guide.channels, home.channels)
            assertTrue(home.groups.isEmpty())
        }
        assertTrue(repo.buildUi(channels, user, LiveTvSettings(), "missing", "", true, true, emptyMap()).channels.isEmpty())
    }
}
