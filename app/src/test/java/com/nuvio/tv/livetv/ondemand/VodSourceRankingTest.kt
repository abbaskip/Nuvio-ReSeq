package com.nuvio.tv.livetv.ondemand

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class VodSourceRankingTest {
    private fun tech(height: Int = 1080, codec: String = "hevc", channels: Int = 6, bitrate: Int = 1000) =
        VodTech(null, height, codec, null, null, null, channels, "English", bitrate, 3600)

    private fun source(id: String, tech: VodTech? = null, language: String = "en", title: String = "Show",
        episode: String = "", folder: String = "") = OnDemandRepository.OnDemandSource(id,
        "https://media.example.test/$id", emptyMap(), title = title, tech = tech,
        languages = setOf(language), episodeTitle = episode, categoryName = folder)

    @Test fun `ranking follows language resolution encoding audio then size in that order`() {
        val expected = listOf(
            source("4k", tech(2160, "h264", 2)),
            source("larger", tech(bitrate = 2000)),
            source("surround", tech()),
            source("stereo", tech(channels = 2, bitrate = 9000)),
            source("avc", tech(codec = "h264", channels = 8, bitrate = 20000)),
            source("720p", tech(720, "av1", 8, 30000)),
            source("foreign", tech(4320, "av1", 12, 40000), "de")
        )
        assertEquals(expected, VodSourceRanking.sort(expected.reversed(), Locale.ENGLISH))
    }

    @Test fun `metadata wins over conflicting tags for every quality attribute`() {
        val tagged = source("metadata", tech(720, "h264", 2),
            title = "Show 4K HEVC 7.1", folder = "8K AV1 Atmos")
        val better = source("actual", tech())
        assertEquals(listOf(better, tagged), VodSourceRanking.sort(listOf(tagged, better), Locale.ENGLISH))
        val key = VodSourceRanking.key(tagged.languages, Locale.ENGLISH, tagged.tech, tagged.title, tagged.categoryName)
        assertEquals(720, key.resolution)
        assertEquals(0, key.encoding)
        assertEquals(2, key.audio)
    }

    @Test fun `episode series and folder tags fill missing metadata without requests`() {
        val episode = source("episode", episode = "Show.S01E01.1080p.x265.5.1", folder = "4K AVC 7.1")
        val folder = source("folder", folder = "EN | 4K AV1 7.1")
        val key = VodSourceRanking.key(episode.languages, Locale.ENGLISH, null, episode.episodeTitle, episode.title, episode.categoryName)
        assertEquals(VodSourceRanking.Key(0, 1080, 1, 6, 0), key)
        assertEquals(listOf(folder, episode), VodSourceRanking.sort(listOf(episode, folder), Locale.ENGLISH))
    }

    @Test fun `efficient codec equivalents tie and unknown values preserve provider order`() {
        val modern = listOf("x265", "HEVC", "H.265", "AV1", "VP9").map { source(it, tech(codec = it)) }
        assertEquals(modern, VodSourceRanking.sort(modern, Locale.ENGLISH))
        val unknown = listOf(source("first"), source("second"), source("third"))
        assertEquals(unknown, VodSourceRanking.sort(unknown, Locale.ENGLISH))
    }

    @Test fun `channel layouts support surround and height channels without confusing episode numbers`() {
        assertEquals(8, VodSourceRanking.audioChannels("7.1(side)"))
        assertEquals(6, VodSourceRanking.audioChannels("DDP5.1"))
        assertEquals(12, VodSourceRanking.audioChannels("7.1.4"))
        assertEquals(6, VodSourceRanking.audioChannels("Dolby Atmos"))
        assertNull(VodSourceRanking.layoutChannels("Dolby Atmos"))
        assertEquals(2, VodSourceRanking.audioChannels("stereo"))
        assertNull(VodSourceRanking.audioChannels("Show S07E01 2025.1080p"))
    }
}
