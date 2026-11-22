package com.nuvio.tv.ui.screens.flickpick

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Resources
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.data.repository.DiscoveryFailure
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.localizedMembershipTitle
import com.nuvio.tv.ui.screens.search.DiscoveryChoices
import com.nuvio.tv.ui.screens.search.DiscoveryDialog
import com.nuvio.tv.ui.screens.search.DiscoveryField
import com.nuvio.tv.ui.screens.search.failureMessage
import com.nuvio.tv.ui.screens.search.validationMessage
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate

private enum class Picker { GENRES, ERA, LIST, AGE, DATES }
private val MovieGold = Color(0xFFF7CB79)

@Composable
internal fun FlickPickScreen(
    onPlay: (MetaPreview, Int) -> Unit,
    onTmdbSettings: () -> Unit,
    viewModel: FlickPickViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filters = state.filters
    val invalid = if (state.fromList) null else filters.query().validationError()
    val modeFocus = remember { FocusRequester() }
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    val lists = state.lists.map { it.key to if (it.key == LOCAL_LIBRARY_LIST_KEY) stringResource(R.string.flick_pick_library) else it.tab.localizedMembershipTitle() }
    val failure = state.failure ?: state.genreError.takeUnless { state.fromList }
    val deviceLocale = remember(LocalConfiguration.current) { Resources.getSystem().configuration.locales[0] }
    val region = deviceLocale?.let { it.getUnicodeLocaleType("rg")?.take(2) ?: it.country }.orEmpty()
    LaunchedEffect(region) { viewModel.configureRatings(region) }
    val ratingTitle = state.ratingSystem?.let { stringResource(R.string.flick_pick_age, it.country) }
        ?: stringResource(R.string.flick_pick_age_loading)
    val anyRating = stringResource(R.string.advanced_discover_any)

    LaunchedEffect(Unit) { withFrameNanos { }; if (picker == null) modeFocus.requestFocus() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelPick() } }
    LaunchedEffect(state.pick) { viewModel.takePick()?.let { (movie, profile) -> onPlay(movie, profile) } }

    val canPlay = invalid == null && (!state.fromList || state.libraryLoaded && state.selectedList != null)
    val listTitle = lists.firstOrNull { it.first == state.selectedList }?.second
    Box(Modifier.fillMaxSize()) {
        MovieNightBackdrop(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.flick_pick_feature), style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = MovieGold)
                Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(
                    listOf(MovieGold.copy(alpha = .60f), MovieGold.copy(alpha = .08f)))))
                Button(onClick = { viewModel.reset(); modeFocus.requestFocus() },
                    colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = .10f))) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.advanced_discover_reset))
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                BoxWithConstraints(Modifier.weight(.96f).fillMaxHeight()) {
                    val artworkHeight = minOf(maxWidth * .75f, maxHeight * .60f)
                    Image(painterResource(R.drawable.flick_pick_artwork), contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(artworkHeight))
                    Column(Modifier.fillMaxSize().padding(bottom = artworkHeight + 8.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PickMode(stringResource(R.string.flick_pick_discover_mode), Icons.Default.Movie, !state.fromList,
                                Modifier.weight(1f).focusRequester(modeFocus)) { viewModel.mode(false) }
                            PickMode(stringResource(R.string.flick_pick_lists_mode), Icons.Default.List, state.fromList, Modifier.weight(1f)) {
                                viewModel.mode(true)
                                picker = Picker.LIST
                            }
                        }
                        Button(onClick = { if (state.busy) viewModel.cancelPick() else viewModel.play() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                            enabled = state.busy || canPlay,
                            shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
                            scale = ButtonDefaults.scale(focusedScale = 1.025f),
                            colors = ButtonDefaults.colors(containerColor = MovieGold, contentColor = Color(0xFF261802),
                                focusedContainerColor = Color(0xFFFFE4AA), focusedContentColor = Color(0xFF261802))) {
                            Icon(if (state.busy) Icons.Default.Close else Icons.Default.PlayArrow, null,
                                Modifier.size(29.dp).background(Color.Black.copy(alpha = .11f), CircleShape).padding(3.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(if (state.busy) R.string.action_cancel else R.string.flick_pick_play),
                                style = MaterialTheme.typography.titleLarge)
                            if (!state.busy) {
                                Spacer(Modifier.width(10.dp))
                                Icon(Icons.Default.Movie, null, Modifier.size(22.dp))
                            }
                        }
                        when {
                            state.busy -> Note(stringResource(R.string.flick_pick_working))
                            invalid != null -> Note(validationMessage(invalid))
                            state.empty -> Note(stringResource(if (state.fromList) R.string.flick_pick_empty_list else R.string.flick_pick_empty))
                            failure == null -> Note(stringResource(if (state.fromList) R.string.flick_pick_list_intro else R.string.flick_pick_intro))
                        }
                        if (failure != null) {
                            if (state.fromList && state.selectedList == null) {
                                Note(stringResource(R.string.library_error_refresh_failed))
                                Button(onClick = viewModel::loadLists) { Text(stringResource(R.string.action_retry)) }
                            } else Note(failureMessage(failure))
                            if (failure == DiscoveryFailure.CONFIGURATION) Button(onClick = onTmdbSettings) {
                                Text(stringResource(R.string.advanced_discover_configure_tmdb))
                            }
                        }
                    }
                }
                Column(Modifier.weight(1.18f).verticalScroll(rememberScrollState()).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.fromList) {
                        FilterTile(stringResource(R.string.flick_pick_list),
                            listTitle ?: stringResource(if (state.libraryLoaded) R.string.flick_pick_choose_list else R.string.flick_pick_loading_lists),
                            Icons.Default.List, Modifier.fillMaxWidth(), selected = listTitle != null) { picker = Picker.LIST }
                        if (state.ratingSystem != null) {
                            FilterTile(ratingTitle, state.maximumRating ?: anyRating, Icons.Default.Star,
                                Modifier.fillMaxWidth(), selected = state.maximumRating != null) { picker = Picker.AGE }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            FilterTile(stringResource(R.string.flick_pick_genres),
                                if (filters.genres.isEmpty()) anyRating else stringResource(R.string.flick_pick_selected_count, filters.genres.size),
                                Icons.Default.Movie, Modifier.weight(1f), enabled = state.genres.isNotEmpty(),
                                selected = filters.genres.isNotEmpty()) { picker = Picker.GENRES }
                            FilterTile(ratingTitle, state.maximumRating ?: anyRating, Icons.Default.Star, Modifier.weight(1f),
                                enabled = state.ratingSystem != null, selected = state.maximumRating != null) { picker = Picker.AGE }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            FilterTile(stringResource(R.string.flick_pick_era), eraLabel(filters.era), Icons.Default.DateRange,
                                Modifier.weight(1f), selected = filters.era != FlickPickEra.ALL) { picker = Picker.ERA }
                            DiscoveryField(stringResource(R.string.advanced_discover_keywords), filters.keywords, Modifier.weight(1f),
                                placeholder = anyRating, trigger = { modifier, edit ->
                                    FilterTile(stringResource(R.string.advanced_discover_keywords), filters.keywords.ifBlank { anyRating },
                                        Icons.Default.Search, modifier, selected = filters.keywords.isNotBlank(), onClick = edit)
                                }) { viewModel.filters(filters.copy(keywords = it)) }
                        }
                        Note(stringResource(R.string.flick_pick_keywords_help))
                        if (state.genreError != null) Button(onClick = viewModel::loadGenres) {
                            Text(stringResource(R.string.flick_pick_retry_genres))
                        }
                    }
                }
            }
        }
    }
    val dismiss = { picker = null }
    when (picker) {
        Picker.AGE -> DiscoveryChoices(ratingTitle, listOf("" to anyRating) +
            state.ratingSystem?.ranks.orEmpty().keys.map { it to it }, state.maximumRating.orEmpty(), dismiss) {
            viewModel.maximumRating(it); dismiss()
        }
        Picker.LIST -> if (lists.isEmpty()) {
            DiscoveryDialog(stringResource(R.string.flick_pick_list), dismiss) {
                Note(stringResource(if (state.libraryLoaded) R.string.flick_pick_no_lists else R.string.flick_pick_loading_lists))
                Button(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        } else DiscoveryChoices(stringResource(R.string.flick_pick_list), lists, state.selectedList.orEmpty(), dismiss) {
            viewModel.list(it); dismiss()
        }
        Picker.ERA -> DiscoveryChoices(stringResource(R.string.flick_pick_era), FlickPickEra.entries.map { it.name to eraLabel(it) } +
            ("custom" to stringResource(R.string.flick_pick_custom)),
            filters.era?.name.orEmpty(), dismiss) {
            if (it == "custom") picker = Picker.DATES else {
                viewModel.filters(filters.withEra(FlickPickEra.valueOf(it), LocalDate.now()))
                dismiss()
            }
        }
        Picker.DATES -> DiscoveryDialog(stringResource(R.string.flick_pick_custom), dismiss) {
            DiscoveryField(stringResource(R.string.advanced_discover_from), filters.from, placeholder = "YYYY-MM-DD") {
                viewModel.filters(filters.copy(era = null, from = it))
            }
            DiscoveryField(stringResource(R.string.advanced_discover_to), filters.to, placeholder = "YYYY-MM-DD") {
                viewModel.filters(filters.copy(era = null, to = it))
            }
        }
        Picker.GENRES -> DiscoveryDialog(stringResource(R.string.flick_pick_genres), dismiss) {
            Note(stringResource(R.string.flick_pick_genre_help))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { FlickPickGenre.entries.forEach { GenreMark(it) } }
            val focus = remember { FocusRequester() }
            LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.genres, key = { it.id }) { genre ->
                    Button(onClick = { viewModel.filters(viewModel.state.value.filters.cycle(genre.id)) },
                        modifier = Modifier.fillMaxWidth().then(if (genre == state.genres.first()) Modifier.focusRequester(focus) else Modifier)) {
                        Text(genre.name, Modifier.weight(1f))
                        filters.genres[genre.id]?.let { GenreMark(it) }
                    }
                }
            }
            LaunchedEffect(Unit) { withFrameNanos { }; if (state.genres.isNotEmpty()) focus.requestFocus() }
        }
        null -> Unit
    }
}

@Composable
private fun MovieNightBackdrop(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF260E1B), Color(0xFF141323), Color(0xFF080D1A))))
        drawRect(Brush.radialGradient(
            listOf(Color(0xFF9E512F).copy(alpha = .38f), Color.Transparent),
            center = Offset(size.width * .24f, size.height * .78f), radius = size.width * .57f))
        val scanlineSpacing = 3.dp.toPx()
        for (row in 0..(size.height / scanlineSpacing).toInt()) {
            val y = row * scanlineSpacing
            drawLine(Color.Black.copy(alpha = .12f), Offset(0f, y), Offset(size.width, y))
        }
    }
}

@Composable
private fun PickMode(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.025f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF4A3420) else Color.White.copy(alpha = .08f),
            contentColor = if (selected) Color(0xFFFFDB93) else NuvioTheme.colors.TextPrimary)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FilterTile(label: String, value: String, icon: ImageVector, modifier: Modifier,
    enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 88.dp), enabled = enabled,
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.035f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF482A31) else Color(0xFF292337),
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = Color(0xFF594027), focusedContentColor = Color.White)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MovieGold)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MovieGold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ArrowForward, null, Modifier.size(16.dp))
    }
}

@Composable
private fun Note(text: String) = Text(text, color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall)

@Composable
private fun GenreMark(rule: FlickPickGenre) {
    val (mark, label, color) = when (rule) {
        FlickPickGenre.ANY -> Triple("✓", R.string.flick_pick_any, Color(0xFFEDC650))
        FlickPickGenre.MUST -> Triple("✓", R.string.flick_pick_must, Color(0xFF65CE83))
        FlickPickGenre.EXCLUDE -> Triple("×", R.string.flick_pick_exclude, Color(0xFFF37C79))
    }
    Text("$mark ${stringResource(label)}", Modifier.background(Color(0xFF202124)).padding(horizontal = 5.dp),
        color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun eraLabel(era: FlickPickEra?): String = when {
    era == null -> stringResource(R.string.flick_pick_custom)
    era.yearsBack != null -> stringResource(R.string.flick_pick_recent, era.yearsBack)
    era.firstYear != null && era.lastYear != null -> "${era.firstYear}–${era.lastYear}"
    else -> stringResource(R.string.flick_pick_all_time)
}
