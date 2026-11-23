// Artwork-led cards and touch targets adapted from NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.detail.formatReleaseDate

internal val mediaCorners = RoundedCornerShape(5.dp)

@Composable
internal fun Modifier.mediaPanel(selected: Boolean = false): Modifier {
    val c = MaterialTheme.colorScheme
    val fill = lerp(c.background, if (selected) c.primary else c.onSurface, if (selected) .18f else .08f)
    return clip(mediaCorners).background(fill).border(1.dp, if (selected) c.primary else c.outlineVariant, mediaCorners)
}

@Composable
internal fun MediaArt(url: String?, icon: ImageVector, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val c = MaterialTheme.colorScheme
    Box(modifier.background(Brush.linearGradient(listOf(lerp(c.background, c.primary, .24f), c.surface))), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) Icon(icon, null, Modifier.size(26.dp), tint = c.onSurfaceVariant)
        else AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = scale)
    }
}

@Composable
internal fun MediaIconAction(label: String, icon: ImageVector, modifier: Modifier = Modifier,
    showLabel: Boolean = false, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.alpha(if (enabled) 1f else .5f).mediaPanel(selected).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
        Icon(icon, if (showLabel) null else label, Modifier.size(20.dp), tint = if (selected) colors.primary else colors.onSurface)
        if (showLabel) Text(label, color = colors.onSurface, fontFamily = condensed, fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun DownloadAction(label: String = "Download", modifier: Modifier = Modifier, showLabel: Boolean = false, onClick: () -> Unit) {
    MediaIconAction(label, Icons.Default.Download, modifier, showLabel, onClick = onClick)
}

@Composable
internal fun StandardRating(label: String, logo: Int, value: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AsyncImage(logo, label, Modifier.size(29.dp, 16.dp), contentScale = ContentScale.Fit)
        Label(String.format(java.util.Locale.getDefault(), "%.1f", value), small = true, strong = true)
    }
}

@Composable
internal fun TitleBrand(title: String, logo: String?, modifier: Modifier = Modifier) {
    var loaded by remember(logo) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.BottomStart) {
        if (!loaded) Text(title, color = MaterialTheme.colorScheme.onBackground, fontFamily = condensed,
            fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!logo.isNullOrBlank()) AsyncImage(logo, title, Modifier.widthIn(max = 255.dp).fillMaxWidth().height(61.dp),
            alignment = Alignment.BottomStart, contentScale = ContentScale.Fit, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

@Composable
internal fun PrimaryMediaAction(label: String, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(modifier.clip(mediaCorners).background(c.primary).combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = c.onPrimary)
        Text(label, fontFamily = condensed, fontWeight = FontWeight.Bold, color = c.onPrimary, fontSize = 15.sp, maxLines = 2)
    }
}

@Composable
internal fun EpisodeTile(episode: Video, watched: Boolean, progress: Float, rating: Double?, enabled: Boolean,
    open: () -> Unit, highlighted: Boolean = false, actions: @Composable (close: () -> Unit) -> Unit) {
    var options by androidx.compose.runtime.saveable.rememberSaveable(episode.episodeListKey, episode.episodeIdentity?.orderType) { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val surface = lerp(Color(0xff101115), accent, if (highlighted) .28f else .16f)
    val edge = if (highlighted) Modifier.border(1.dp, accent, mediaCorners) else Modifier
    Row(Modifier.fillMaxWidth().clip(mediaCorners).background(surface).then(edge).combinedClickable(
        role = Role.Button, onClickLabel = if (enabled) "Play episode" else "Episode actions",
        onClick = { if (enabled) open() else options = true }, onLongClickLabel = "Episode actions", onLongClick = { options = true })
        .padding(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(98.dp).aspectRatio(1.42f).clip(mediaCorners)) {
            MediaArt(episode.thumbnail, Icons.Default.Movie, Modifier.fillMaxSize())
            val season = episode.displaySeason
            val number = episode.displayEpisode
            if (season != null && number != null) {
                val badge = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"
                Text(badge, Modifier.align(Alignment.TopStart).background(Color.Black).padding(horizontal = 5.dp, vertical = 2.dp),
                    color = Color.White, fontFamily = condensed, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (watched) Icon(Icons.Default.CheckCircle, "Watched", Modifier.align(Alignment.TopEnd).padding(3.dp).size(19.dp)
                .background(Color.Black.copy(alpha = .72f), mediaCorners), tint = Color.White)
            if (progress > 0f && !watched) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Label(episode.title, strong = true, lines = 2)
            val date = remember(episode.released) { episode.released?.let(::formatReleaseDate) }
            val facts = listOfNotNull(date?.takeIf(String::isNotBlank), episode.runtime?.let { "$it min" }, rating?.let { "★ $it" }).joinToString("  ·  ")
            if (facts.isNotBlank()) Label(facts, small = true, lines = 2)
            episode.overview?.takeIf(String::isNotBlank)?.let { Label(it, small = true, lines = 2) }
        }
    }
    if (options) TouchSheet({ options = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowseHeading(episode.title) { options = false }
            episode.overview?.takeIf(String::isNotBlank)?.let { Label(it, Modifier.padding(horizontal = 4.dp)) }
            actions { options = false }
        }
    }
}

@Composable
internal fun SourceTile(stream: Stream, enabled: Boolean, select: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().mediaPanel().combinedClickable(enabled = enabled, onClick = select, onLongClick = select)
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        MediaArt(stream.addonLogo, Icons.Default.VideoLibrary, Modifier.size(44.dp).clip(mediaCorners), ContentScale.Fit)
        SourceText(stream, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = c.primary)
    }
}

@Composable
internal fun SourceText(stream: Stream, modifier: Modifier = Modifier) {
    val title = stream.getDisplayName().trim()
    val details = listOfNotNull(stream.title, stream.description).map(String::trim).filter { it.isNotEmpty() && it != title }.distinct()
    val origin = listOfNotNull(stream.pluginSourceName ?: stream.addonName, stream.quality).filter(String::isNotBlank).distinct().joinToString("  ·  ")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (origin.isNotBlank() && origin != title) Text(origin, fontFamily = condensed, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Label(title, strong = true)
        details.forEach { Label(it, small = true) }
        stream.behaviorHints?.filename?.takeIf { it.isNotBlank() && it !in title && details.none { detail -> it in detail } }
            ?.let { Label(it, small = true) }
    }
}

@Composable
internal fun MobileSourceTabs(addons: List<String>, selected: String?, choose: (String?) -> Unit) {
    if (addons.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 4.dp)) {
        item { Action("All", selected = selected == null) { choose(null) } }
        items(addons.distinct(), key = { "addon-$it" }) { name ->
            Action(name, Modifier.widthIn(max = 220.dp), selected = selected == name) { choose(name) }
        }
    }
}

@Composable
internal fun SeasonRail(meta: Meta, seasons: List<Int>, current: Int, postersEnabled: Boolean, togglePosters: () -> Unit, select: (Int) -> Unit) {
    val images = remember(meta, seasons, postersEnabled) {
        if (postersEnabled) com.nuvio.tv.ui.screens.detail.resolveSeasonPosters(meta, seasons) else null
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionTitle("Seasons") }
        Action("Posters", selected = postersEnabled, onClick = togglePosters)
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(current, seasons) {
        val index = seasons.indexOf(current)
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.scrollToItem(index)
    }
    LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
        items(seasons) { season ->
            val selected = season == current
            val label = if (season == 0) "Specials" else "Season $season"
            if (!postersEnabled) Action(label, selected = selected) { select(season) }
            else Column(Modifier.width(92.dp).mediaPanel(selected).clickable { select(season) }) {
                MediaArt(images?.get(season) ?: meta.videos.firstOrNull { it.displaySeason == season && !it.thumbnail.isNullOrBlank() }?.thumbnail ?: meta.poster, Icons.Default.Movie, Modifier.fillMaxWidth().aspectRatio(.74f))
                Label(label, Modifier.padding(6.dp), strong = selected, lines = 1)
            }
        }
    }
}

@Composable
internal fun TrailerRail(meta: Meta, select: (MetaTrailer) -> Unit) {
    SectionTitle("Trailers")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(meta.trailers) { index, trailer ->
            Column(Modifier.width(146.dp).mediaPanel().clickable { select(trailer) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    val thumbnail = trailer.ytId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }
                    MediaArt(thumbnail ?: meta.backdropUrl, Icons.Default.Movie, Modifier.fillMaxSize())
                    Icon(Icons.Default.PlayCircleFilled, null, Modifier.align(Alignment.Center).size(28.dp), tint = Color.White)
                }
                Label(trailer.name ?: "Trailer ${index + 1}", Modifier.padding(7.dp), small = true, strong = true, lines = 2)
            }
        }
    }
}

