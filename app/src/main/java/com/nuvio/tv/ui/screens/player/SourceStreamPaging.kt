package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream

private const val SOURCE_FILTER_PAGE_SIZE = 100

/** Pure updates: a retried StateFlow update must not publish a separate, stale paging cache. */
internal fun PlayerUiState.withSourceStreams(
    streams: List<Stream>,
    resetPage: Boolean = false
): PlayerUiState {
    val filtered = sourceSelectedAddonFilter?.let { addon ->
        streams.filter { it.addonName == addon }
    } ?: streams
    val pageSize = if (resetPage) SOURCE_FILTER_PAGE_SIZE else {
        sourceFilteredStreams.size.coerceAtLeast(SOURCE_FILTER_PAGE_SIZE)
    }
    val pageEnd = pageSize.coerceAtMost(filtered.size)
    return copy(
        sourceAllStreams = streams,
        sourceFilterFullList = filtered,
        sourceFilteredStreams = if (pageEnd == filtered.size) filtered else filtered.subList(0, pageEnd)
    )
}

internal fun PlayerUiState.withSourceAddonFilter(addonName: String?): PlayerUiState =
    copy(sourceSelectedAddonFilter = addonName).withSourceStreams(sourceAllStreams, resetPage = true)

internal fun PlayerUiState.withExpandedSourcePage(): PlayerUiState {
    if (sourceFilteredStreams.size >= sourceFilterFullList.size) return this
    val pageEnd = (sourceFilteredStreams.size + SOURCE_FILTER_PAGE_SIZE).coerceAtMost(sourceFilterFullList.size)
    return copy(sourceFilteredStreams = sourceFilterFullList.subList(0, pageEnd))
}
