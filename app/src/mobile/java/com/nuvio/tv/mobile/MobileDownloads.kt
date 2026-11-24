package com.nuvio.tv.mobile

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DownloadsLink(open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.surface).clickable(role = Role.Button, onClick = open)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Download, null, Modifier.size(20.dp), tint = colors.onSurface)
        Label("Downloads", Modifier.weight(1f), strong = true)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun MobileDownloads(profile: Int, media: MobileOfflineMedia, back: () -> Unit, details: (MobileVideo) -> Unit, play: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by media.changes.collectAsStateWithLifecycle()
    val transfers by media.transfers.collectAsStateWithLifecycle()
    var refresh by remember(profile) { mutableIntStateOf(0) }
    var removing by remember(profile) { mutableStateOf<OfflineFile?>(null) }
    var message by remember(profile) { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(profile) { refresh++; onPauseOrDispose { } }
    val entries by produceState<List<OfflineFile>?>(null, profile, revision, refresh) {
        value = withContext(Dispatchers.IO) { media.saved(profile) }
    }
    val active = transfers.values.filter { it.video.profile == profile && it.active }
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BrowseHeading("Downloads", back)
                MobileCastButton()
            }
        }
        if (active.isNotEmpty()) {
            item { SettingsHeading("Downloading") }
            items(active, key = { "transfer-" + it.video.key }) { transfer ->
                Label(transfer.video.label, strong = true)
                Label(transfer.text, small = true)
            }
        }
        message?.let { item { Label(it) } }
        val saved = entries
        when {
            saved == null -> item { LinearProgressIndicator() }
            saved.isEmpty() -> item { Label("No saved downloads in this profile.", Modifier.padding(vertical = 8.dp)) }
            else -> {
                item {
                    val size = Formatter.formatShortFileSize(context, saved.sumOf { it.length })
                    Label("${saved.size} ${if (saved.size == 1) "download" else "downloads"} · $size on this device", small = true)
                }
                items(saved, key = { it.video.key }) { entry ->
                    Row(Modifier.fillMaxWidth().mediaPanel(), verticalAlignment = Alignment.CenterVertically) {
                        MediaArt(entry.artwork ?: entry.video.poster, Icons.Default.Download, Modifier.padding(start = 6.dp).size(42.dp, 58.dp))
                        Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Play download") {
                            scope.launch {
                                val route = withContext(Dispatchers.IO) { media.localRoute(entry.video) }
                                if (route != null) play(route) else { message = "This file is no longer on the device."; refresh++ }
                            }
                        }.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                            Label(entry.video.label, strong = true, lines = 2)
                            Label(Formatter.formatShortFileSize(context, entry.length), small = true)
                        }
                        IconButton(onClick = { details(entry.video) }) { Icon(Icons.Default.Info, "Details for " + entry.video.label) }
                        IconButton(onClick = { removing = entry }) {
                            Icon(Icons.Default.DeleteOutline, "Delete " + entry.video.label, tint = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    removing?.let { entry ->
        DeleteDownloadDialog(entry, cancel = { removing = null }) {
            removing = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { media.remove(entry) } }
                message = result.exceptionOrNull()?.message
                refresh++
            }
        }
    }
}

@Composable
internal fun SavedTitleDetails(entries: List<OfflineFile>, back: () -> Unit, play: (MobileVideo) -> Unit) {
    val first = entries.first()
    val info = entries.firstNotNullOfOrNull { it.details }
    val ordered = remember(entries) { entries.sortedWith(compareBy({ com.nuvio.tv.domain.model.TvdbPlaybackContext.decode(it.video.tvdb)?.season ?: it.video.season },
        { com.nuvio.tv.domain.model.TvdbPlaybackContext.decode(it.video.tvdb)?.episode ?: it.video.episode })) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BrowseHeading(first.video.title, back) }
        item {
            Row(Modifier.fillMaxWidth().mediaPanel().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaArt(first.artwork ?: first.video.poster, Icons.Default.Download, Modifier.size(88.dp, 128.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("On this device", strong = true)
                    listOfNotNull(info?.release, info?.genres).forEach { Label(it, small = true) }
                    if (first.video.episode == null) info?.runtime?.let { Label(it, small = true) }
                }
            }
        }
        info?.synopsis?.takeIf(String::isNotBlank)?.let { item { Label(it) } }
        items(ordered, key = { it.video.key }) { entry ->
            Column(Modifier.fillMaxWidth().mediaPanel().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (entry.video.episode != null) {
                    Label(entry.video.label.removePrefix(entry.video.title + " "), strong = true)
                    entry.details?.let { details ->
                        val facts = listOfNotNull(details.episodeAirDate, details.runtime).joinToString(" · ")
                        if (facts.isNotBlank()) Label(facts, small = true)
                        details.episodeSynopsis?.let { Label(it) }
                    }
                }
                PrimaryMediaAction("Play download", Modifier.fillMaxWidth()) { play(entry.video) }
            }
        }
    }
}

@Composable
internal fun DeleteDownloadDialog(entry: OfflineFile, cancel: () -> Unit, remove: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, shape = RectangleShape, title = { Label("Delete download?", strong = true) },
        text = { Label("Remove “${entry.video.label}” from this device?") },
        confirmButton = { Action("Delete download", onClick = remove) },
        dismissButton = { Action("Keep", onClick = cancel) })
}
