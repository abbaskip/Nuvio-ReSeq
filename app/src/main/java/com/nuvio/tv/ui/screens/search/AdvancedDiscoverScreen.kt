package com.nuvio.tv.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.DiscoveryFailure
import com.nuvio.tv.domain.model.DiscoveryQuery
import com.nuvio.tv.domain.model.DiscoveryValidationError
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.tvTextFieldNavigation
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.text.NumberFormat
import kotlinx.coroutines.launch

@Composable
fun AdvancedDiscoverScreen(
    onBack: () -> Unit,
    onOpenTmdbSettings: () -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    viewModel: AdvancedDiscoverViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val query = state.draft
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingDetailReturn by rememberSaveable { mutableStateOf(false) }
    var restoreGrid by rememberSaveable { mutableStateOf(false) }
    var focusedRevision by rememberSaveable { mutableIntStateOf(0) }
    var gridHasFocus by remember { mutableStateOf(false) }
    val showResultsFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current
    val filterScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!pendingDetailReturn) {
            filterScroll.scrollToItem(0)
            withFrameNanos { }
            runCatching { showResultsFocus.requestFocus() }
        }
    }
    LaunchedEffect(state.validationError) {
        if (state.validationError != null) {
            filterScroll.scrollToItem(0)
            withFrameNanos { }
            runCatching { showResultsFocus.requestFocus() }
        }
    }
    LaunchedEffect(state.items.size) {
        focusedIndex = focusedIndex.coerceIn(0, state.items.lastIndex.coerceAtLeast(0))
    }
    LaunchedEffect(state.resultRevision, state.items.isNotEmpty(), state.applied) {
        if (state.applied == null) focusedRevision = 0
        if (state.items.isNotEmpty() && state.resultRevision > focusedRevision) {
            focusedRevision = state.resultRevision
            focusedIndex = 0
            restoreGrid = true
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingDetailReturn) {
                pendingDetailReturn = false
                restoreGrid = true
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = picker == null && !showSave) {
        if (gridHasFocus) scope.launch {
            filterScroll.scrollToItem(0)
            withFrameNanos { }
            runCatching { showResultsFocus.requestFocus() }
        } else onBack()
    }

    Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.advanced_discover_title), style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary)
            DiscoveryButton(stringResource(R.string.advanced_discover_back), onClick = onBack)
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LazyColumn(Modifier.weight(0.43f), state = filterScroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiscoveryButton(stringResource(R.string.advanced_discover_show_results),
                            Modifier.weight(1f).focusRequester(showResultsFocus), onClick = viewModel::showResults)
                        DiscoveryButton(stringResource(R.string.advanced_discover_reset), onClick = viewModel::reset)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiscoveryButton(stringResource(R.string.advanced_discover_saved), Modifier.weight(1f), onClick = { picker = "saved" })
                        DiscoveryButton(stringResource(R.string.advanced_discover_save), Modifier.weight(1f), onClick = { showSave = true })
                    }
                }
                state.validationError?.let { error -> item { DiscoveryNote(validationMessage(error)) } }
                if (state.savedSuccessfully) item { DiscoveryNote(stringResource(R.string.advanced_discover_save_success)) }
                if (state.saveFailed) item { DiscoveryNote(stringResource(R.string.advanced_discover_save_failure)) }
                item {
                    DiscoveryButton(stringResource(R.string.advanced_discover_type_value,
                        stringResource(if (query.television) R.string.advanced_discover_tv else R.string.advanced_discover_movies)),
                        Modifier.fillMaxWidth(), onClick = { picker = "type" })
                }
                item {
                    DiscoveryButton(stringResource(R.string.advanced_discover_genres_value, query.genres.size), Modifier.fillMaxWidth(),
                        enabled = state.genres.isNotEmpty(), onClick = { picker = "genres" })
                }
                item {
                    DiscoveryButton(stringResource(if (query.matchAllGenres) R.string.advanced_discover_match_all else R.string.advanced_discover_match_any),
                        Modifier.fillMaxWidth(), onClick = { viewModel.edit(query.copy(matchAllGenres = !query.matchAllGenres)) })
                }
                item {
                    DiscoveryButton(stringResource(R.string.advanced_discover_exclude_value, query.excludedGenres.size), Modifier.fillMaxWidth(),
                        enabled = state.genres.isNotEmpty(), onClick = { picker = "exclude" })
                }
                item {
                    DiscoveryNote(stringResource(if (query.television) R.string.advanced_discover_first_aired else R.string.advanced_discover_release_date))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiscoveryField(stringResource(R.string.advanced_discover_from), query.dateFrom, Modifier.weight(1f), placeholder = "YYYY-MM-DD") {
                            viewModel.edit(query.copy(dateFrom = it))
                        }
                        DiscoveryField(stringResource(R.string.advanced_discover_to), query.dateTo, Modifier.weight(1f), placeholder = "YYYY-MM-DD") {
                            viewModel.edit(query.copy(dateTo = it))
                        }
                    }
                }
                item {
                    DiscoveryButton(stringResource(R.string.advanced_discover_date_shortcuts), Modifier.fillMaxWidth(), onClick = { picker = "dates" })
                }
                item {
                    val language = state.languages.firstOrNull { it.code == query.originalLanguage }?.englishName
                        ?: query.originalLanguage.ifBlank { stringResource(R.string.advanced_discover_any) }
                    DiscoveryButton(stringResource(R.string.advanced_discover_language_value, language), Modifier.fillMaxWidth(),
                        enabled = state.languages.isNotEmpty(), onClick = { picker = "language" })
                }
                item {
                    DiscoveryField(stringResource(R.string.advanced_discover_rating), query.minimumRating, keyboardType = KeyboardType.Decimal) {
                        viewModel.edit(query.copy(minimumRating = it))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DiscoveryField(stringResource(R.string.advanced_discover_votes), query.minimumVotes, Modifier.weight(1f), KeyboardType.Number) {
                            viewModel.edit(query.copy(minimumVotes = it))
                        }
                        DiscoveryButton(stringResource(R.string.advanced_discover_presets), onClick = { picker = "votes" })
                    }
                }
                item {
                    DiscoveryNote(stringResource(R.string.advanced_discover_runtime))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiscoveryField(stringResource(R.string.advanced_discover_from), query.runtimeFrom, Modifier.weight(1f), KeyboardType.Number) {
                            viewModel.edit(query.copy(runtimeFrom = it))
                        }
                        DiscoveryField(stringResource(R.string.advanced_discover_to), query.runtimeTo, Modifier.weight(1f), KeyboardType.Number) {
                            viewModel.edit(query.copy(runtimeTo = it))
                        }
                    }
                }
                if (query.television) item {
                    val label = stringResource(when (query.seriesStatus) {
                        "0" -> R.string.advanced_discover_returning
                        "3" -> R.string.advanced_discover_ended
                        "4" -> R.string.advanced_discover_cancelled
                        else -> R.string.advanced_discover_any
                    })
                    DiscoveryButton(stringResource(R.string.advanced_discover_status_value, label), Modifier.fillMaxWidth(), onClick = { picker = "status" })
                }
                item {
                    DiscoveryButton(stringResource(if (query.hideWatched) R.string.advanced_discover_hide_watched_on else R.string.advanced_discover_hide_watched_off),
                        Modifier.fillMaxWidth(), onClick = { viewModel.edit(query.copy(hideWatched = !query.hideWatched)) })
                    DiscoveryNote(stringResource(R.string.advanced_discover_watched_hint))
                }
                item {
                    val sortLabels = discoverySortLabels()
                    DiscoveryButton(stringResource(R.string.advanced_discover_sort_value, sortLabels.getValue(query.sort)),
                        Modifier.fillMaxWidth(), onClick = { picker = "sort" })
                }
                if (state.referenceLoading) item { DiscoveryNote(stringResource(R.string.advanced_discover_loading_options)) }
                state.referenceError?.let { error -> item {
                    DiscoveryNote(failureMessage(error))
                    DiscoveryButton(stringResource(R.string.advanced_discover_retry_options), onClick = viewModel::reloadReferences)
                } }
                item { DiscoveryButton(stringResource(R.string.advanced_discover_show_results), Modifier.fillMaxWidth(), onClick = viewModel::showResults) }
            }

            Column(Modifier.weight(0.57f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DiscoveryNote(stringResource(R.string.advanced_discover_attribution))
                if (state.error == DiscoveryFailure.CONFIGURATION || state.referenceError == DiscoveryFailure.CONFIGURATION) {
                    DiscoveryButton(stringResource(R.string.advanced_discover_configure_tmdb), onClick = onOpenTmdbSettings)
                }
                if (state.applied != null) {
                    DiscoveryNote(stringResource(R.string.advanced_discover_result_count, state.items.size, state.totalResults))
                    if (state.applied != query) DiscoveryNote(stringResource(R.string.advanced_discover_pending_changes))
                }
                state.items.getOrNull(focusedIndex)?.let { focused ->
                    val numbers = NumberFormat.getNumberInstance()
                    DiscoveryNote(stringResource(R.string.advanced_discover_focused_title, focused.name,
                        focused.released.orEmpty(), focused.imdbRating?.let { numbers.format(it) }.orEmpty(),
                        focused.voteCount?.let { numbers.format(it) }.orEmpty()))
                }
                if (state.error != null) {
                    DiscoveryNote(failureMessage(state.error!!))
                    DiscoveryButton(stringResource(R.string.advanced_discover_retry), onClick = viewModel::retry)
                }
                if (state.items.isEmpty()) {
                    when {
                        state.loading || state.filteringWatched -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                        state.applied == null -> DiscoveryNote(stringResource(R.string.advanced_discover_start))
                        state.error == null -> {
                            DiscoveryNote(stringResource(R.string.advanced_discover_empty))
                            if (state.hasMore) DiscoveryButton(stringResource(R.string.advanced_discover_load_more), onClick = viewModel::loadMore)
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).onFocusChanged { gridHasFocus = it.hasFocus }) {
                        DiscoverGrid(
                            items = state.items,
                            posterCardStyle = PosterCardStyle(width = state.cardWidthDp.dp, height = (state.cardWidthDp * 1.5f).dp,
                                cornerRadius = state.cardCornerRadiusDp.dp),
                            focusResults = false, firstItemFocusRequester = firstItemFocus, focusedItemIndex = focusedIndex,
                            shouldRestoreFocusedItem = restoreGrid, onRestoreFocusedItemHandled = { restoreGrid = false },
                            onItemFocused = { focusedIndex = it }, pendingCount = 0,
                            canLoadMore = state.hasMore && state.error == null && !state.filteringWatched,
                            isLoadingMore = state.loading || state.filteringWatched,
                            onLoadMore = viewModel::loadMore,
                            onItemClick = { index, item ->
                                focusedIndex = index
                                pendingDetailReturn = true
                                HeroBackdropState.update(item.backdropUrl)
                                onNavigateToDetail(item.id, item.apiType, "")
                            }, filterKey = state.resultRevision.toString()
                        )
                    }
                }
            }
        }
    }

    if (showSave) {
        var name by rememberSaveable { mutableStateOf(state.saved.firstOrNull { it.id == state.selectedSavedId }?.name.orEmpty()) }
        DiscoveryDialog(stringResource(R.string.advanced_discover_save_title), onDismiss = { showSave = false }) {
            DiscoveryField(stringResource(R.string.advanced_discover_name), name) { name = it }
            DiscoveryButton(stringResource(R.string.advanced_discover_save_new), enabled = name.isNotBlank() && !state.saving, onClick = {
                viewModel.save(name, false)
                showSave = false
            })
            if (state.selectedSavedId != null) DiscoveryButton(stringResource(R.string.advanced_discover_update_saved),
                enabled = name.isNotBlank() && !state.saving, onClick = {
                    viewModel.save(name, true)
                    showSave = false
                })
        }
    }
    when (picker) {
        "type" -> DiscoveryChoices(stringResource(R.string.advanced_discover_type), listOf(
            "movie" to stringResource(R.string.advanced_discover_movies), "tv" to stringResource(R.string.advanced_discover_tv)
        ), query.mediaType, { picker = null }) { viewModel.changeType(it == "tv"); picker = null }
        "genres", "exclude" -> {
            val excluding = picker == "exclude"
            val selected = if (excluding) query.excludedGenres else query.genres
            DiscoveryChoices(stringResource(if (excluding) R.string.advanced_discover_exclude_genres else R.string.advanced_discover_genres),
                state.genres.map { it.id.toString() to it.name }, "", { picker = null }, selected.map { it.toString() }.toSet()) { value ->
                val id = value.toInt()
                val updated = if (id in selected) selected - id else selected + id
                viewModel.edit(if (excluding) query.copy(excludedGenres = updated, genres = query.genres - id)
                    else query.copy(genres = updated, excludedGenres = query.excludedGenres - id))
            }
        }
        "language" -> DiscoveryChoices(stringResource(R.string.advanced_discover_language),
            listOf("" to stringResource(R.string.advanced_discover_any)) + state.languages.map { it.code to it.englishName },
            query.originalLanguage, { picker = null }, searchable = true) {
            viewModel.edit(query.copy(originalLanguage = it)); picker = null
        }
        "votes" -> DiscoveryChoices(stringResource(R.string.advanced_discover_votes),
            listOf("" to stringResource(R.string.advanced_discover_any)) + listOf(50, 100, 500, 1000, 5000, 10000).map { it.toString() to it.toString() },
            query.minimumVotes, { picker = null }) { viewModel.edit(query.copy(minimumVotes = it)); picker = null }
        "status" -> DiscoveryChoices(stringResource(R.string.advanced_discover_status), listOf(
            "" to stringResource(R.string.advanced_discover_any), "0" to stringResource(R.string.advanced_discover_returning),
            "3" to stringResource(R.string.advanced_discover_ended), "4" to stringResource(R.string.advanced_discover_cancelled)
        ), query.seriesStatus, { picker = null }) { viewModel.edit(query.copy(seriesStatus = it)); picker = null }
        "sort" -> DiscoveryChoices(stringResource(R.string.advanced_discover_sort), discoverySortLabels().toList(), query.sort,
            { picker = null }) { viewModel.edit(query.copy(sort = it)); picker = null }
        "dates" -> {
            val today = LocalDate.now()
            val year = today.year
            DiscoveryChoices(stringResource(R.string.advanced_discover_date_shortcuts), listOf(
                "any" to stringResource(R.string.advanced_discover_any),
                "year" to stringResource(R.string.advanced_discover_this_year),
                "five" to stringResource(R.string.advanced_discover_last_five_years)
            ) + (0..8).map { offset -> val decade = (year / 10 - offset) * 10; decade.toString() to "${decade}s" },
                "", { picker = null }) {
                val from = when (it) { "any" -> ""; "year" -> "$year-01-01"; "five" -> today.minusYears(5).toString(); else -> "$it-01-01" }
                val to = when (it) { "any" -> ""; "year", "five" -> today.toString(); else -> minOf(LocalDate.of(it.toInt() + 9, 12, 31), today).toString() }
                viewModel.edit(query.copy(dateFrom = from, dateTo = to)); picker = null
            }
        }
        "saved" -> DiscoveryDialog(stringResource(R.string.advanced_discover_saved), { picker = null }) {
            if (state.saved.isEmpty()) DiscoveryNote(stringResource(R.string.advanced_discover_no_saved))
            LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.saved, key = { it.id }) { saved ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiscoveryButton(saved.name, Modifier.weight(1f), onClick = { viewModel.openSaved(saved); picker = null })
                        DiscoveryButton(stringResource(R.string.advanced_discover_delete), onClick = { viewModel.deleteSaved(saved) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled) { Text(label) }
}

@Composable
private fun DiscoveryNote(message: String) {
    Text(message, color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DiscoveryField(label: String, value: String, modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text, placeholder: String = "", onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth().tvTextFieldNavigation(), singleLine = true,
        label = { Text(label, color = NuvioTheme.colors.TextSecondary) },
        placeholder = { Text(placeholder, color = NuvioTheme.colors.TextSecondary) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.moveFocus(FocusDirection.Next) }),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NuvioTheme.colors.TextPrimary,
            unfocusedTextColor = NuvioTheme.colors.TextPrimary, cursorColor = NuvioTheme.colors.FocusRing,
            focusedBorderColor = NuvioTheme.colors.FocusRing))
}

@Composable
private fun DiscoveryDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(540.dp).heightIn(max = 480.dp).background(NuvioTheme.colors.Background, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                DiscoveryButton(stringResource(R.string.advanced_discover_done), onClick = onDismiss)
            }
            content()
        }
    }
}

@Composable
private fun DiscoveryChoices(title: String, options: List<Pair<String, String>>, selected: String, onDismiss: () -> Unit,
    multiple: Set<String>? = null, searchable: Boolean = false, onSelect: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val visible = options.filter { search.isBlank() || it.second.contains(search, ignoreCase = true) }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    val entryFocus = remember { FocusRequester() }
    DiscoveryDialog(title, onDismiss) {
        if (searchable) DiscoveryField(stringResource(R.string.advanced_discover_find_language), search, Modifier.fillMaxWidth().focusRequester(entryFocus)) { search = it }
        LazyColumn(Modifier.heightIn(max = if (searchable) 270.dp else 340.dp), state = scroll,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.first }) { (value, label) ->
                val checked = if (multiple == null) value == selected else value in multiple
                val initial = !searchable && value == (options.firstOrNull { it.first == selected } ?: options.firstOrNull())?.first
                DiscoveryButton(if (checked) "✓ $label" else label,
                    Modifier.fillMaxWidth().then(if (initial) Modifier.focusRequester(entryFocus) else Modifier),
                    onClick = { onSelect(value) })
            }
        }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { entryFocus.requestFocus() } }
}

@Composable
private fun discoverySortLabels(): Map<String, String> = linkedMapOf(
    "popularity.desc" to stringResource(R.string.advanced_discover_popular_desc),
    "popularity.asc" to stringResource(R.string.advanced_discover_popular_asc),
    "release_date.desc" to stringResource(R.string.advanced_discover_date_desc),
    "release_date.asc" to stringResource(R.string.advanced_discover_date_asc),
    "vote_average.desc" to stringResource(R.string.advanced_discover_rating_desc),
    "vote_average.asc" to stringResource(R.string.advanced_discover_rating_asc),
    "vote_count.desc" to stringResource(R.string.advanced_discover_votes_desc),
    "vote_count.asc" to stringResource(R.string.advanced_discover_votes_asc)
)

@Composable
private fun failureMessage(error: DiscoveryFailure): String = stringResource(when (error) {
    DiscoveryFailure.RATE_LIMIT -> R.string.advanced_discover_rate_limit
    DiscoveryFailure.CONFIGURATION -> R.string.advanced_discover_configuration_error
    DiscoveryFailure.NETWORK -> R.string.advanced_discover_network_error
})

@Composable
private fun validationMessage(error: DiscoveryValidationError): String = stringResource(when (error) {
    DiscoveryValidationError.DATE -> R.string.advanced_discover_invalid_date
    DiscoveryValidationError.DATE_ORDER -> R.string.advanced_discover_invalid_date_order
    DiscoveryValidationError.RATING -> R.string.advanced_discover_invalid_rating
    DiscoveryValidationError.VOTES -> R.string.advanced_discover_invalid_votes
    DiscoveryValidationError.RUNTIME -> R.string.advanced_discover_invalid_runtime
    DiscoveryValidationError.RUNTIME_ORDER -> R.string.advanced_discover_invalid_runtime_order
    DiscoveryValidationError.GENRES -> R.string.advanced_discover_invalid_genres
    DiscoveryValidationError.OPTIONS -> R.string.advanced_discover_invalid_options
})
