package com.nuvio.tv.livetv.ondemand

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.ui.SearchResultCard
import com.nuvio.tv.livetv.ui.SearchResultsRow
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.distinctUntilChanged

/** Searches only the imported IPTV library; selecting a result opens its provider details. */
@Composable
fun OnDemandLibrarySearchScreen(
    onBack: () -> Unit,
    onPlay: (url: String, title: String, type: String, poster: String?) -> Unit,
    viewModel: OnDemandLibrarySearchViewModel = hiltViewModel()
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val colors = NuvioTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    var moveToResults by remember { mutableStateOf(false) }
    var providerItem by remember { mutableStateOf<VodItem?>(null) }
    val visibleRows = state.rows.filter { it.items.isNotEmpty() }

    BackHandler { onBack() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        inputFocus.requestFocus()
    }
    LaunchedEffect(moveToResults, visibleRows.firstOrNull()?.items?.firstOrNull()?.uid) {
        if (moveToResults && visibleRows.isNotEmpty()) {
            withFrameNanos { }
            firstResultFocus.requestFocus()
            moveToResults = false
        }
    }

    Column(Modifier.fillMaxSize().background(colors.Background).padding(top = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.vod_search_title), style = MaterialTheme.typography.headlineLarge,
                color = colors.TextPrimary, modifier = Modifier.weight(1f))
            Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = { moveToResults = false; viewModel.queryChanged(it) },
            label = { Text(stringResource(R.string.vod_search_hint), color = colors.TextPrimary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.TextPrimary) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.TextPrimary,
                unfocusedTextColor = colors.TextPrimary, focusedBorderColor = colors.FocusRing),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                moveToResults = true
                viewModel.submit()
            }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 52.dp).focusRequester(inputFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        keyboard?.hide()
                        filterFocus.requestFocus()
                        true
                    } else false
                }
        )
        Row(Modifier.padding(horizontal = 52.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(null to R.string.stream_filter_all, VodKind.MOVIE to R.string.type_movies, VodKind.SERIES to R.string.type_series)
                .forEach { (kind, label) ->
                    Button(onClick = { moveToResults = false; viewModel.selectKind(kind) },
                        modifier = Modifier.then(if (kind == null) Modifier.focusRequester(filterFocus) else Modifier)
                            .focusProperties { up = inputFocus; if (visibleRows.isNotEmpty()) down = firstResultFocus },
                        colors = ButtonDefaults.colors(containerColor = if (state.kind == kind) colors.Secondary else colors.Background)) {
                        Text(stringResource(label))
                    }
                }
        }
        when {
            state.query.trim().length < 2 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.vod_search_start), color = colors.TextPrimary)
            }
            state.loading && visibleRows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            visibleRows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.error) Button(onClick = viewModel::submit) { Text(stringResource(R.string.action_retry)) }
                else EmptyScreenState(title = stringResource(R.string.search_no_results_title),
                    subtitle = stringResource(R.string.vod_search_no_results), icon = Icons.Default.Search)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                itemsIndexed(visibleRows, key = { _, row -> "${state.query}:${state.kind}:${row.playlist.id}" }) { index, row ->
                    LibrarySearchRow(row, if (index == 0) firstResultFocus else null, filterFocus,
                        onLoadMore = { viewModel.loadMore(row.playlist.id) }, onOpen = { providerItem = it })
                }
                if (state.loading) item(key = "search_loading") { LoadingIndicator() }
                if (state.error) item(key = "search_retry") {
                    Button(onClick = viewModel::submit, modifier = Modifier.padding(horizontal = 52.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                item(key = "search_bottom") { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
    providerItem?.let { item ->
        ProviderDetailDialog(item = item, viewModel = hiltViewModel(), onDismiss = { providerItem = null },
            onPlay = { url, title, poster ->
                providerItem = null
                onPlay(url, title, item.kind.key, poster)
            })
    }
}

@Composable
private fun LibrarySearchRow(
    row: VodSearchRow,
    entryFocus: FocusRequester?,
    inputFocus: FocusRequester,
    onLoadMore: () -> Unit,
    onOpen: (VodItem) -> Unit
) {
    val scroll = rememberLazyListState()
    LaunchedEffect(scroll, row.items.size, row.hasMore, row.loading) {
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { last ->
                if (!row.loading && row.hasMore && last >= row.items.size - 8) onLoadMore()
            }
    }
    SearchResultsRow(title = stringResource(R.string.vod_search_playlist, row.playlist.name), listState = scroll) {
        itemsIndexed(row.items, key = { _, item -> item.uid }) { index, item ->
            SearchResultCard(
                title = OnDemandDatabase.displayTitle(item.name),
                lines = listOf(listOfNotNull(stringResource(if (item.kind == VodKind.SERIES) R.string.type_series else R.string.type_movies),
                    item.year?.toString()).joinToString(" · ")),
                poster = item.icon, logo = null,
                modifier = Modifier.then(if (index == 0 && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
                    .focusProperties { if (entryFocus != null) up = inputFocus },
                onClick = { onOpen(item) }
            )
        }
        if (row.loading) item(key = "loading_more") { LoadingIndicator() }
    }
}
