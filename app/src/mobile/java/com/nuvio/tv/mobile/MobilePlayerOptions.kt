package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.*

@Composable
internal fun MobilePlayerOptions(section: String, state: PlayerUiState, send: (PlayerEvent) -> Unit, close: () -> Unit) {
    TouchSheet(close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { BrowseHeading(section, close) }
            when (section) {
                "Speed" -> items(PLAYBACK_SPEEDS) { speed -> Action("${speed}×", selected = speed == state.playbackSpeed) {
                    send(PlayerEvent.OnSetPlaybackSpeed(speed)); close()
                } }
                "Chapters" -> {
                    if (state.chapters.isEmpty()) item { Label("This video has no available chapters.") }
                    itemsIndexed(state.chapters) { index, chapter ->
                        Action(mobilePlaybackTime(chapter.startMs) + " · " + (chapter.title ?: "Chapter ${index + 1}")) { send(PlayerEvent.OnSelectChapter(index)); close() }
                    }
                }
                "Audio" -> {
                    items(state.audioTracks) { track -> Action(track.name, selected = track.isSelected) {
                        send(PlayerEvent.OnSelectAudioTrack(track.index)); close()
                    } }
                    item { Steps("Audio delay", "${state.audioDelayMs} ms",
                        { send(PlayerEvent.OnSetAudioDelayMs(state.audioDelayMs - 50)) }, { send(PlayerEvent.OnSetAudioDelayMs(state.audioDelayMs + 50)) }) }
                    item { Action("Reset audio delay") { send(PlayerEvent.OnSetAudioDelayMs(0)) } }
                }
                "Subtitles" -> {
                    item { Action("Off") { send(PlayerEvent.OnDisableSubtitles); close() } }
                    items(state.subtitleTracks) { track -> Action(track.name, selected = track.isSelected) {
                        send(PlayerEvent.OnSelectSubtitleTrack(track.index)); close()
                    } }
                    items(state.addonSubtitles) { sub -> Action((sub.label ?: sub.lang) + " · " + sub.addonName, selected = sub == state.selectedAddonSubtitle) {
                        send(PlayerEvent.OnSelectAddonSubtitle(sub)); close()
                    } }
                    if (state.isLoadingAddonSubtitles) item { Label("Loading subtitles…") }
                    state.addonSubtitlesError?.let { item { Label(it) } }
                    item { Steps("Subtitle delay", "${state.subtitleDelayMs} ms",
                        { send(PlayerEvent.OnAdjustSubtitleDelay(-50, false)) }, { send(PlayerEvent.OnAdjustSubtitleDelay(50, false)) }) }
                    item { Action("Reset delay") { send(PlayerEvent.OnResetSubtitleDelay(false)) } }
                    val style = state.editableSubtitleStyle
                    item { Steps("Text size", "${style.size}%", { send(PlayerEvent.OnSetSubtitleSize((style.size - 5).coerceAtLeast(50))) },
                        { send(PlayerEvent.OnSetSubtitleSize((style.size + 5).coerceAtMost(200))) }) }
                    item { Steps("Vertical position", "${style.verticalOffset}%", { send(PlayerEvent.OnSetSubtitleVerticalOffset(style.verticalOffset - 1)) },
                        { send(PlayerEvent.OnSetSubtitleVerticalOffset(style.verticalOffset + 1)) }) }
                    item { Action("Bold", selected = style.bold) { send(PlayerEvent.OnSetSubtitleBold(!style.bold)) } }
                    item { Action("Outline", selected = style.outlineEnabled) { send(PlayerEvent.OnSetSubtitleOutlineEnabled(!style.outlineEnabled)) } }
                    item { Action("Reset subtitle style") { send(PlayerEvent.OnResetSubtitleDefaults) } }
                }
            }
        }
    }
}

@Composable
private fun Steps(title: String, value: String, minus: () -> Unit, plus: () -> Unit) {
    Row { Label("$title · $value", Modifier.weight(1f).padding(6.dp)); Action("−", onClick = minus); Action("+", onClick = plus) }
}

@Composable
internal fun MobilePlayerSources(state: PlayerUiState, local: Stream?, send: (PlayerEvent) -> Unit) {
    val episodes = state.showEpisodesPanel
    val streams = !episodes || state.showEpisodeStreams || state.isEpisodeAutoPlaySearching
    val back = {
        send(when {
            episodes && state.showEpisodeStreams -> PlayerEvent.OnBackFromEpisodeStreams
            episodes -> PlayerEvent.OnDismissEpisodesPanel
            else -> PlayerEvent.OnDismissSourcesPanel
        })
    }
    TouchSheet(back) {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item { BrowseHeading(if (streams) "Sources" else "Episodes", back) }
            if (streams) {
                val loading = if (episodes) state.isLoadingEpisodeStreams || state.isEpisodeAutoPlaySearching else state.isLoadingSourceStreams
                val options = if (episodes) state.episodeFilteredStreams else state.sourceFilteredStreams
                val error = if (episodes) state.episodeStreamsError else state.sourceStreamsError
                if (local != null) item { Action("Play downloaded file", Modifier.fillMaxWidth()) {
                    send(if (episodes) PlayerEvent.OnEpisodeStreamSelected(local) else PlayerEvent.OnSourceStreamSelected(local))
                } }
                if (loading) item { Label("Finding sources…") }
                if (error != null) item { Label(error) }
                item { LazyRow { items(listOf<String?>(null) + if (episodes) state.episodeAvailableAddons else state.sourceAvailableAddons) { name ->
                    Action(name ?: "All", selected = name == if (episodes) state.episodeSelectedAddonFilter else state.sourceSelectedAddonFilter) {
                        send(if (episodes) PlayerEvent.OnEpisodeAddonFilterSelected(name) else PlayerEvent.OnSourceAddonFilterSelected(name))
                    }
                } } }
                items(options) { stream -> Action(stream.getDisplayName() + " · " + (stream.description ?: stream.title ?: stream.addonName)) {
                    send(if (episodes) PlayerEvent.OnEpisodeStreamSelected(stream) else PlayerEvent.OnSourceStreamSelected(stream))
                } }
                item { Action("Refresh sources") { send(if (episodes) PlayerEvent.OnReloadEpisodeStreams else PlayerEvent.OnReloadSourceStreams) } }
            } else {
                item { LazyRow { items(state.episodesAvailableSeasons) { season -> Action("Season $season", selected = season == state.episodesSelectedSeason) {
                    send(PlayerEvent.OnEpisodeSeasonSelected(season))
                } } } }
                items(state.episodes) { episode -> Action("${episode.episode ?: ""} · ${episode.title}", selected = episode.id == state.currentVideoId) {
                    send(PlayerEvent.OnEpisodeSelected(episode))
                } }
                if (state.episodes.isEmpty()) item { Label("No episodes available.") }
            }
        }
    }
}
