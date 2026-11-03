package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.PlaylistSource
import org.junit.Assert.*
import org.junit.Test

class IptvPlaylistTest {
    @Test fun `streaming playlist callback retains no second channel catalog`() {
        var delivered = 0
        val text = """#EXTM3U x-tvg-url="https://example.test/guide.xml"
#EXTINF:-1 tvg-id="news" group-title="News",News
https://example.test/live/channel.ts
#EXTINF:-1 group-title="Movies",Film
https://example.test/movie/film.mp4
"""
        val expected = M3uParser.parse(text.reader().buffered())
        val streamed = M3uParser.parse(text.reader().buffered()) { entry ->
            assertEquals(expected.entries[delivered], entry)
            delivered++
        }
        assertEquals(2, delivered)
        assertEquals(expected.epgUrls, streamed.epgUrls)
        assertTrue(streamed.entries.isEmpty())
    }

    @Test fun `M3U preserves category and guide identity independently of channel name`() {
        val playlist = M3uParser.parse("""#EXTM3U x-tvg-url="https://example.test/guide.xml.gz"
#EXTINF:-1 tvg-id="news.au" group-title="Australia | News",News HD
https://example.test/live/news.m3u8
#EXTINF:-1 tvg-id="sport.au" group-title="Australia | Sports",Sports HD
https://example.test/live/sport.m3u8
""".reader().buffered())
        assertEquals(listOf("https://example.test/guide.xml.gz"), playlist.epgUrls)
        assertEquals(listOf("Australia | News", "Australia | Sports"), playlist.entries.map { it.group })
        assertEquals("news.au", playlist.entries.first().tvgId)
        assertEquals("https://example.test/live/news.m3u8", playlist.entries.first().url)
    }

    @Test fun `Xtream credentials are encoded and endpoint paths are normalized`() {
        val source = PlaylistSource("provider-a", "Provider A", "", xtreamServer = "https://example.test:8443/get.php",
            xtreamUsername = "user+name", xtreamPassword = "p&word")
        assertEquals("https://example.test:8443", source.xtreamBase())
        assertTrue(source.xtreamApiUrl().endsWith("/player_api.php?username=user%2Bname&password=p%26word"))
        assertTrue(source.xtreamEpgUrl()!!.contains("/xmltv.php?"))
        assertTrue(source.resolvedUrl().contains("type=m3u_plus"))
    }
}
