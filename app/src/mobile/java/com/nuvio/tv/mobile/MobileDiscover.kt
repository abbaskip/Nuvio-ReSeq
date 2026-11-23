package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.ui.screens.search.*
import com.nuvio.tv.ui.screens.settings.TmdbApiKeyError
import com.nuvio.tv.ui.screens.settings.TmdbSettingsViewModel
import com.nuvio.tv.ui.screens.tvmix.TvMixViewModel

@Composable
internal fun MobileDiscover(openTitle: (MetaPreview, VideoAction) -> Unit,
    openMix: () -> Unit,
    vm: AdvancedDiscoverViewModel = hiltViewModel(), mix: TvMixViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<MetaPreview?>(null) }
    val draft = state.draft
    LaunchedEffect(state.picked) { vm.takePick()?.let { detail = it.first } }
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Advanced Discover")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Action("Filters") { sheet = "filters" } }
            item { Action("Saved searches") { sheet = "saved" } }
            item { Action(if (state.picking) "Picking…" else "Random match", onClick = vm::sendToFlickPick) }
            item { Action("TV Mix", onClick = openMix) }
            item { Action("TMDB key") { sheet = "key" } }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.pickEmpty) Label("No matching titles available.")
        state.pickError?.let { Label(failureMessage(it)) }
        state.error?.let { Label(failureMessage(it), Modifier.padding(8.dp)); Action("Retry", onClick = vm::retry) }
        if (state.items.isEmpty() && !state.loading && state.error == null) {
            if (state.applied == null) Action("Browse movies and shows", onClick = vm::showResults)
            else Label("No matches", Modifier.padding(8.dp))
        }
        LazyVerticalGrid(GridCells.Adaptive(116.dp), contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.items, key = { it.apiType + "/" + it.id }) { item ->
                Column(Modifier.mediaPanel().combinedClickable(
                    onClick = { detail = item }, onLongClickLabel = "Title actions", onLongClick = { detail = item })) {
                    AsyncImage(item.poster, null, Modifier.fillMaxWidth().aspectRatio(2f / 3), contentScale = ContentScale.Crop)
                    Label(item.name, Modifier.padding(6.dp), small = true, lines = 2)
                }
            }
            if (state.hasMore && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Action("More results", onClick = vm::loadMore) }
        }
    }
    when (sheet) {
        "filters" -> TouchSheet({ sheet = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Filters")
                Row {
                    Action("Movies", Modifier.weight(1f), !draft.television) { vm.changeType(false) }
                    Action("Shows", Modifier.weight(1f), draft.television) { vm.changeType(true) }
                }
                Field("Title contains", draft.titleContains) { vm.edit(draft.copy(titleContains = it)) }
                Field("Keywords", draft.keywords) { vm.edit(draft.copy(keywords = it)) }
                Action("Match all title terms", selected = draft.matchAllTitles) { vm.edit(draft.copy(matchAllTitles = !draft.matchAllTitles)) }
                Action("Match all keywords", selected = draft.matchAllKeywords) { vm.edit(draft.copy(matchAllKeywords = !draft.matchAllKeywords)) }
                Field("Minimum rating", draft.minimumRating) { vm.edit(draft.copy(minimumRating = it)) }
                Field("Minimum votes", draft.minimumVotes) { vm.edit(draft.copy(minimumVotes = it)) }
                Field("Minimum runtime (minutes)", draft.runtimeFrom) { vm.edit(draft.copy(runtimeFrom = it)) }
                Field("Maximum runtime (minutes)", draft.runtimeTo) { vm.edit(draft.copy(runtimeTo = it)) }
                DiscoverOptions("Original language", draft.originalLanguage, listOf("" to "Any") + state.languages.map { it.code to it.englishName }) {
                    vm.edit(draft.copy(originalLanguage = it))
                }
                DiscoverOptions("Sort by", draft.sort, DiscoveryQuery.SORTS.map {
                    val (key, direction) = it.split('.')
                    it to ((when (key) { "vote_average" -> "Rating"; "vote_count" -> "Votes"; "release_date" -> "Release date"; else -> "Popularity" }) +
                        if (direction == "asc") " ↑" else " ↓")
                }) { vm.edit(draft.copy(sort = it)) }
                if (draft.television) DiscoverOptions("Series status", draft.seriesStatus,
                    listOf("" to "Any", "0" to "Returning", "3" to "Ended", "4" to "Cancelled")) { vm.edit(draft.copy(seriesStatus = it)) }
                else {
                    DiscoverOptions("Rating country", draft.certificationCountry, DiscoveryQuery.CERTIFICATIONS.keys.map { it to it }) {
                        vm.edit(draft.copy(certificationCountry = it, certification = ""))
                    }
                    DiscoverOptions("Certification", draft.certification,
                        listOf("" to "Any") + DiscoveryQuery.CERTIFICATIONS[draft.certificationCountry].orEmpty().map { it to it }) {
                        vm.edit(draft.copy(certification = it))
                    }
                }
                Field("From date (YYYY-MM-DD)", draft.dateFrom) { vm.edit(draft.copy(dateFrom = it)) }
                Field("To date (YYYY-MM-DD)", draft.dateTo) { vm.edit(draft.copy(dateTo = it)) }
                Label("Include genres")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(state.genres, key = { it.id }) { genre ->
                        Action(genre.name, selected = genre.id in draft.genres) {
                            vm.edit(draft.copy(genres = if (genre.id in draft.genres) draft.genres - genre.id else draft.genres + genre.id,
                                excludedGenres = draft.excludedGenres - genre.id))
                        }
                    }
                }
                Action("Match all included genres", selected = draft.matchAllGenres) { vm.edit(draft.copy(matchAllGenres = !draft.matchAllGenres)) }
                Label("Exclude genres")
                LazyRow {
                    items(state.genres, key = { it.id }) { genre -> Action(genre.name, selected = genre.id in draft.excludedGenres) {
                        vm.edit(draft.copy(excludedGenres = if (genre.id in draft.excludedGenres) draft.excludedGenres - genre.id else draft.excludedGenres + genre.id,
                            genres = draft.genres - genre.id))
                    } }
                }
                state.referenceError?.let { Label(failureMessage(it)); Action("Reload genres and languages", onClick = vm::reloadReferences) }
                Action("Hide watched", selected = draft.hideWatched) { vm.edit(draft.copy(hideWatched = !draft.hideWatched)) }
                state.validationError?.let { Label(validationMessage(it)) }
                Row {
                    Action("Show results") { vm.showResults(); if (vm.state.value.validationError == null) sheet = null }
                    Action("Reset", onClick = vm::reset)
                }
            }
        }
        "saved" -> TouchSheet({ sheet = null }) {
            var name by remember { mutableStateOf("") }
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                item { SectionTitle("Saved searches") }
                items(state.saved, key = { it.id }) { saved ->
                    var confirm by remember(saved.id) { mutableStateOf(false) }
                    Row {
                        Action(saved.name, Modifier.weight(1f), state.selectedSavedId == saved.id) { vm.openSaved(saved); sheet = null }
                        Action(if (confirm) "Confirm delete" else "Delete") { if (confirm) vm.deleteSaved(saved) else confirm = true }
                    }
                }
                item { Field("Save current filters as", name) { name = it } }
                item { Action("Save search") { if (name.isNotBlank()) vm.save(name, false) } }
                state.saved.firstOrNull { it.id == state.selectedSavedId }?.let { saved -> item {
                    Action("Update " + saved.name) { vm.save(name.ifBlank { saved.name }, true) }
                } }
                if (state.savedSuccessfully) item { Label("Search saved") }
                if (state.saveFailed) item { Label("Could not save this search.") }
                state.validationError?.let { item { Label(validationMessage(it)) } }
            }
        }
        "key" -> MobileTmdbKey { sheet = null }
    }
    detail?.let { item -> TouchSheet({ detail = null }) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(item.name)
            Action("Title details") { detail = null; openTitle(item, VideoAction.Details) }
            Action("Play manually") { detail = null; openTitle(item, VideoAction.Manual) }
            Action("Download") { detail = null; openTitle(item, VideoAction.Download) }
            item.releaseInfo?.let { Label(it) }
            item.description?.let { Label(it) }
            if (item.apiType == "series" || item.apiType == "tv") {
                MobileAddToMix(item, mix) { detail = null }
            }
        }
    } }
}

@Composable
internal fun MobileAddToMix(item: MetaPreview, mix: TvMixViewModel, saved: () -> Unit) {
    val mixes by mix.mixes.collectAsStateWithLifecycle()
    val result by mix.state.collectAsStateWithLifecycle()
    var name by remember(item.id) { mutableStateOf("") }
    SectionTitle("Add to TV Mix")
    mixes.forEach { existing -> Action(existing.name) { mix.add(item, existing.id, existing.name, saved) } }
    Field("New mix name", name) { name = it }
    Action("Create mix with this show") { if (name.isNotBlank()) mix.add(item, null, name, saved) }
    result.error?.let { Label(it) }
}

@Composable
internal fun MixList(vm: TvMixViewModel, back: () -> Unit, play: (com.nuvio.tv.domain.model.TvMixSelection) -> Unit) {
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val currentPlay by rememberUpdatedState(play)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.autoStart, state.selection) {
        if (state.autoStart && state.selection != null) vm.play()?.let(currentPlay)
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            BrowseHeading("TV Mix", back)
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.tv_mix_backdrop), null,
                Modifier.fillMaxWidth().height(112.dp), contentScale = ContentScale.Crop)
        }
        if (mixes.isEmpty()) item { Label("Choose a show in Discover to create a mix.") }
        items(mixes, key = { it.id }) { mix ->
            Action(mix.name, selected = state.mixId == mix.id) { vm.open(mix.id) }
            if (state.mixId == mix.id) {
                Label(mix.shows.joinToString(" · ") { it.name })
                Action("Manage mix") { editing = mix.id; confirm = false }
                Action("Unwatched only", selected = mix.unwatchedOnly) {
                    vm.save(mix.copy(unwatchedOnly = !mix.unwatchedOnly)) { vm.remix() }
                }
                state.selection?.let { pick ->
                    Row(Modifier.fillMaxWidth().mediaPanel().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        MediaArt(pick.video.thumbnail ?: pick.meta.backdropUrl, androidx.compose.material.icons.Icons.Default.Movie, Modifier.size(82.dp, 52.dp))
                        Label(pick.meta.name + " · " + pick.video.title, Modifier.weight(1f), strong = true)
                    }
                    Action(if (state.continuePrompt) "Continue mix" else "Play episode") { vm.play()?.let(play) }
                }
                state.error?.let { Label(it) }
                if (state.loading) LinearProgressIndicator()
                else Action("Pick another episode", onClick = vm::remix)
            }
        }
    }
    mixes.firstOrNull { it.id == editing }?.let { mix ->
        var name by remember(mix.id) { mutableStateOf(mix.name) }
        TouchSheet({ editing = null }) {
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                item { SectionTitle("Manage mix"); Field("Name", name) { name = it } }
                item { Action("Save name") { if (name.isNotBlank()) vm.save(mix.copy(name = name.trim())) { editing = null } } }
                items(mix.shows) { show ->
                    Action("Remove " + show.name) { vm.save(mix.copy(shows = mix.shows - show)) { vm.remix() } }
                }
                item { Action(if (confirm) "Confirm delete mix" else "Delete mix") {
                    if (confirm) vm.delete(mix.id) { vm.end(); editing = null } else confirm = true
                } }
                state.error?.let { item { Label(it) } }
            }
        }
    }
}

@Composable
internal fun MobileTmdbKey(vm: TmdbSettingsViewModel = hiltViewModel(), onClose: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var password by remember(state.profileId) { mutableStateOf("") }
    TouchSheet({ vm.cancelApiKeySave(); onClose() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(12.dp)) {
            SectionTitle(stringResource(R.string.tmdb_local_api_key_title))
            OutlinedTextField(password, { password = it }, singleLine = true, shape = RectangleShape,
                enabled = !state.savingApiKey, label = { Label("TMDB API key") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
            state.apiKeyError?.let { Label(stringResource(when (it) {
                TmdbApiKeyError.INVALID -> R.string.tmdb_local_api_key_invalid
                TmdbApiKeyError.NETWORK -> R.string.tmdb_local_api_key_network
                TmdbApiKeyError.RATE_LIMIT -> R.string.tmdb_local_api_key_rate_limit
            })) }
            Action(if (state.savingApiKey) "Checking…" else "Save key") { vm.saveApiKey(password, state.profileId, onClose) }
        }
    }
}

@Composable
private fun DiscoverOptions(label: String, selected: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    Label(label)
    LazyRow { items(options) { (value, title) -> Action(title, selected = selected == value) { change(value) } } }
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), singleLine = true, shape = RectangleShape, label = { Label(label) })
}
