package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.WatchProgress

/** Device-only receipt, never imported from cloud history or sent to a tracker. */
internal data class RecentDeviceProgress(
    val progress: WatchProgress,
    val recordedAt: Long,
    val confirmedSources: Set<String> = emptySet()
)

internal const val DEVICE_PROGRESS_GRACE_MS = 10 * 60_000L

internal fun RecentDeviceProgress.pendingFor(source: String, now: Long): Boolean =
    source !in confirmedSources && now - recordedAt in 0 until DEVICE_PROGRESS_GRACE_MS

internal fun WatchProgress.confirmsDeviceProgress(local: WatchProgress): Boolean {
    if (contentId != local.contentId || lastWatched < local.lastWatched) return false
    val native = tvdbContext?.episodeIds.orEmpty() + tvdbEpisodeIds.orEmpty() + listOfNotNull(tvdbEpisodeId)
    val localNative = local.tvdbContext?.episodeIds.orEmpty() + local.tvdbEpisodeIds.orEmpty() + listOfNotNull(local.tvdbEpisodeId)
    val sameEpisode = if (native.isNotEmpty() && localNative.isNotEmpty()) native.containsAll(localNative)
        else season == local.season && episode == local.episode
    return sameEpisode && (isCompleted() || progressPercentage >= local.progressPercentage)
}
