package com.nuvio.tv.ui.screens.tvmix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import com.nuvio.tv.LocalContentFocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.net.URLDecoder

/** Uses the app's shared Coil loader/cache; no generated composite bitmaps are stored. */
@Composable
fun TvMixArtwork(encoded: String, title: String, modifier: Modifier = Modifier) {
    val covers = remember(encoded) { encoded.removePrefix("tvmix-art:").split('|')
        .filter(String::isNotBlank).mapNotNull { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.take(4) }
    Box(modifier.background(NuvioTheme.colors.BackgroundCard)) {
        if (covers.isEmpty()) MonochromePosterPlaceholder(contentDescription = title)
        else Row(Modifier.fillMaxSize()) {
            covers.chunked(if (covers.size <= 2) 1 else 2).forEach { column ->
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    column.forEach { cover ->
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            MonochromePosterPlaceholder(contentDescription = null)
                            AsyncImage(model = cover, contentDescription = title,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
        }
        Text(title, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(NuvioTheme.colors.Background.copy(alpha = 0.8f)).padding(10.dp),
            color = NuvioTheme.colors.TextPrimary, maxLines = 2)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddToMixAction(item: MetaPreview, viewModel: TvMixViewModel = hiltViewModel()) {
    var picker by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val mixes by viewModel.mixes.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    Button(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) { Text("Add to mix") }
    if (picker) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(creating) { focus.requestFocus() }
        NuvioDialog(title = "Add ${item.name} to mix", onDismiss = { picker = false; creating = false }) {
            if (state.error != null) Text(state.error!!)
            if (creating) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, singleLine = true,
                    label = { Text("Mix name") }, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                Button(onClick = { viewModel.add(item, null, name) { picker = false; creating = false; name = "" } },
                    enabled = name.isNotBlank() && !state.saving) { Text("Save and add show") }
            } else {
                Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().focusRequester(focus)) { Text("Create new mix") }
                LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(mixes, key = { it.id }) { mix ->
                        val included = mix.shows.any { it.sameShow(TvMixShow(item.id, item.name, imdbId = item.imdbId)) }
                        Button(onClick = { viewModel.add(item, mix.id, "") { picker = false } },
                            enabled = !included && !state.saving, modifier = Modifier.fillMaxWidth()) {
                            Text(mix.name + if (included) " (already added)" else "")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvMixScreen(id: String, manageOnOpen: Boolean, viewModel: TvMixViewModel,
    onPlay: (TvMixSelection) -> Unit, onBack: () -> Unit) {
    val mixes by viewModel.mixes.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mix = mixes.firstOrNull { it.id == id }
    var managing by remember(id) { mutableStateOf(manageOnOpen) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember(mix?.name) { mutableStateOf(mix?.name.orEmpty()) }
    var remove by remember { mutableStateOf<TvMixShow?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val manageFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = LocalContentFocusRequester.current
    val leave = { viewModel.end(); onBack() }
    BackHandler { leave() }
    LaunchedEffect(id) {
        viewModel.open(id)
        withFrameNanos { }
        manageFocusRequester.requestFocus()
    }
    LaunchedEffect(id, state.continuePrompt, state.loading) {
        if (!state.loading && state.selection != null) {
            withFrameNanos { }
            focus.requestFocus()
        }
    }
    LaunchedEffect(state.autoStart, state.selection) {
        if (state.autoStart && state.selection != null) viewModel.play()?.let(onPlay)
    }
    LazyColumn(Modifier.fillMaxSize().focusRequester(contentFocusRequester).focusRestorer()
        .background(NuvioTheme.colors.Background).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text(mix?.name ?: "TV Mix", style = MaterialTheme.typography.headlineLarge) }
        item { Text(if (state.continuePrompt) "Continue mix?" else "Next random episode", style = MaterialTheme.typography.titleLarge) }
        item {
            val selected = state.selection
            if (state.loading) Text("Choosing an episode…")
            else if (selected != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected.meta.name, style = MaterialTheme.typography.headlineSmall)
                val video = selected.video
                Text("S${video.displaySeason ?: video.season} E${video.displayEpisode ?: video.episode} · ${video.title}")
                video.overview?.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 3) }
            }
            state.error?.let { Text(it) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { viewModel.play()?.let(onPlay) }, enabled = state.selection != null && !state.loading,
                    modifier = Modifier.focusRequester(focus)) { Text(if (state.continuePrompt) "Yes" else "Play") }
                Button(onClick = { viewModel.remix() }, enabled = !state.loading) { Text("Remix") }
                if (state.continuePrompt) Button(onClick = leave) { Text("No") }
                Button(onClick = { managing = true }, modifier = Modifier.focusRequester(manageFocusRequester)) { Text("Manage mix") }
                Button(onClick = leave) { Text("Back") }
            }
        }
        item { Text("Included shows · ${mix?.shows?.size ?: 0}", style = MaterialTheme.typography.titleLarge) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(mix?.shows.orEmpty(), key = { it.id }) { show ->
                    Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(onClick = { if (managing) remove = show }, onLongClick = { remove = show }) {
                            Box(Modifier.size(140.dp, 210.dp)) {
                                MonochromePosterPlaceholder(contentDescription = show.name)
                                AsyncImage(model = show.poster, contentDescription = show.name,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                        Text(show.name, maxLines = 2)
                    }
                }
            }
        }
        if (mix?.shows.isNullOrEmpty()) item { Text("Long-press a TV show anywhere in Nuvio and choose Add to mix.") }
    }
    if (managing && mix != null) {
        val manageFocus = remember { FocusRequester() }
        LaunchedEffect(renaming) { manageFocus.requestFocus() }
        NuvioDialog(title = "Manage ${mix.name}", onDismiss = { managing = false; renaming = false }) {
            if (renaming) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("Mix name") },
                    singleLine = true, modifier = Modifier.focusRequester(manageFocus))
                Button(onClick = { viewModel.save(mix.copy(name = name.trim())) { renaming = false } },
                    enabled = name.isNotBlank() && !state.saving) { Text("Save name") }
                Button(onClick = { renaming = false }) { Text("Cancel") }
            } else {
                Button(onClick = { renaming = true }, modifier = Modifier.focusRequester(manageFocus)) { Text("Rename mix") }
                Button(onClick = { viewModel.save(mix.copy(unwatchedOnly = !mix.unwatchedOnly)) { viewModel.remix() } }) {
                    Text(if (mix.unwatchedOnly) "Episodes: unwatched only" else "Episodes: include watched")
                }
                Text("Add shows from their long-press menu. Select a show below to remove it.")
                LazyColumn(Modifier.heightIn(max = 130.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mix.shows, key = { it.id }) { show ->
                        Button(onClick = { remove = show }, modifier = Modifier.fillMaxWidth()) { Text("Remove ${show.name}") }
                    }
                }
                Button(onClick = { deleting = true }) { Text("Delete mix") }
                Button(onClick = { managing = false; renaming = false }) { Text("Done") }
            }
            state.error?.let { Text(it) }
        }
    }
    if (remove != null && mix != null) NuvioDialog(title = "Remove ${remove!!.name}?", onDismiss = { remove = null }) {
        Button(onClick = { viewModel.save(mix.copy(shows = mix.shows.filterNot { it.id == remove?.id })) {
            remove = null; viewModel.remix()
        } }) { Text("Remove show") }
        Button(onClick = { remove = null }) { Text("Cancel") }
    }
    if (deleting && mix != null) NuvioDialog(title = "Delete ${mix.name}?", onDismiss = { deleting = false }) {
        Button(onClick = { viewModel.delete(mix.id) { leave() } }) { Text("Delete mix") }
        Button(onClick = { deleting = false }) { Text("Cancel") }
    }
}
