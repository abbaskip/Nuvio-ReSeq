package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.detail.*

@Composable
internal fun MobileTrailer(state: MetaDetailsUiState, send: (MetaDetailsEvent) -> Unit) {
    val shared = state.isSharedTrailerOverlayVisible
    val url = if (shared) state.sharedTrailerUrl else state.trailerUrl
    val close = { send(if (shared) MetaDetailsEvent.OnDismissSharedTrailer else MetaDetailsEvent.OnTrailerEnded) }
    var paused by remember(url) { mutableStateOf(false) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var pos by remember(url) { mutableLongStateOf(0L) }
    var length by remember(url) { mutableLongStateOf(0L) }
    var draft by remember(url) { mutableStateOf<Float?>(null) }
    var seek by remember(url) { mutableLongStateOf(0L) }
    var token by remember(url) { mutableIntStateOf(0) }
    TouchSheet(close) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            BrowseHeading(state.selectedSharedTrailer?.name ?: "Trailer", close)
            if (url != null && !failed) TrailerPlayer(trailerUrl = url, trailerAudioUrl = if (shared) state.sharedTrailerAudioUrl else state.trailerAudioUrl,
                isPlaying = true, isPaused = paused, muted = muted, onEnded = close, onError = { failed = true },
                seekRequestToken = token, seekDeltaMs = seek, onProgressChanged = { p, d -> pos = p; length = d },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9))
            if (state.isSharedTrailerLoading || state.isTrailerLoading) Label("Loading trailer…")
            if (failed || state.sharedTrailerErrorMessage != null) {
                Label(state.sharedTrailerErrorMessage ?: "The trailer could not be played.")
                Action("Retry") { failed = false; if (shared) send(MetaDetailsEvent.OnRetrySharedTrailer) }
            }
            if (length > 0) Slider(draft ?: (pos.toFloat() / length).coerceIn(0f, 1f), { draft = it }, onValueChangeFinished = {
                draft?.let { seek = (it * length).toLong() - pos; token++ }; draft = null
            })
            Row { Action(if (paused) "Play" else "Pause") { paused = !paused }; Action(if (muted) "Unmute" else "Mute") { muted = !muted } }
        }
    }
}

@Composable
internal fun MobileComments(state: MetaDetailsUiState, send: (MetaDetailsEvent) -> Unit, close: () -> Unit) {
    TouchSheet(close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { BrowseHeading("Reviews and comments", close) }
            if (state.meta?.videos?.isNotEmpty() == true) item {
                Row { CommentsMode.entries.forEach { mode -> Action(mode.name.lowercase(), selected = state.commentsMode == mode) {
                    send(MetaDetailsEvent.OnCommentsModeSelected(mode))
                } } }
                if (state.commentsMode == CommentsMode.EPISODE) LazyRow { items(state.episodesForSeason) { ep ->
                    Action("S${ep.displaySeason}E${ep.displayEpisode}", selected = ep.id == state.commentsEpisodeTarget?.id) {
                        send(MetaDetailsEvent.OnCommentsEpisodeSelected(ep))
                    }
                } }
            }
            if (state.isCommentsLoading) item { Label("Loading comments…") }
            state.commentsError?.let { item { Label(it); Action("Retry") { send(MetaDetailsEvent.OnRetryComments) } } }
            if (state.comments.isEmpty() && !state.isCommentsLoading && state.commentsError == null) item { Label("No comments yet.") }
            items(state.comments, key = { it.id }) { review ->
                var reveal by remember(review.id) { mutableStateOf(false) }
                Label(review.authorDisplayName + (review.rating?.let { " · $it/10" } ?: ""), strong = true)
                if (!review.hasSpoilerContent || reveal) Label(review.comment)
                else Action("Show spoiler") { reveal = true }
            }
            if (state.commentsCurrentPage < state.commentsPageCount) item {
                Action(if (state.isCommentsLoadingMore) "Loading…" else "More comments") { send(MetaDetailsEvent.OnLoadMoreComments) }
            }
        }
    }
}
