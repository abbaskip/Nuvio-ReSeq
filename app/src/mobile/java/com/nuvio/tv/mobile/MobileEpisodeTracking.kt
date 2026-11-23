package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.detail.*
import java.text.DateFormat
import java.util.Date

internal data class MobileEpisodeTracking(
    val displayKey: Pair<Int, Int>?,
    val progress: WatchProgress?,
    val watched: Boolean,
    val canTrack: Boolean,
    val pending: Boolean
)

internal fun List<Video>.ambiguousEpisodeSlots() = groupingBy { it.displaySeason to it.displayEpisode }
    .eachCount().filterValues { it > 1 }.keys

internal fun MetaDetailsUiState.trackingForEpisode(video: Video, ambiguousSlot: Boolean = false): MobileEpisodeTracking {
    val display = video.displaySeason?.let { s -> video.displayEpisode?.let { e -> s to e } }
    val source = video.forEpisodeSource()
    val canTrack = display != null && !ambiguousSlot && video.hasResolvedSource && video.episodeIdentity?.trackingEpisodeIds?.isEmpty() != true
    val progress = if (canTrack) episodeProgressMap[display] else null
    return MobileEpisodeTracking(display, progress, canTrack && (progress?.isCompleted() == true || display in watchedEpisodes),
        canTrack, "${source.id}:${source.season}:${source.episode}" in episodeWatchedPendingKeys)
}

@Composable
internal fun SeasonWatchedAction(season: Int, title: String, watched: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    send: (MetaDetailsEvent) -> Unit) {
    if (watched) MediaIconAction("Mark season unwatched", Icons.Default.RemoveDone, modifier, showLabel = true, enabled = enabled) {
        send(MetaDetailsEvent.OnMarkSeasonUnwatched(season))
    } else MarkWatchedAction("Mark season watched", "$title · Season $season", modifier, enabled) {
        send(MetaDetailsEvent.OnMarkSeasonWatched(season, it))
    }
}

@Composable
internal fun EpisodeSheetActions(episode: Video, tracking: MobileEpisodeTracking, state: MetaDetailsUiState, meta: Meta,
    seasonWatched: Boolean, play: ((VideoAction) -> Unit)?, send: (MetaDetailsEvent) -> Unit,
    remove: (WatchProgress, lastOnly: Boolean) -> Unit, comments: () -> Unit) {
    val full = Modifier.fillMaxWidth()
    val progress = tracking.progress
    val season = episode.displaySeason
    if (play != null) {
        Row(full, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PrimaryMediaAction(if (progress?.isInProgress() == true) "Resume" else "Play", Modifier.weight(1f)) { play(VideoAction.Play) }
            MediaIconAction("Play manually", Icons.Default.Tune, Modifier.weight(1f), showLabel = true) { play(VideoAction.Manual) }
            DownloadAction(modifier = Modifier.weight(1f), showLabel = true) { play(VideoAction.Download) }
        }
        if (progress != null && !progress.isCompleted() && progress.progressPercentage > 0)
            MediaIconAction("Start from beginning", Icons.Default.Replay, full, showLabel = true) { play(VideoAction.Restart) }
    }
    if (tracking.canTrack) {
        SectionTitle("Watch history")
        Row(full, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MarkWatchedAction("Mark as watched", "${meta.name} · ${episode.title}", Modifier.weight(1f), !tracking.watched && !tracking.pending) {
                send(MetaDetailsEvent.OnToggleEpisodeWatched(episode, it))
            }
            MediaIconAction("Mark as unwatched", Icons.Default.RemoveDone, Modifier.weight(1f), showLabel = true,
                enabled = tracking.watched && !tracking.pending) { send(MetaDetailsEvent.OnToggleEpisodeWatched(episode)) }
        }
        val lastWatch = progress?.takeIf { it.hasRemovableWatchEvent() }
        val date = lastWatch?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.lastWatched)) }
        MediaIconAction(if (date == null) "Remove last watch" else stringResource(R.string.episodes_remove_last_watch, date),
            Icons.Default.History, full, showLabel = true, enabled = lastWatch != null && !tracking.pending) {
            lastWatch?.let { remove(it, true) }
        }
        val partial = progress?.takeIf { it.hasRemovableEpisodePause() }
        MediaIconAction(stringResource(R.string.episodes_remove_partial_watch), Icons.Default.Clear, full,
            showLabel = true, enabled = partial != null && !tracking.pending) { partial?.let { remove(it, false) } }
    }
    if (tracking.canTrack || episode.episodeIdentity != null)
        meta.episodeRatingReference(episode)?.let { MobileRating(it, full) }
    if (state.shouldShowCommentsSection)
        MediaIconAction("Episode comments", Icons.Default.Comment, full, showLabel = true, onClick = comments)
    if (season != null && tracking.canTrack) {
        val ready = state.episodeWatchedPendingKeys.isEmpty()
        SectionTitle(if (season == 0) "Specials" else "Season $season")
        SeasonWatchedAction(season, meta.name, seasonWatched, ready, full, send)
        if (state.episodesForSeason.any { it.displaySeason == season && (it.displayEpisode ?: Int.MAX_VALUE) < (episode.displayEpisode ?: 0) })
            MarkWatchedAction(stringResource(R.string.episodes_mark_previous_watched), "${meta.name} · Before ${episode.title}", full, ready) {
                send(MetaDetailsEvent.OnMarkPreviousEpisodesWatched(episode, it))
            }
        if (state.seasons.any { it in 1 until season })
            MarkWatchedAction(stringResource(R.string.episodes_mark_previous_seasons_watched), "${meta.name} · Before season $season", full, ready) {
                send(MetaDetailsEvent.OnMarkPreviousSeasonsWatched(season, it))
            }
    }
}
