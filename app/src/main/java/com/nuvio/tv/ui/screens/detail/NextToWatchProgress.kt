package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.WatchProgress

internal fun resolveNextToWatchLatestProgress(
    latestProgress: WatchProgress?,
    progressEntries: Collection<WatchProgress>,
    isResumable: (WatchProgress) -> Boolean
): WatchProgress? {
    val resumeOverride = progressEntries
        .filter(isResumable)
        .maxWithOrNull(
            compareBy<WatchProgress> { it.lastWatched }
                .thenBy { it.season ?: 0 }
                .thenBy { it.episode ?: 0 }
        )
    return when {
        resumeOverride != null && (
            latestProgress == null ||
                resumeOverride.lastWatched >= maxOf(latestProgress.lastWatched,
                    progressEntries.filter { it.isCompleted() }.maxOfOrNull { it.lastWatched } ?: Long.MIN_VALUE)
            ) -> resumeOverride
        else -> latestProgress
    }
}
