package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.*
import org.junit.Test

class GuidePriorityChannelsTest {
    @Test fun `large playlists only read the same first 400 priority channels`() {
        var reads = 0
        val channels = object : AbstractList<LiveChannel>() {
            override val size = 100_000
            override fun get(index: Int): LiveChannel {
                reads++
                return channel(index)
            }
        }
        assertEquals(List(400) { "key-$it" }, guidePriorityChannelKeys(channels))
        assertEquals(400, reads)
    }

    @Test fun `empty and small groups preserve all keys and their order`() {
        assertEquals(emptyList<String>(), guidePriorityChannelKeys(emptyList()))
        assertEquals(listOf("key-3", "key-1", "key-2"),
            guidePriorityChannelKeys(listOf(channel(3), channel(1), channel(2))))
    }

    private fun channel(i: Int) = LiveChannel("key-$i", "source", "Source", "Channel $i",
        null, null, null, "group", "Group", i, "https://example.invalid/$i")
}
