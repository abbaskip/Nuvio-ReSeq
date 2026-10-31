package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.forPlaylist
import org.junit.Assert.*
import org.junit.Test

class PlaylistScopeTest {
    private fun channel(source: String) = LiveChannel(
        key = "$source:1", sourceId = source, sourceName = source, name = "News",
        tvgId = "news", tvgName = null, logo = null, groupId = "$source:news", group = "News",
        number = 1, url = "https://example.test/$source"
    )

    @Test fun `guide and playback list distinguish equal channel names and numbers by playlist ID`() {
        val first = channel("first")
        val second = channel("second")
        val all = listOf(first, second)
        assertEquals(listOf(first), all.forPlaylist("first"))
        assertEquals(listOf(second), all.forPlaylist("second"))
        assertTrue(all.forPlaylist("missing").isEmpty())
    }

    @Test fun `all playlists retains the existing list without copying or dropping channels`() {
        val all = listOf(channel("first"), channel("second"))
        assertSame(all, all.forPlaylist(null))
    }
}
