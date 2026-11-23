package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable internal fun MobileCastButton(casting: MobileCasting = LocalCasting.current) {
    val available by casting.available.collectAsStateWithLifecycle()
    if (available) AndroidView(factory = { context ->
        MediaRouteButton(context).apply {
            contentDescription = "Cast"
            CastButtonFactory.setUpMediaRouteButton(context, this)
        }
    }, modifier = Modifier.size(48.dp))
}

@Composable internal fun MobileCastBar(casting: MobileCasting, sources: (MobileVideo) -> Unit, channels: () -> Unit) {
    val state by casting.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val device = state.device ?: return
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).clickable { expanded = true }
        .padding(horizontal = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CastConnected, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Label(state.item?.title ?: if (state.loading) "Connecting media…" else "Connected to Chromecast", strong = true, lines = 1)
            Label(device, small = true, lines = 1)
        }
        if (state.item != null) CastPlayPause(state, casting)
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.ExpandLess, "Cast controls") }
    }
    if (expanded) TouchSheet({ expanded = false; section = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
            BrowseHeading(state.item?.title ?: device) { expanded = false; section = null }
            Label(device, small = true)
            val item = state.item
            if (item != null) {
                if (!item.live && state.duration > 0) {
                    var draft by remember(item.token) { mutableStateOf<Float?>(null) }
                    val position = draft?.let { (it * state.duration).toLong() } ?: state.position
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(playerTime(position), small = true)
                        Slider(draft ?: (position.toFloat()/state.duration).coerceIn(0f, 1f), { draft = it }, Modifier.weight(1f),
                            onValueChangeFinished = { casting.seek(position); draft = null })
                        Label(playerTime(state.duration), small = true)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (!item.live) PlayerIcon("Rewind 10 seconds", Icons.Default.Replay10) { casting.seek(state.position - 10_000) }
                    CastPlayPause(state, casting)
                    if (!item.live) PlayerIcon("Forward 10 seconds", Icons.Default.Forward10) { casting.seek(state.position + 10_000) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                    item.video?.takeUnless { it.type == "cloud" }?.let { video -> PlayerIcon("Change source", Icons.Default.PlaylistPlay) {
                        expanded = false; section = null; sources(video)
                    } }
                    if (item.iptv && item.video == null) PlayerIcon("Channels", Icons.Default.LiveTv) {
                        expanded = false; section = null; channels()
                    }
                    PlayerIcon("Audio", Icons.Default.Audiotrack) { section = "Audio" }
                    PlayerIcon("Subtitles", Icons.Default.Subtitles) { section = "Subtitles" }
                    if (!item.live) PlayerIcon("Speed", Icons.Default.Speed) { section = "Speed" }
                }
                section?.let { label ->
                    SectionTitle(label)
                    if (label == "Speed") Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(.5, .75, 1.0, 1.25, 1.5, 2.0).forEach { rate -> Action("${rate}×", selected = rate == state.speed) { casting.speed(rate) } }
                    } else {
                        val type = if (label == "Audio") MediaTrack.TYPE_AUDIO else MediaTrack.TYPE_TEXT
                        val tracks = state.tracks.filter { it.type == type }
                        if (tracks.isEmpty()) Label(if (type == MediaTrack.TYPE_AUDIO) "The receiver offers the default audio track." else "No subtitle tracks available.")
                        if (type == MediaTrack.TYPE_TEXT && tracks.isNotEmpty()) Action("Off", selected = tracks.none { it.id in state.selected }) { casting.track(type, null) }
                        tracks.forEach { track -> Action(track.name ?: track.language ?: "Track ${track.id}", selected = track.id in state.selected) {
                            casting.track(type, track.id)
                        } }
                    }
                }
            }
            MediaIconAction("Stop casting", Icons.Default.CastConnected, Modifier.fillMaxWidth(), showLabel = true) {
                expanded = false; section = null; casting.disconnect()
            }
        }
    }
}

@Composable private fun CastPlayPause(state: CastControls, casting: MobileCasting) {
    PlayerIcon(if (state.playing) "Pause" else "Play", if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, onClick = casting::toggle)
}
