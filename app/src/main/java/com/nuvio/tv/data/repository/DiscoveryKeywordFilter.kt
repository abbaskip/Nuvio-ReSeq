package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.TmdbKeywordSearchResponse
import com.nuvio.tv.domain.model.DiscoveryTerm

// Leave room for URL encoding, credentials and the other Discover parameters.
private const val MAX_KEYWORD_PARAMETER_LENGTH = 2000

/** TMDB accepts one AND or OR list. A partial term can resolve to several alternative tag IDs. */
internal data class DiscoveryKeywordFilter(
    val parameter: String? = null,
    val checkItemTags: Boolean = false,
    val empty: Boolean = false
)

internal suspend fun resolveDiscoveryKeywords(
    terms: List<DiscoveryTerm>, matchAll: Boolean,
    search: suspend (String, Int) -> TmdbKeywordSearchResponse
): DiscoveryKeywordFilter {
    if (terms.isEmpty()) return DiscoveryKeywordFilter()
    val groups = mutableListOf<Set<Int>>()
    var broadTerm = false
    for (term in terms) {
        val first = search(term.text, 1)
        val pageCount = (first.totalPages ?: 1).coerceAtLeast(1)
        // For very broad terms, compare the actual tags on each discovered title. This
        // avoids oversized query URLs and keeps matches beyond the first keyword pages.
        if (pageCount > 500) {
            broadTerm = true
            continue
        }
        val ids = mutableSetOf<Int>()
        var parameterLength = 0
        for (page in 1..pageCount) {
            val result = if (page == 1) first else search(term.text, page)
            for (tag in result.results.orEmpty()) {
                if (tag.id > 0 && tag.name?.let(term::matchesKeyword) == true && ids.add(tag.id))
                    parameterLength += tag.id.toString().length + 1
            }
            if (parameterLength > MAX_KEYWORD_PARAMETER_LENGTH) break
        }
        if (parameterLength > MAX_KEYWORD_PARAMETER_LENGTH) {
            broadTerm = true
            continue
        }
        if (ids.isEmpty() && matchAll) return DiscoveryKeywordFilter(empty = true)
        if (ids.isNotEmpty()) groups += ids
    }
    if (groups.isEmpty()) return DiscoveryKeywordFilter(checkItemTags = broadTerm, empty = !broadTerm)
    if (!matchAll) {
        val union = groups.flatten().distinct().sorted().joinToString("|")
        return if (broadTerm || union.length > MAX_KEYWORD_PARAMETER_LENGTH) DiscoveryKeywordFilter(checkItemTags = true)
            else DiscoveryKeywordFilter(parameter = union)
    }
    val exactIds = groups.filter { it.size == 1 }.flatten().distinct().sorted()
    if (exactIds.isNotEmpty()) {
        // One required tag can satisfy multiple overlapping terms.
        val required = exactIds.joinToString(",")
        if (required.length > MAX_KEYWORD_PARAMETER_LENGTH) return DiscoveryKeywordFilter(exactIds.first().toString(), checkItemTags = true)
        val needsCheck = broadTerm || groups.any { group -> exactIds.none(group::contains) }
        return DiscoveryKeywordFilter(required, checkItemTags = needsCheck)
    }
    val smallest = groups.minBy { it.size }
    return DiscoveryKeywordFilter(smallest.sorted().joinToString("|"),
        checkItemTags = broadTerm || groups.any { !it.containsAll(smallest) })
}
