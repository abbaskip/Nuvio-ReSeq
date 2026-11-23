package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.WatchProgress

/** Device-only receipt, never imported from cloud history or sent to a tracker. */
internal data class RecentDeviceProgress(
    val progress: WatchProgress,
    val recordedAt: Long,
    val confirmedSources: Set<String> = emptySet(),
    val completedSources: Set<String>? = null // Absent in older receipts.
)

internal const val DEVICE_PROGRESS_GRACE_MS = 10 * 60_000L

internal fun WatchProgress.isDeviceEpisodeResume(): Boolean = source == WatchProgress.SOURCE_LOCAL &&
    trackingProviderId == null && hasRemovableEpisodePause() && !isBelowCwMinimum()

// Artwork and titles can be filled in independently of playback.
internal fun WatchProgress.matchesSavedPlay(saved: WatchProgress): Boolean =
    source == saved.source && trackingProviderId == saved.trackingProviderId &&
        videoId == saved.videoId && lastWatched == saved.lastWatched &&
        position == saved.position && duration == saved.duration && progressPercentage == saved.progressPercentage &&
        (deviceWatchEventId == null || saved.deviceWatchEventId == null || deviceWatchEventId == saved.deviceWatchEventId)

// A package upgrade can interrupt playback before the tracker receives a pause.
internal fun RecentDeviceProgress.retainedAt(now: Long): Boolean = now >= recordedAt &&
    (now - recordedAt < DEVICE_PROGRESS_GRACE_MS || progress.isDeviceEpisodeResume())

internal fun RecentDeviceProgress.pendingFor(source: String, now: Long): Boolean =
    source !in completedSources.orEmpty() && source !in confirmedSources && retainedAt(now)

internal fun WatchProgress.confirmsDeviceProgress(local: WatchProgress): Boolean {
    if (contentId != local.contentId || lastWatched < local.lastWatched) return false
    val native = tvdbContext?.episodeIds.orEmpty() + tvdbEpisodeIds.orEmpty() + listOfNotNull(tvdbEpisodeId)
    val localNative = local.tvdbContext?.episodeIds.orEmpty() + local.tvdbEpisodeIds.orEmpty() + listOfNotNull(local.tvdbEpisodeId)
    if (local.tvdbContext != null && tvdbContext != null && local.tvdbContext.orderType == tvdbContext.orderType &&
        local.tvdbContext.episodeId != tvdbContext.episodeId) return false
    if (local.tvdbContext != null && native.isEmpty()) return false
    val sameEpisode = if (native.isNotEmpty() && localNative.isNotEmpty()) native.containsAll(localNative)
        else season == local.season && episode == local.episode
    if (!sameEpisode) return false
    if (isCompleted()) return native.isEmpty() || tvdbWatchedIds.containsAll(localNative)
    return progressPercentage >= local.progressPercentage
}
