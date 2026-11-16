package com.nuvio.tv.ui.screens.search

/** Recognizes delayed ViewModel echoes without overwriting a newer TextField edit. */
internal class SearchEditAcknowledgements {
    private val pending = ArrayDeque<String>()

    fun sent(text: String) {
        pending.addLast(text)
        // A collected StateFlow may coalesce intermediate queries. Keep only a bounded tail.
        while (pending.size > 64) pending.removeFirst()
    }

    fun acknowledge(text: String): Boolean {
        val index = pending.indexOfLast { it == text }
        if (index < 0) return false
        repeat(index + 1) { pending.removeFirst() }
        return true
    }
}
