package com.nuvio.tv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.screens.player.*

@Composable
internal fun PlayerIcon(label: String, icon: ImageVector, prominent: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(if (prominent) 58.dp else 48.dp)
        .background(if (prominent) colors.primary else Color.Black.copy(alpha = .5f), CircleShape)) {
        Icon(icon, label, Modifier.size(if (prominent) 32.dp else 24.dp),
            tint = if (prominent) colors.onPrimary else Color.White)
    }
}

internal fun playerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    val tail = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$tail" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$tail"
}

@Composable
internal fun BoxScope.MobileTransport(state: PlayerUiState, timeline: PlaybackTimelineState, scrub: Float?,
    seek: (Float) -> Unit, finishSeek: () -> Unit, downloaded: Boolean, back: () -> Unit, event: (PlayerEvent) -> Unit,
    tool: (String) -> Unit) {
    val shade = Color.Black.copy(alpha = .86f)
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
        .background(Brush.verticalGradient(listOf(shade, Color.Transparent))).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerIcon("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = back)
        Label(state.title, Modifier.weight(1f), strong = true, lines = 2)
        MobileCastButton()
        if (downloaded) Icon(Icons.Default.DownloadDone, "Playing download", tint = Color.White)
    }
    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        PlayerIcon("Rewind 10 seconds", Icons.Default.Replay10) { event(PlayerEvent.OnSeekBy(-10_000)) }
        PlayerIcon(if (state.isPlaying) "Pause" else "Play", if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            prominent = true) { event(PlayerEvent.OnPlayPause) }
        PlayerIcon("Forward 10 seconds", Icons.Default.Forward10) { event(PlayerEvent.OnSeekBy(10_000)) }
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        .background(Brush.verticalGradient(listOf(Color.Transparent, shade, shade))).padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (timeline.duration > 0) {
            val position = scrub?.let { (it * timeline.duration).toLong() } ?: timeline.currentPosition
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(playerTime(position), small = true)
                Slider(scrub ?: (position.toFloat() / timeline.duration).coerceIn(0f, 1f), seek,
                    Modifier.weight(1f), onValueChangeFinished = finishSeek)
                Label(playerTime(timeline.duration), small = true)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
            PlayerIcon("Audio", Icons.Default.Audiotrack) { tool("Audio") }
            PlayerIcon("Subtitles", Icons.Default.Subtitles) { tool("Subtitles") }
            PlayerIcon("Sources", Icons.AutoMirrored.Filled.PlaylistPlay) { event(PlayerEvent.OnShowSourcesPanel) }
            PlayerIcon("Speed", Icons.Default.Speed) { tool("Speed") }
            PlayerIcon("Aspect", Icons.Default.AspectRatio) { event(PlayerEvent.OnToggleAspectRatio) }
            PlayerIcon("Chapters", Icons.Default.Bookmarks) { tool("Chapters") }
            PlayerIcon("Episodes", Icons.Default.VideoLibrary) { event(PlayerEvent.OnShowEpisodesPanel) }
            PlayerIcon("Watch Party", Icons.Default.Groups) { tool("Watch Party") }
        }
    }
}
