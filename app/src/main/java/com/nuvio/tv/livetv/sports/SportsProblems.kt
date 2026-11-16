package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** A successful live-score request cannot clear a failed catalogue download. */
internal class SportsProblems {
    val catalogue = MutableStateFlow<String?>(null)
    val scores = MutableStateFlow<String?>(null)
    val message = combine(catalogue, scores) { feed, score -> feed ?: score }
}
