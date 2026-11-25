package com.nuvio.tv.ui.screens.tvmix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.nuvio.tv.ui.components.tvTextFieldNavigation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.net.URLDecoder

@Composable
private fun MixButton(label: String, icon: ImageVector?, modifier: Modifier = Modifier, primary: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    val colors = NuvioTheme.colors
    Button(onClick = onClick, modifier = modifier, enabled = enabled,
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.03f),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) colors.MutedAccent else colors.TintedControl,
            contentColor = if (primary) colors.TextInverse else colors.TextPrimary,
            focusedContainerColor = colors.PaleFocus,
            focusedContentColor = colors.TextInverse
        )) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (primary) 23.dp else 18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

/** Uses the app's shared Coil loader/cache; no generated composite bitmaps are stored. */
@Composable
fun TvMixArtwork(encoded: String, title: String? = null, modifier: Modifier = Modifier) {
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
        if (title != null) {
            Text(title, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(NuvioTheme.colors.Background.copy(alpha = 0.8f)).padding(10.dp),
                color = NuvioTheme.colors.TextPrimary, maxLines = 2)
        }
    }
}

@Composable
internal fun TvMixNameField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = value, onValueChange = { onChange(it.take(100)) }, singleLine = true,
        label = { Text("Mix name", color = NuvioTheme.colors.TextSecondary) },
        modifier = modifier.tvTextFieldNavigation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.moveFocus(FocusDirection.Down) }),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NuvioTheme.colors.TextPrimary,
            unfocusedTextColor = NuvioTheme.colors.TextPrimary, cursorColor = NuvioTheme.colors.FocusRing,
            focusedBorderColor = NuvioTheme.colors.FocusRing))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddToMixAction(item: MetaPreview) {
    var picker by remember { mutableStateOf(false) }
    Button(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) { Text("Add to mix") }
    if (picker) TvMixPicker(item, onDismiss = { picker = false })
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvMixPicker(item: MetaPreview, onDismiss: () -> Unit, viewModel: TvMixViewModel = hiltViewModel()) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val mixes by viewModel.mixes.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(creating) { focus.requestFocus() }
    NuvioDialog(title = "Add ${item.name} to mix", onDismiss = { onDismiss() }) {
        if (state.error != null) Text(state.error!!)
        if (creating) {
            TvMixNameField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus))
            Button(onClick = { viewModel.add(item, null, name) { onDismiss() } },
                enabled = name.isNotBlank() && !state.saving) { Text("Save and add show") }
        } else {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().focusRequester(focus)) { Text("Create new mix") }
            LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(mixes, key = { it.id }) { mix ->
                    val included = mix.shows.any { it.sameShow(TvMixShow(item.id, item.name, imdbId = item.imdbId)) }
                    Button(onClick = { viewModel.add(item, mix.id, "") { onDismiss() } },
                        enabled = !included && !state.saving, modifier = Modifier.fillMaxWidth()) {
                        Text(mix.name + if (included) " (already added)" else "")
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
    val colors = NuvioTheme.colors
    Column(
        Modifier.fillMaxSize().focusRequester(contentFocusRequester).focusRestorer()
            .background(colors.TintedBackground),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, top = 20.dp, end = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(40.dp), tint = colors.MutedAccent)
            Column(Modifier.weight(1f)) {
                if (mix != null) Text("TV Mix", style = MaterialTheme.typography.labelLarge, color = colors.MutedAccent)
                Text(mix?.name ?: "TV Mix", style = MaterialTheme.typography.headlineMedium, color = colors.TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            MixButton("Manage mix", Icons.Default.Settings, modifier = Modifier.focusRequester(manageFocusRequester),
                enabled = mix != null) { managing = true }
            MixButton("Back", null, onClick = leave)
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                val selection = state.selection
                val artwork = selection?.video?.thumbnail ?: selection?.meta?.background
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.TintedPanel).heightIn(min = 252.dp)) {
                    AsyncImage(model = artwork ?: R.drawable.tv_mix_backdrop, contentDescription = null,
                        error = painterResource(R.drawable.tv_mix_backdrop),
                        modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
                        alignment = Alignment.CenterEnd)
                    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                        0f to colors.TintedPanel, .35f to colors.TintedPanel.copy(alpha = .94f), .73f to colors.TintedPanel.copy(alpha = .61f),
                        1f to colors.TintedPanel.copy(alpha = .18f)
                    )))
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                        0f to Color.Transparent, .58f to Color.Transparent, 1f to colors.TintedPanel
                    )))
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (state.continuePrompt) "Continue mix?" else "Next random episode",
                            style = MaterialTheme.typography.labelLarge, color = colors.MutedAccent)
                        if (state.loading) {
                            Text("Choosing an episode…", color = colors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
                        } else if (selection != null) {
                            Text(selection.meta.name, color = colors.TextPrimary, style = MaterialTheme.typography.headlineLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val video = selection.video
                            val season = video.displaySeason ?: video.season
                            val episode = video.displayEpisode ?: video.episode
                            val episodeTitle = if (season != null && episode != null) "S$season E$episode · ${video.title}" else video.title
                            Text(episodeTitle, color = colors.MutedAccent, style = MaterialTheme.typography.titleMedium)
                            video.overview?.takeIf(String::isNotBlank)?.let { overview ->
                                Text(overview, modifier = Modifier.fillMaxWidth(.68f), color = colors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        state.error?.let { Text(it, color = colors.Error, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MixButton(if (state.continuePrompt) "Yes" else "Play", Icons.Default.PlayArrow,
                                modifier = Modifier.focusRequester(focus), primary = true,
                                enabled = selection != null && !state.loading) { viewModel.play()?.let(onPlay) }
                            MixButton("Remix", Icons.Default.Shuffle, enabled = !state.loading, onClick = viewModel::remix)
                            if (state.continuePrompt) MixButton("No", null, onClick = leave)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Included shows · ${mix?.shows?.size ?: 0}", style = MaterialTheme.typography.titleLarge, color = colors.TextPrimary)
                    if (mix != null) Text(if (mix.unwatchedOnly) "Unwatched only" else "Includes watched",
                        color = colors.MutedAccent, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (mix?.shows.isNullOrEmpty()) {
                item { Text("Long-press a TV show anywhere in Nuvio and choose Add to mix.", color = colors.TextSecondary) }
            } else item {
                LazyRow(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(mix?.shows.orEmpty(), key = { it.id }) { show ->
                        Column(Modifier.width(124.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Card(onClick = { if (managing) remove = show }, onLongClick = { remove = show }) {
                                Box(Modifier.size(124.dp, 186.dp)) {
                                    MonochromePosterPlaceholder(contentDescription = show.name)
                                    AsyncImage(model = show.poster, contentDescription = show.name,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            }
                            Text(show.name, color = colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    if (managing && mix != null) {
        val manageFocus = remember { FocusRequester() }
        LaunchedEffect(renaming) { manageFocus.requestFocus() }
        NuvioDialog(title = "Manage ${mix.name}", onDismiss = { managing = false; renaming = false }) {
            if (renaming) {
                TvMixNameField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(manageFocus))
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
