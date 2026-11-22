package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
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
import com.nuvio.tv.ui.screens.search.*
import com.nuvio.tv.ui.screens.settings.TmdbApiKeyError
import com.nuvio.tv.ui.screens.settings.TmdbSettingsViewModel
import com.nuvio.tv.ui.screens.tvmix.TvMixViewModel

@Composable
internal fun MobileDiscover(openTitle: (MetaPreview, VideoAction) -> Unit,
    playMixEpisode: (com.nuvio.tv.domain.model.TvMixSelection) -> Unit,
    vm: AdvancedDiscoverViewModel = hiltViewModel(), mix: TvMixViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<MetaPreview?>(null) }
    val draft = state.draft
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Advanced Discover")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Action("Filters") { sheet = "filters" } }
            item { Action("Saved searches") { sheet = "saved" } }
            item { Action("TV Mix") { sheet = "mixes" } }
            item { Action("TMDB key") { sheet = "key" } }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Label(failureMessage(it), Modifier.padding(8.dp)); Action("Retry", onClick = vm::retry) }
        if (state.items.isEmpty() && !state.loading && state.error == null) {
            if (state.applied == null) Action("Browse movies and shows", onClick = vm::showResults)
            else Label("No matches", Modifier.padding(8.dp))
        }
        LazyVerticalGrid(GridCells.Adaptive(116.dp), contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.items, key = { it.apiType + "/" + it.id }) { item ->
                Column(Modifier.background(MaterialTheme.colorScheme.surface).combinedClickable(
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
                Field("Minimum rating", draft.minimumRating) { vm.edit(draft.copy(minimumRating = it)) }
                Field("From date (YYYY-MM-DD)", draft.dateFrom) { vm.edit(draft.copy(dateFrom = it)) }
                Field("To date (YYYY-MM-DD)", draft.dateTo) { vm.edit(draft.copy(dateTo = it)) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(state.genres, key = { it.id }) { genre ->
                        Action(genre.name, selected = genre.id in draft.genres) {
                            vm.edit(draft.copy(genres = if (genre.id in draft.genres) draft.genres - genre.id else draft.genres + genre.id))
                        }
                    }
                }
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
                items(state.saved, key = { it.id }) { saved -> Action(saved.name) { vm.openSaved(saved); sheet = null } }
                item { Field("Save current filters as", name) { name = it } }
                item { Action("Save search") { if (name.isNotBlank()) vm.save(name, false) } }
                if (state.saveFailed) item { Label("Could not save this search.") }
                state.validationError?.let { item { Label(validationMessage(it)) } }
            }
        }
        "mixes" -> TouchSheet({ mix.end(); sheet = null }) { MixList(mix) { selection ->
            sheet = null
            playMixEpisode(selection)
        } }
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
                val mixes by mix.mixes.collectAsStateWithLifecycle()
                val result by mix.state.collectAsStateWithLifecycle()
                var name by remember(item.id) { mutableStateOf("") }
                SectionTitle("Add to TV Mix")
                mixes.forEach { existing -> Action(existing.name) { mix.add(item, existing.id, existing.name) { detail = null } } }
                Field("New mix name", name) { name = it }
                Action("Create mix with this show") { if (name.isNotBlank()) mix.add(item, null, name) { detail = null } }
                result.error?.let { Label(it) }
            }
        }
    } }
}

@Composable
private fun MixList(vm: TvMixViewModel, play: (com.nuvio.tv.domain.model.TvMixSelection) -> Unit) {
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { SectionTitle("TV Mix") }
        if (mixes.isEmpty()) item { Label("Choose a show in Discover to create a mix.") }
        items(mixes, key = { it.id }) { mix ->
            Action(mix.name, selected = state.mixId == mix.id) { vm.open(mix.id) }
            if (state.mixId == mix.id) {
                Label(mix.shows.joinToString(" · ") { it.name })
                Action("Unwatched only", selected = mix.unwatchedOnly) {
                    vm.save(mix.copy(unwatchedOnly = !mix.unwatchedOnly)) { vm.remix() }
                }
                state.selection?.let { pick ->
                    Label(pick.meta.name + " · " + pick.video.title, strong = true)
                    Action("Play episode") { play(pick) }
                }
                state.error?.let { Label(it) }
                if (state.loading) LinearProgressIndicator()
                else Action("Pick another episode", onClick = vm::remix)
            }
        }
    }
}

@Composable
private fun MobileTmdbKey(vm: TmdbSettingsViewModel = hiltViewModel(), onClose: () -> Unit) {
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
private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), singleLine = true, shape = RectangleShape, label = { Label(label) })
}
