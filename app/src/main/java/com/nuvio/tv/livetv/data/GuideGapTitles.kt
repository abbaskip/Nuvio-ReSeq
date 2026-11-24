package com.nuvio.tv.livetv.data

/** Explicit missing-data labels, including the fallback used by older XMLTV imports. */
internal object GuideGapTitles {
    private val labels = setOf("", "no program guide available", "no programme guide available",
        "no information", "no info", "no program information", "no programme information")
    private val sqlLabels = labels.joinToString { "'$it'" }

    fun matches(title: String): Boolean {
        if (title.isNotEmpty() && title[0].isLetter() && !title.startsWith("no ", ignoreCase = true)) return false
        return title.trim(' ', '\t', '\r', '\n', '.').lowercase() in labels
    }

    fun predicate(column: String) = "trim($column, char(32,9,13,10,46)) COLLATE NOCASE IN ($sqlLabels)"
}
