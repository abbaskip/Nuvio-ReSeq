package com.nuvio.tv.ui.screens.detail

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.*

internal class SeriesNextToWatch(private val localizedContext: Context) {
    suspend fun resolve(
        contentId: String, contentType: String, name: String, videos: List<Video>, ordered: Boolean,
        defaultVideoId: String?, progressMap: Map<Pair<Int, Int>, WatchProgress>,
        watchedEpisodes: Set<Pair<Int, Int>>, useFurthestEpisode: Boolean,
        watchedTimes: suspend () -> Map<Pair<Int, Int>, Long>
    ): NextToWatch {
        val allEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .filter { it.available != false }
            .sortedWith(episodeSequenceComparator)
            .distinctEpisodeSources()

        if (allEpisodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = contentId,
                nextSeason = null,
                nextEpisode = null,
                displayText = localizedContext.getString(R.string.detail_btn_play)
            )
        }

        val nonSpecialEpisodes = allEpisodes.filter { (it.displaySeason ?: 0) > 0 }
        val episodePool = if (nonSpecialEpisodes.isNotEmpty()) nonSpecialEpisodes else allEpisodes
        if (ordered) {
            val byKey = episodePool.withIndex().associate { (index, video) -> (video.season to video.episode) to index }
            val progress = progressMap.values.filter { (it.season to it.episode) in byKey }
            val anchor = if (useFurthestEpisode) progress.maxByOrNull { byKey[it.season to it.episode] ?: -1 }
                else progress.maxWithOrNull(compareBy<WatchProgress> { it.lastWatched }.thenBy { byKey[it.season to it.episode] ?: -1 })
            val watched = watchedEpisodes.mapNotNull { key ->
                episodePool.firstOrNull { it.season == key.first && it.episode == key.second }
            }.maxByOrNull { byKey[it.season to it.episode] ?: -1 }
            val watchedAnchor = watched?.let { video ->
                WatchProgress(contentId = contentId, contentType = contentType,
                    name = name, poster = null, backdrop = null, logo = null, videoId = video.id,
                    season = video.season, episode = video.episode, episodeTitle = video.title,
                    position = 1, duration = 1, lastWatched = 0)
            }
            val latest = if (useFurthestEpisode && watchedAnchor != null &&
                (byKey[watchedAnchor.season to watchedAnchor.episode] ?: -1) > (byKey[anchor?.season to anchor?.episode] ?: -1)) watchedAnchor
                else anchor ?: watchedAnchor
            return buildNextToWatchFromLatestProgress(latest, episodePool, progressMap, watchedEpisodes,
                contentId, defaultEpisode = episodePool.firstOrNull(), isRewatchMode = !useFurthestEpisode)
        }
        val latestSeriesProgress = if (useFurthestEpisode) {
            // When using furthest episode mode, consider both progressMap entries
            // AND watchedEpisodes (batch marks) to find the furthest watched episode.
            val furthestFromProgress = progressMap.values
                .filter { it.isCompleted() || shouldResumeProgress(it) }
                .maxWithOrNull(
                    compareBy<WatchProgress> { it.season ?: 0 }
                        .thenBy { it.episode ?: 0 }
                )
            val furthestFromWatched = watchedEpisodes
                .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                ?.let { (s, e) ->
                    // Only use watchedEpisodes entry if it's further than progressMap
                    val progressFurthest = furthestFromProgress?.let { (it.season ?: 0) to (it.episode ?: 0) }
                    if (progressFurthest == null || s > progressFurthest.first || (s == progressFurthest.first && e > progressFurthest.second)) {
                        episodePool.firstOrNull { it.season == s && it.episode == e }?.let { video ->
                            WatchProgress(
                                contentId = contentId,
                                contentType = "series",
                                name = "",
                                poster = null, backdrop = null, logo = null,
                                videoId = video.id,
                                season = video.season,
                                episode = video.episode,
                                episodeTitle = video.title,
                                position = 1L, duration = 1L,
                                lastWatched = System.currentTimeMillis(),
                                progressPercent = 100f
                            )
                        }
                    } else null
                }
            furthestFromWatched ?: furthestFromProgress
        } else {
            progressMap.values
                .sortedWith(
                    compareByDescending<WatchProgress> { it.lastWatched }
                        .thenByDescending { it.season ?: 0 }
                        .thenByDescending { it.episode ?: 0 }
                )
                .firstOrNull()
        }
        val effectiveLatestProgress = latestSeriesProgress ?: run {
            if (watchedEpisodes.isEmpty()) null
            else {
                val watchedWithTimestamps = watchedTimes()
                val highest = watchedWithTimestamps.entries
                    .maxWithOrNull(compareBy<Map.Entry<Pair<Int, Int>, Long>> { it.value }
                        .thenBy { it.key.first }
                        .thenBy { it.key.second })
                highest?.let { (key, watchedAt) ->
                    val (s, e) = key
                    episodePool.firstOrNull { it.season == s && it.episode == e }?.let { video ->
                        WatchProgress(
                            contentId = contentId,
                            contentType = "series",
                            name = "",
                            poster = null, backdrop = null, logo = null,
                            videoId = video.id,
                            season = video.season,
                            episode = video.episode,
                            episodeTitle = video.title,
                            position = 1L, duration = 1L,
                            lastWatched = watchedAt,
                            progressPercent = 100f
                        )
                    }
                }
            }
        }
        val defaultEpisode = videos.firstOrNull { it.id == defaultVideoId && it.available != false }?.takeIf { preferred ->
            episodePool.any { it.id == preferred.id }
        }

        return buildNextToWatchFromLatestProgress(
            latestProgress = effectiveLatestProgress,
            episodes = episodePool,
            fallbackProgressMap = progressMap,
            watchedEpisodes = watchedEpisodes,
            metaId = contentId,
            defaultEpisode = defaultEpisode,
            isRewatchMode = !useFurthestEpisode
        )
    }

    private fun buildNextToWatchFromLatestProgress(
        latestProgress: WatchProgress?,
        episodes: List<Video>,
        fallbackProgressMap: Map<Pair<Int, Int>, WatchProgress>,
        watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
        metaId: String,
        defaultEpisode: Video? = null,
        isRewatchMode: Boolean = false
    ): NextToWatch {
        if (episodes.isEmpty()) {
            return NextToWatch(
                watchProgress = null,
                isResume = false,
                nextVideoId = metaId,
                nextSeason = null,
                nextEpisode = null,
                displayText = localizedContext.getString(R.string.detail_btn_play)
            )
        }

        val anchoredProgress = resolveNextToWatchLatestProgress(
            latestProgress = latestProgress,
            progressEntries = fallbackProgressMap.values,
            isResumable = ::shouldResumeProgress
        )

        if (anchoredProgress?.season != null && anchoredProgress.episode != null) {
            val season = anchoredProgress.season
            val episode = anchoredProgress.episode
            val matchedIndex = episodes.indexOfFirst { it.season == season && it.episode == episode }

            if (shouldResumeProgress(anchoredProgress)) {
                val matchedEpisode = if (matchedIndex >= 0) episodes[matchedIndex] else null
                return NextToWatch(
                    watchProgress = anchoredProgress,
                    isResume = true,
                    nextVideoId = matchedEpisode?.id ?: anchoredProgress.videoId,
                    nextSeason = season,
                    nextEpisode = episode,
                    displayText = localizedContext.getString(R.string.detail_btn_resume_episode, season, episode)
                )
            }

            if (anchoredProgress.isCompleted() && matchedIndex >= 0) {
                if (isRewatchMode) {
                    // In rewatch mode, simply take the next episode regardless of watched state
                    val next = episodes.getOrNull(matchedIndex + 1)
                    if (next != null) {
                        return NextToWatch(
                            watchProgress = null,
                            isResume = false,
                            nextVideoId = next.id,
                            nextSeason = next.season,
                            nextEpisode = next.episode,
                            displayText = localizedContext.getString(R.string.detail_btn_next_episode, next.season, next.episode)
                        )
                    }
                } else {
                    // Normal mode: skip already watched episodes
                    val nextUnwatched = episodes.subList(matchedIndex + 1, episodes.size)
                        .firstOrNull { candidate ->
                            val s = candidate.season ?: return@firstOrNull true
                            val e = candidate.episode ?: return@firstOrNull true
                            val progress = fallbackProgressMap[s to e]
                            val isWatched = progress?.isCompleted() == true || (s to e) in watchedEpisodes
                            !isWatched
                        }
                    if (nextUnwatched != null) {
                        return NextToWatch(
                            watchProgress = null,
                            isResume = false,
                            nextVideoId = nextUnwatched.id,
                            nextSeason = nextUnwatched.season,
                            nextEpisode = nextUnwatched.episode,
                            displayText = localizedContext.getString(R.string.detail_btn_next_episode, nextUnwatched.season, nextUnwatched.episode)
                        )
                    }
                }
            }
        }

        var resumeEpisode: Video? = null
        var resumeProgress: WatchProgress? = null
        var nextUnwatchedEpisode: Video? = null

        for (episode in episodes) {
            val season = episode.season ?: continue
            val ep = episode.episode ?: continue
            val progress = fallbackProgressMap[season to ep]

            if (progress != null) {
                if (shouldResumeProgress(progress)) {
                    resumeEpisode = episode
                    resumeProgress = progress
                    break
                } else if (progress.isCompleted()) {
                    continue
                }
            }
            // Check watchedEpisodes — covers both batch marks and episodes
            // that haven't propagated to episodeProgressMap yet.
            if (progress == null && (season to ep) in watchedEpisodes) {
                continue
            }
            if (progress == null) {
                if (nextUnwatchedEpisode == null) {
                    nextUnwatchedEpisode = episode
                }
                if (resumeEpisode == null) {
                    break
                }
            }
        }

        return when {
            resumeEpisode != null && resumeProgress != null -> {
                NextToWatch(
                    watchProgress = resumeProgress,
                    isResume = true,
                    nextVideoId = resumeEpisode.id,
                    nextSeason = resumeEpisode.season,
                    nextEpisode = resumeEpisode.episode,
                    displayText = localizedContext.getString(R.string.detail_btn_resume_episode, resumeEpisode.season, resumeEpisode.episode)
                )
            }
            nextUnwatchedEpisode != null -> {
                val hasWatchedSomething = fallbackProgressMap.isNotEmpty()
                val preferredEpisode = if (hasWatchedSomething) nextUnwatchedEpisode else (defaultEpisode ?: nextUnwatchedEpisode)
                val s = preferredEpisode.season
                val e = preferredEpisode.episode
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = preferredEpisode.id,
                    nextSeason = s,
                    nextEpisode = e,
                    displayText = if (hasWatchedSomething) {
                        localizedContext.getString(R.string.detail_btn_next_episode, s, e)
                    } else {
                        localizedContext.getString(R.string.detail_btn_play_episode, s, e)
                    }
                )
            }
            else -> {
                val firstEpisode = episodes.firstOrNull()
                NextToWatch(
                    watchProgress = null,
                    isResume = false,
                    nextVideoId = firstEpisode?.id ?: metaId,
                    nextSeason = firstEpisode?.season,
                    nextEpisode = firstEpisode?.episode,
                    displayText = if (firstEpisode != null) {
                        localizedContext.getString(R.string.detail_btn_play_episode, firstEpisode.season, firstEpisode.episode)
                    } else {
                        localizedContext.getString(R.string.detail_btn_play)
                    }
                )
            }
        }
    }
}

internal fun shouldResumeProgress(progress: WatchProgress): Boolean {
    if (progress.isCompleted()) return false
    if (progress.progressPercentage >= 0.02f) return true

    val hasStartedPlayback = progress.position > 0L ||
        progress.progressPercent?.let { it > 0f } == true
    return hasStartedPlayback &&
        progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
        progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
}
