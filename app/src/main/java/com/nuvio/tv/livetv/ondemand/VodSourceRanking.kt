package com.nuvio.tv.livetv.ondemand

import java.util.Locale

/** Uses already loaded metadata only. Compute each key once, before sorting. */
internal object VodSourceRanking {
    data class Key(val language: Int, val resolution: Int, val encoding: Int, val audio: Int, val bytes: Long)

    val comparator: Comparator<Key> = compareBy<Key> { it.language }
        .thenByDescending { it.resolution }
        .thenByDescending { it.encoding }
        .thenByDescending { it.audio }
        .thenByDescending { it.bytes }

    private val resolutions = listOf(
        4320 to Regex("""(?i)(?<![\p{L}\p{N}])(?:8k|4320p)(?![\p{L}\p{N}])"""),
        2160 to Regex("""(?i)(?<![\p{L}\p{N}])(?:4k|uhd|2160p)(?![\p{L}\p{N}])"""),
        1080 to Regex("""(?i)(?<![\p{L}\p{N}])(?:fhd|full[ ._-]?hd|1080[pi])(?![\p{L}\p{N}])"""),
        720 to Regex("""(?i)(?<![\p{L}\p{N}])(?:hd|720p)(?![\p{L}\p{N}])"""),
        576 to Regex("""(?i)(?<![\p{L}\p{N}])576[pi](?![\p{L}\p{N}])"""),
        480 to Regex("""(?i)(?<![\p{L}\p{N}])(?:sd|480[pi])(?![\p{L}\p{N}])""")
    )
    private val codec = Regex("""(?i)(?<![\p{L}\p{N}])(?:hevc|[hx][ ._-]?265|av1|vp9|avc|[hx][ ._-]?264|mpeg[ ._-]?2)(?![\p{L}\p{N}])""")
    private val efficient = Regex("""(?i)^(?:hevc|[hx][ ._-]?265|av1|vp9)$""")
    private val channelLayout = Regex("""(?i)(?<![\p{L}\p{N}])(?:(?:ddp?|e-?ac-?3|ac-?3|dts|truehd)[ ._-]?)?([257])[._]1(?:[._]([24]))?(?![\p{L}\p{N}])""")
    private val surround = Regex("""(?i)\b(?:atmos|surround)\b""")
    private val stereo = Regex("""(?i)\b(?:stereo|2[.]0)\b""")
    private val mono = Regex("""(?i)\b(?:mono|1[.]0)\b""")

    fun layoutChannels(text: String): Int? = channelLayout.find(text)?.let {
        it.groupValues[1].toInt() + 1 + (it.groupValues[2].toIntOrNull() ?: 0)
    } ?: when {
        stereo.containsMatchIn(text) -> 2
        mono.containsMatchIn(text) -> 1
        else -> null
    }

    // Atmos/surround tags imply a preference, not a measured channel count.
    fun audioChannels(text: String): Int? = layoutChannels(text)
        ?: if (surround.containsMatchIn(text)) 6 else null

    /** Tag order is episode, series, folder; actual media metadata overrides all tags. */
    fun key(languages: Set<String>, preferred: Locale, tech: VodTech?, vararg tags: String): Key {
        val resolution = when {
            (tech?.height ?: 0) >= 3000 || (tech?.width ?: 0) >= 6400 -> 4320
            tech?.quality == "4K" -> 2160
            tech?.quality == "1080p" -> 1080
            tech?.quality == "720p" -> 720
            tech?.quality == "SD" -> if ((tech.height ?: 0) >= 500) 576 else 480
            else -> tags.firstNotNullOfOrNull { text -> resolutions.firstOrNull { it.second.containsMatchIn(text) }?.first } ?: 0
        }
        val encoding = tech?.videoCodec?.takeIf { it.isNotBlank() && !it.equals("unknown", true) && !it.equals("und", true) }
            ?: tags.firstNotNullOfOrNull { codec.find(it)?.value }
        val channels = tech?.channels?.takeIf { it > 0 }
            ?: tags.firstNotNullOfOrNull(::audioChannels) ?: 0
        return Key(VodLanguagePreference.rank(languages, preferred), resolution,
            if (encoding != null && efficient.matches(encoding)) 1 else 0, channels,
            tech?.estimatedBytes?.takeIf { it > 0 } ?: 0L)
    }

    fun sort(sources: List<OnDemandRepository.OnDemandSource>, preferred: Locale): List<OnDemandRepository.OnDemandSource> =
        sources.map { it to key(it.languages, preferred, it.tech, it.episodeTitle, it.title, it.categoryName) }
            .sortedWith(compareBy(comparator) { it.second }).map { it.first }
}
