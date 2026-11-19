package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.DrmInfo
import com.nuvio.tv.livetv.model.PlaylistSource
import org.junit.Assert.*
import org.junit.Test

class M3uParserTest {
    private val source = PlaylistSource("pl", "Provider", "", userAgent = "default-agent")

    @Test fun manualRefreshJoiningAnAutomaticPassOnlyRechecksMissingOrEditedSources() {
        val pass = LiveTvRepository.AutomaticPass()
        assertFalse(pass.covers(source))
        pass.checked(source)
        assertTrue(pass.covers(source.copy(lastUpdatedMs = 3, channelCount = 20)))
        assertFalse(pass.covers(source.copy(userAgent = "different")))
        assertFalse(pass.covers(source.copy(id = "new")))
        assertFalse(LiveTvRepository.AutomaticPass().covers(source))
    }

    @Test fun channelPassPreservesReleasedOutputAndNeverDecodesSkippedVod() {
        val playlist = """
            #EXTM3U x-tvg-url="https://example.test/guide" catchup="default" catchup-days="3"
            #KODIPROP:inputstream.adaptive.license_type=widevine
            #KODIPROP:inputstream.adaptive.license_key=https://example.test/license|Origin=example
            #EXTVLCOPT:http-user-agent=entry
            #EXTINF:-1 tvg-id="abc" tvg-chno=42 tvg-name="Name, alt" group-title="Local;Other" tvg-shift="-2",A, B
            https://example.test/stream |referer=A%20B
            #EXTINF:-1 tvg-id="orphan" tvg-chno=9999,Film
            #EXTVLCOPT:http-referrer=discard
            https://example.test/MoViE/a/b/2.ts |Origin=discard
            #EXTINF:-1 tvg-id="abc" group-title=News catchup-correction="1,5",A, B
            https://example.test/live/abc.ts
            #EXTINF:-1 tvg-id="abc",A, B
            #EXTGRP:Regional
            https://example.test/live/abc.ts?next=.mp4x
            #EXTINF:-1,Loop
            https://example.test/loop.mp4?name=test |User-Agent=discard
        """.trimIndent()
        val oldShape = M3uChannelList(true)
        val legacy = M3uParser.parse(playlist.reader().buffered())
        legacy.entries.filterNot { LiveTvRepository.isVodEntry(it.url) }.forEach(oldShape.appendFrom(source))
        val parsed = M3uChannelList(true)
        val header = M3uParser.parse(playlist.reader().buffered(), { !LiveTvRepository.isVodEntry(it) }, parsed.appendFrom(source))
        assertEquals(listOf("https://example.test/guide"), header.epgUrls)
        assertEquals(oldShape.channels, parsed.channels)
        val (first, second, third) = parsed.channels
        assertEquals(listOf("pl|abc|A, B", "pl|abc|A, B#2", "pl|abc|A, B#3"), parsed.channels.map { it.key })
        assertEquals(listOf(42, 43, 44), parsed.channels.map { it.number })
        assertEquals(listOf("pl::Local", "pl::News", "pl::Regional"), parsed.channels.map { it.groupId })
        assertEquals("Name, alt", first.tvgName)
        assertEquals("https://example.test/stream", first.url)
        assertEquals(mapOf("User-Agent" to "entry", "Referer" to "A B"), first.headers)
        assertEquals(DrmInfo("widevine", "https://example.test/license", mapOf("Origin" to "example")), first.drm)
        assertNull(second.drm)
        assertEquals(CatchupInfo("default", null, 3, 1.5), second.catchup)
        assertEquals(mapOf("User-Agent" to "default-agent"), second.headers)
        assertSame(second.headers, third.headers)
        // A VOD-only malformed URL header must never even be decoded during the live pass.
        val broken = "#EXTM3U\n#EXTINF:-1,Movie\nhttps://example.test/movie/1|Referer=%zz"
        assertTrue(M3uParser.parse(broken.reader().buffered(), { !LiveTvRepository.isVodEntry(it) }, null).entries.isEmpty())
    }

    @Test fun attributeScannerMatchesBothReleasedRegexesIncludingNestedAndRepeatedKeys() {
        val quoted = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")
        val plain = Regex("""([A-Za-z0-9_\-]+)\s*=\s*([^\s",]+)""")
        for (line in listOf(
            """#EXTINF:-1 tvg-id=first tvg-id="quoted" tvg-id=last TVG-Id="final",Name""",
            """#EXTINF:-1 logo="https://logo.test/?tvg-id=embedded&v=2" tvg-shift="-1.5",News""",
            """#EXTINF:-1 tvg-name="Channel, with = and spaces" name=outer=inner,b=extra""",
            """#EXTINF:-1 group-title="unfinished tvg-name=odd,Channel""",
            "#EXTINF:-1\tfoo  = bar tvg-shift=4 extinf=ignore, Name",
            """#EXTM3U catchup="" catchup-days=5 key=, group-title='Sport'"""
        )) {
            val expected = HashMap<String, String>()
            quoted.findAll(line).forEach { expected[it.groupValues[1].lowercase()] = it.groupValues[2] }
            plain.findAll(line).forEach {
                val key = it.groupValues[1].lowercase()
                if (key != "extinf") expected.putIfAbsent(key, it.groupValues[2])
            }
            assertEquals(line, expected, M3uParser.attributes(line))
        }
    }
}
