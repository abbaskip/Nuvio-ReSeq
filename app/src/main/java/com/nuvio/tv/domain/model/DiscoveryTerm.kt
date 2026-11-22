package com.nuvio.tv.domain.model

internal data class DiscoveryTerm(val text: String, val quoted: Boolean) {
    fun matchesKeyword(name: String): Boolean =
        if (quoted) name.equals(text, ignoreCase = true) else name.contains(text, ignoreCase = true)

    fun matchesTitle(title: String): Boolean {
        if (!quoted) return title.contains(text, ignoreCase = true)
        var start = title.indexOf(text, ignoreCase = true)
        while (start >= 0) {
            val end = start + text.length
            if (title.getOrNull(start - 1)?.isLetterOrDigit() != true && title.getOrNull(end)?.isLetterOrDigit() != true) return true
            start = title.indexOf(text, start + 1, ignoreCase = true)
        }
        return false
    }
}

internal fun discoveryTerms(input: String): List<DiscoveryTerm> {
    val source = input.replace('“', '"').replace('”', '"')
    val terms = mutableListOf<DiscoveryTerm>()
    var start = 0
    var inQuotes = false
    fun addTerm(end: Int) {
        val term = source.substring(start, end).trim()
        val quoted = term.length >= 2 && term.first() == '"' && term.last() == '"'
        val text = if (quoted) term.substring(1, term.lastIndex).trim() else term
        if (text.isNotEmpty()) terms += DiscoveryTerm(text, quoted)
    }
    source.forEachIndexed { index, char ->
        if (char == '"') inQuotes = !inQuotes
        if (char == ',' && !inQuotes) {
            addTerm(index)
            start = index + 1
        }
    }
    addTerm(source.length)
    return terms.distinctBy { it.text.lowercase() to it.quoted }
}

internal inline fun List<DiscoveryTerm>.matchTerms(all: Boolean, matches: (DiscoveryTerm) -> Boolean): Boolean =
    isEmpty() || if (all) all(matches) else any(matches)
