package com.nuvio.tv.core.plugin

import java.util.Locale

/** Source language codes follow CloudStream's MainAPI.lang, including its regional aliases. */
object SourceLanguages {
    private val aliases = mapOf("mx" to "es", "jp" to "ja", "cn" to "zh", "kr" to "ko", "iw" to "he", "in" to "id")
    private val universal = setOf("multi", "mul", "uni", "xx", "und", "all")
    fun normalize(language: String?): String? {
        val code = language?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
            ?.takeIf { it.isNotBlank() && it !in universal } ?: return null
        return aliases[code] ?: code
    }
    fun allows(language: String?, selected: Set<String>): Boolean =
        selected.isEmpty() || normalize(language)?.let { it in selected } != false

    fun displayName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        .takeIf { it.isNotBlank() } ?: code
}
