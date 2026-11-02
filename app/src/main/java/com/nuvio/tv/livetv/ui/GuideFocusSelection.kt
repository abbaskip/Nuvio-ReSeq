package com.nuvio.tv.livetv.ui

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Never let an old group's focus overwrite a newer playlist selection while its model loads. */
internal suspend fun awaitGuideSelection(
    states: StateFlow<LiveTvUiState>,
    requested: StateFlow<String?>,
    timeoutMs: Long = 5_000L
): LiveTvUiState? = withTimeoutOrNull(timeoutMs) {
    combine(states, requested) { state, target ->
        state.takeIf {
            target == null || it.selectedGroupId == target ||
                (it.groups.isNotEmpty() && it.groups.none { group -> group.id == target })
        }
    }.filterNotNull().first()
}
