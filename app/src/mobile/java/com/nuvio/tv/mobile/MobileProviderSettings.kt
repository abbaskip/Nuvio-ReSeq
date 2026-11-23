package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.screens.settings.*

@Composable
internal fun MobileMdbListSettings(mdb: MDBListSettingsViewModel = hiltViewModel(),
    tracker: MdbListTrackerViewModel = hiltViewModel()) {
    val state by mdb.uiState.collectAsStateWithLifecycle()
    val account by tracker.uiState.collectAsStateWithLifecycle()
    val lists by tracker.libraryLists.collectAsStateWithLifecycle()
    val validating by mdb.validating.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var key by remember(state.apiKey) { mutableStateOf(state.apiKey) }
    var mdbMessage by remember { mutableStateOf("") }
    LaunchedEffect(mdb) { mdb.validationError.collect { mdbMessage = "MDBList could not validate the key." } }
    LaunchedEffect(account.isConnected) {
        if (account.isConnected) { tracker.onAccountOpened(); tracker.onLibraryListsOpened() }
    }
    SectionTitle("MDBList")
    if (account.isConnected) {
        account.username?.let { Label(it) }
        Action(if (account.isSyncing) "Syncing…" else "Sync MDBList") { if (!account.isSyncing) tracker.onSyncNow() }
        lists.lists.forEach { option -> TogglePreference(option.name, option.visible) {
            if (lists.pendingKey == null) tracker.onToggleLibraryList(option)
        } }
        if (lists.isLoading) Label("Loading MDBList lists…")
        lists.errorMessage?.let {
            Label(it)
            Action("Reload MDBList lists", onClick = tracker::onLibraryListsOpened)
        }
        Action("Disconnect MDBList", onClick = tracker::onDisconnect)
    } else if (account.session != null) {
        account.session?.let { session ->
            Label(session.userCode, strong = true)
            var showAddress by remember(session.verificationUriComplete) { mutableStateOf(false) }
            Action("Open MDBList sign-in") {
                try { uri.openUri(session.verificationUriComplete) } catch (_: IllegalArgumentException) { showAddress = true }
            }
            if (showAddress) androidx.compose.foundation.text.selection.SelectionContainer {
                Label(session.verificationUriComplete)
            }
            if (!account.isPolling && !account.isLoading) Action("Check MDBList approval", onClick = tracker::onRetryPolling)
            Action("Cancel sign-in", onClick = tracker::onCancel)
        }
    } else if (account.credentialsConfigured) Action(if (account.isLoading) "Connecting…" else "Connect MDBList") {
        if (!account.isLoading) tracker.onConnect()
    } else Label("MDBList account sign-in is unavailable in this build.")
    (account.errorMessage ?: account.statusMessage)?.let { Label(it) }
    var editRatings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Action("MDBList ratings and API key") { editRatings = !editRatings }
    if (!editRatings) return
    MobileSecretField("MDBList API key", key) { key = it; mdbMessage = "" }
    Action(if (validating) "Checking key…" else "Save MDBList key") { if (!validating) mdb.validateAndSaveApiKey(key) { mdbMessage = "Saved" } }
    if (mdbMessage.isNotEmpty()) Label(mdbMessage)
    TogglePreference("Use MDBList ratings", state.enabled) { mdb.onEvent(MDBListSettingsEvent.ToggleEnabled(it)) }
    if (state.enabled) {
        TogglePreference("Trakt", state.showTrakt) { mdb.onEvent(MDBListSettingsEvent.ToggleTrakt(it)) }
        TogglePreference("IMDb", state.showImdb) { mdb.onEvent(MDBListSettingsEvent.ToggleImdb(it)) }
        TogglePreference("TMDB", state.showTmdb) { mdb.onEvent(MDBListSettingsEvent.ToggleTmdb(it)) }
        TogglePreference("Letterboxd", state.showLetterboxd) { mdb.onEvent(MDBListSettingsEvent.ToggleLetterboxd(it)) }
        TogglePreference("Rotten Tomatoes", state.showTomatoes) { mdb.onEvent(MDBListSettingsEvent.ToggleTomatoes(it)) }
        TogglePreference("Audience", state.showAudience) { mdb.onEvent(MDBListSettingsEvent.ToggleAudience(it)) }
        TogglePreference("Metacritic", state.showMetacritic) { mdb.onEvent(MDBListSettingsEvent.ToggleMetacritic(it)) }
        TogglePreference("MyAnimeList", state.showMal) { mdb.onEvent(MDBListSettingsEvent.ToggleMal(it)) }
        TogglePreference("Show ratings on highlights", state.showOnHero) { mdb.onEvent(MDBListSettingsEvent.ToggleShowOnHero(it)) }
        state.ratingOrder.forEachIndexed { index, provider ->
            Row {
                Label(provider, Modifier.weight(1f))
                fun move(direction: Int) { mdb.enterRatingMoveMode(provider); mdb.moveRatingDuringDrag(provider, direction); mdb.confirmRatingMove() }
                if (index > 0) Action("Earlier") { move(-1) }
                if (index < state.ratingOrder.lastIndex) Action("Later") { move(1) }
            }
        }
    }
}

@Composable
internal fun MobileAnimeSkipSettings(anime: AnimeSkipSettingsViewModel = hiltViewModel()) {
    val animeId by anime.clientId.collectAsStateWithLifecycle()
    val useAnime by anime.enabled.collectAsStateWithLifecycle()
    val checkingAnime by anime.validating.collectAsStateWithLifecycle()
    var client by remember(animeId) { mutableStateOf(animeId) }
    var animeMessage by remember { mutableStateOf("") }
    LaunchedEffect(anime) { anime.validationError.collect { animeMessage = "AnimeSkip could not validate the client ID." } }
    TogglePreference("Use AnimeSkip", useAnime, anime::setEnabled)
    MobileSecretField("AnimeSkip client ID", client) { client = it; animeMessage = "" }
    Action(if (checkingAnime) "Checking client ID…" else "Save AnimeSkip client ID") {
        if (!checkingAnime) anime.validateAndSave(client) { animeMessage = "Saved" }
    }
    if (animeMessage.isNotEmpty()) Label(animeMessage)
}
