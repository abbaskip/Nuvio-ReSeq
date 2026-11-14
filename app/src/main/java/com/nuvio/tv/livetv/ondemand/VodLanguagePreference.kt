package com.nuvio.tv.livetv.ondemand

import java.util.Locale

/** Provider audio hints, kept separate from title identity and subtitle languages. */
internal object VodLanguagePreference {
    private val aliases = buildMap {
        Locale.getISOLanguages().forEach { code ->
            val locale = Locale.forLanguageTag(code)
            put(code, code)
            runCatching { locale.isO3Language }.getOrNull()?.let { put(it, code) }
            put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), code)
            put(locale.getDisplayLanguage(locale).lowercase(Locale.ROOT), code)
        }
        putAll(mapOf("ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh",
            "cze" to "cs", "gre" to "el", "rum" to "ro", "wel" to "cy",
            "latino" to "es", "brazil" to "pt", "brazilian" to "pt"))
    }
    private val countryTags = mapOf("uk" to "en", "us" to "en", "usa" to "en", "gb" to "en", "au" to "en")
    // These collide with ISO codes but describe IPTV quality/content rather than audio.
    private val nonLanguageTags = setOf("sd", "tv")
    private val tokens = Regex("""[\p{L}]+(?:[-_][\p{L}]{2,4})?""")
    private val brackets = Regex("""\[([^\]]+)]|\(([^)]+)\)|\|([^|]+)\|""")
    private val prefix = Regex("""^\s*([A-Z]{2,3}(?:[-_][A-Z]{2})?)\s*(?:[:|]|[-–—]\s)""")
    private val suffix = Regex("""\s+([A-Z]{2,3})\s*$""")
    private val subtitle = Regex("""(?i)\b(subs?|subtitles?|subbed|multi[- ]?sub|vostfr)\b""")

    private fun code(value: String): String? {
        val key = value.trim().lowercase(Locale.ROOT)
        return aliases[key] ?: key.takeIf { '-' in it || '_' in it }
            ?.substringBefore('-')?.substringBefore('_')?.let(aliases::get)
    }

    fun audio(value: String?): Set<String> = value?.let(::code)?.let(::setOf).orEmpty()

    private fun tag(value: String): Set<String> {
        if (subtitle.containsMatchIn(value)) return emptySet()
        return tokens.findAll(value).mapNotNull { tagCode(it.value) }.toSet()
    }

    private fun tagCode(value: String): String? = value.lowercase(Locale.ROOT).let { key ->
        if (key in nonLanguageTags) null else countryTags[key] ?: code(value)
    }

    /** Only decorations count in titles: ordinary words such as 'It' and 'Us' are titles. */
    fun title(value: String): Set<String> = buildSet {
        brackets.findAll(value).forEach { match ->
            addAll(tag(match.groupValues.drop(1).first { it.isNotEmpty() }))
        }
        prefix.find(value)?.groupValues?.get(1)?.let { addAll(tag(it)) }
        suffix.find(value)?.groupValues?.get(1)?.let { addAll(tag(it)) }
    }

    /** Folder labels may spell out the language, e.g. 'German Series' or 'EN | Cooking'. */
    fun folder(value: String): Set<String> {
        if (subtitle.containsMatchIn(value)) return emptySet()
        return tokens.findAll(value).filter { token ->
            token.value.length > 3 || token.value == token.value.uppercase(Locale.ROOT) ||
                value.trim() == token.value && token.value == token.value.lowercase(Locale.ROOT) ||
                Regex("""^\s*${Regex.escape(token.value)}\s*[:|–—-]""").containsMatchIn(value)
        }.mapNotNull { tagCode(it.value) }.toSet() + title(value)
    }

    fun resolve(audio: String?, episodeTitle: String?, seriesTitle: String, folder: String): Set<String> =
        audio(audio).ifEmpty { title(episodeTitle.orEmpty()) }
            .ifEmpty { title(seriesTitle) }.ifEmpty { folder(folder) }

    fun rank(languages: Set<String>, preferred: Locale): Int = when {
        preferred.language.ifBlank { "en" } in languages -> 0
        "en" in languages -> 1
        languages.isEmpty() -> 2
        else -> 3
    }

    fun display(languages: Set<String>): String? = languages.takeIf { it.isNotEmpty() }
        ?.joinToString(" / ") { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
}
