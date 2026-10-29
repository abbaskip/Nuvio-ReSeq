package com.nuvio.tv.domain.model

import com.google.gson.Gson

/** Immutable episode selection. Search coordinates are deliberately independent
 * of the verified content IDs used by tracking destinations. No stream is selected. */
data class TvdbPlaybackContext(
    val seriesId: Long,
    val orderType: String,
    val episodeId: Long?,
    val season: Int,
    val episode: Int,
    val episodeIds: List<Long>,
    val requestVideoId: String,
    val expectedRuntimeMinutes: Int? = null,
    val providerSources: List<EpisodeSourceIdentity>? = null,
    val episodeRuntimeMinutes: Map<Long, Int>? = null
) {
    fun storageKey(contentId: String) = "${contentId}_tvdb1:$seriesId:$orderType:${episodeId ?: 0}:$season:$episode:${episodeIds.joinToString(".")}:${expectedRuntimeMinutes ?: 0}"
    /** Duration can reject clearly incomplete combined coverage; it cannot identify a part or add IDs. */
    fun permitsTracking(actualDurationMs: Long): Boolean = episodeIds.size <= 1 ||
        (expectedRuntimeMinutes != null && expectedRuntimeMinutes > 0 && actualDurationMs >= expectedRuntimeMinutes * 60_000L * 0.75)

    fun encode(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()
        fun decode(value: String?): TvdbPlaybackContext? = runCatching {
            value?.takeIf(String::isNotBlank)?.let { gson.fromJson(it, TvdbPlaybackContext::class.java) }?.takeIf {
                it.seriesId > 0 && it.orderType.matches(Regex("[A-Za-z0-9_-]+")) && it.season >= 0 && it.episode > 0 &&
                    it.requestVideoId.isNotBlank() && it.episodeIds.all { id -> id > 0 }
            }
        }.getOrNull()

        fun fromStorageKey(contentId: String, key: String, videoId: String): TvdbPlaybackContext? = runCatching {
            val prefix = "${contentId}_tvdb1:"
            if (!key.startsWith(prefix)) return null
            val parts = key.removePrefix(prefix).split(':')
            if (parts.size !in 6..7) return null
            TvdbPlaybackContext(parts[0].toLong(), parts[1], parts[2].toLong().takeIf { it > 0 },
                parts[3].toInt(), parts[4].toInt(), parts[5].split('.').filter(String::isNotBlank).map(String::toLong), videoId, parts.getOrNull(6)?.toIntOrNull()?.takeIf { it > 0 })
        }.getOrNull()
    }
}

fun Video.tvdbPlaybackContext(): TvdbPlaybackContext? = episodeIdentity?.tvdbPlaybackContext(id)

internal fun EpisodeIdentity.tvdbPlaybackContext(requestVideoId: String): TvdbPlaybackContext = TvdbPlaybackContext(
    tvdbSeriesId, orderType, tvdbEpisodeId, displayed.season, displayed.episode, trackingEpisodeIds, requestVideoId,
    trackingEpisodeIds.takeIf { ids -> ids.size > 1 && ids.all { (contentRuntimeMinutes[it] ?: 0) > 0 } }
        ?.sumOf { contentRuntimeMinutes.getValue(it) },
    providerSources, contentRuntimeMinutes)

val WatchProgress.storageKey: String get() = tvdbContext?.storageKey(contentId)
    ?: if (season != null && episode != null) "${contentId}_s${season}e${episode}" else contentId
