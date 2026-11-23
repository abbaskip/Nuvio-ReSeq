package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.screens.detail.PersonalRatingsViewModel
import com.nuvio.tv.ui.screens.player.*

@Composable
internal fun MobileMovieRating(request: MovieRatingRequest, close: () -> Unit,
    vm: PersonalRatingsViewModel = hiltViewModel(key = "completed-movie-rating")) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val enabled by vm.promptEnabled.collectAsStateWithLifecycle()
    val profile by vm.activeProfileId.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val target = remember(request) { personalRatingTarget(request.contentId, "movie") }
    val eligible = connected.orEmpty().filter { target?.supports(it) == true }
    val dismiss = { vm.close(); close() }
    LaunchedEffect(request, connected, enabled, profile) {
        when {
            target == null || request.profileId != profile || enabled == false -> dismiss()
            enabled == null || connected == null -> Unit
            eligible.isEmpty() -> dismiss()
            !state.open -> vm.open(target)
        }
    }
    DisposableEffect(vm) { onDispose { vm.close() } }
    if (state.open && profile == request.profileId) TouchSheet(dismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            BrowseHeading("Rate " + request.title, dismiss)
            Label(eligible.joinToString { it.label })
            var score by rememberSaveable(request.contentId) { mutableStateOf<Int?>(null) }
            LaunchedEffect(state.results) {
                if (!state.saved) score = state.results.filter { it.confirmed }.mapNotNull { it.rating }.distinct().singleOrNull()
            }
            state.results.forEach {
                Label(it.provider.label + " · " + when {
                    !it.confirmed -> "Could not confirm rating"
                    it.rating == null -> "Unrated"
                    else -> it.rating.toString() + "/10"
                })
            }
            if (state.busy) Label("Contacting trackers…")
            state.error?.let { Label(it) }
            if (!state.busy && state.results.isNotEmpty()) {
                for (start in listOf(1, 6)) Row {
                    for (n in start until start + 5) Action(n.toString(), Modifier.weight(1f), n == score) { score = n }
                }
                if (score != null) Action("Save rating") { vm.write(score) }
                Action("Remove rating") { vm.write(null) }
                if (state.saved && state.results.all { it.confirmed }) Label("Saved")
            }
        }
    }
}

@Composable
internal fun MobileRecommendations(state: PostPlayRecommendationUiState, vm: PlayerViewModel, exit: () -> Unit,
    open: (PostPlayRecommendation, VideoAction) -> Unit) {
    val item = state.recommendation ?: return
    val trailerNetwork = LocalTrailerNetwork.current
    val trailersAllowed by trailerNetwork.playbackAllowed.collectAsStateWithLifecycle()
    LaunchedEffect(trailersAllowed, state.isTrailerPlaying) {
        if (!trailersAllowed && state.isTrailerPlaying) vm.onPostPlayTrailerEnded()
    }
    fun dismiss() {
        if (state.canReturnToPlayer && !vm.uiState.value.playbackEnded) vm.returnToPlayerFromPostPlay() else exit()
    }
    TouchSheet(::dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
            BrowseHeading("Watch next", ::dismiss)
            SectionTitle(item.title)
            item.description?.let { Label(it, lines = 4) }
            if (!trailersAllowed && item.hasTrailer) Label(TRAILER_DATA_MESSAGE, small = true)
            else if (state.isTrailerPlaying && item.hasTrailer) {
                TrailerPlayer(trailerUrl = item.trailerVideoUrl, trailerAudioUrl = item.trailerAudioUrl,
                    isPlaying = true, onEnded = vm::onPostPlayTrailerEnded, onError = vm::onPostPlayTrailerEnded,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9))
                Action("Stop trailer", onClick = vm::onPostPlayTrailerEnded)
            } else if (item.hasTrailer) Action("Play trailer", onClick = vm::playPostPlayTrailer)
            Action("Play") { open(item, VideoAction.Play) }
            Action("Play manually") { open(item, VideoAction.Manual) }
            Action("Title details") { open(item, VideoAction.Details) }
            Row {
                if (state.canNavigatePrevious) Action("Previous", onClick = vm::showPreviousPostPlayRecommendation)
                if (state.canNavigateNext) Action("Next", onClick = vm::showNextPostPlayRecommendation)
            }
        }
    }
}
