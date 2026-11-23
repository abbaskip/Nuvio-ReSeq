// Touch catalogue, search and library layouts adapted from NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.detail.TvdbOrderControlViewModel
import com.nuvio.tv.ui.screens.library.*
import com.nuvio.tv.ui.screens.search.*

@Composable
internal fun HomeHighlights(titles: List<MetaPreview>, open: (MetaPreview) -> Unit, more: (MetaPreview) -> Unit) {
    val pager = rememberPagerState { titles.size }
    HorizontalPager(pager) { index ->
        val title = titles[index]
        Box(Modifier.fillMaxWidth().heightIn(max = 310.dp).aspectRatio(1.9f)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = { open(title) }, onLongClick = { more(title) })) {
            AsyncImage(title.background ?: title.landscapePoster ?: title.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 10.dp)) {
                Text(title.name, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                Text(listOfNotNull(title.releaseInfo, title.genres.firstOrNull()).joinToString(" · "), color = Color.White)
            }
        }
    }
}

@Composable
internal fun Poster(item: MetaPreview, modifier: Modifier, open: () -> Unit, more: () -> Unit) {
    Column(modifier.combinedClickable(onClick = open, onLongClickLabel = "Title actions", onLongClick = more)) {
        AsyncImage(item.poster, null, Modifier.fillMaxWidth().aspectRatio(2f / 3)
            .background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
        Label(item.name, Modifier.padding(vertical = 4.dp), small = true, lines = 2)
    }
}

@Composable
internal fun MobileSearch(open: (MetaPreview, String?, VideoAction) -> Unit, advanced: () -> Unit, flickPick: () -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var menu by remember { mutableStateOf<Pair<MetaPreview, String?>?>(null) }
    val submit = { vm.onEvent(SearchEvent.SubmitSearch); keyboard?.hide(); Unit }
    LaunchedEffect(vm) { vm.ensureDiscoverLoaded() }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("Search") }
            Action("FlickPick", onClick = flickPick)
            Action("Advanced Discover", onClick = advanced)
        }
        OutlinedTextField(state.query, { vm.onEvent(SearchEvent.QueryChanged(it)) }, Modifier.fillMaxWidth(),
            singleLine = true, shape = RectangleShape, label = { Label("Movies and series") },
            trailingIcon = { TextButton(onClick = submit) { Text("Find") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
        val grid = rememberLazyGridState()
        LaunchedEffect(state.submittedQuery, state.selectedDiscoverCatalogKey) { grid.scrollToItem(0) }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), state = grid, verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (state.query.isBlank()) {
                if (state.recentSearches.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow { items(state.recentSearches) { term -> Action(term) { vm.onEvent(SearchEvent.QueryChanged(term)); submit() } } }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle("Discover")
                    LazyRow { items(state.discoverCatalogs.map { it.type }.distinct()) { type ->
                        Action(type.replaceFirstChar { it.uppercase() }, selected = state.selectedDiscoverType == type) {
                            vm.onEvent(SearchEvent.SelectDiscoverType(type))
                        }
                    } }
                    LazyRow { items(state.discoverCatalogs.filter { it.type == state.selectedDiscoverType }) { catalog ->
                        Action(catalog.catalogName, selected = state.selectedDiscoverCatalogKey == catalog.key) {
                            vm.onEvent(SearchEvent.SelectDiscoverCatalog(catalog.key))
                        }
                    } }
                    val genres = state.discoverCatalogs.find { it.key == state.selectedDiscoverCatalogKey }?.genres.orEmpty()
                    if (genres.isNotEmpty()) LazyRow { items(listOf("All genres") + genres) { genre ->
                        Action(genre, selected = (state.selectedDiscoverGenre ?: "All genres") == genre) {
                            vm.onEvent(SearchEvent.SelectDiscoverGenre(genre.takeUnless { it == "All genres" }))
                        }
                    } }
                }
                val addon = state.discoverCatalogs.find { it.key == state.selectedDiscoverCatalogKey }?.addonBaseUrl
                items(state.discoverResults) { item -> Poster(item, Modifier.fillMaxWidth(),
                    { open(item, addon, VideoAction.Details) }, { menu = item to addon }) }
                if (state.discoverResults.isNotEmpty() && state.discoverHasMore) item(span = { GridItemSpan(maxLineSpan) }) {
                    Action("More titles") { vm.onEvent(SearchEvent.LoadNextDiscoverResults) }
                }
            } else {
                state.catalogRows.forEach { row ->
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(row.catalogName) }
                    items(row.items) { item -> Poster(item, Modifier.fillMaxWidth(),
                        { open(item, row.addonBaseUrl, VideoAction.Details) }, { menu = item to row.addonBaseUrl }) }
                }
                if (state.catalogRows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Label(state.error ?: if (state.isSearching) "Searching…" else if (state.submittedQuery.isBlank()) "Enter a title to search." else "No matching titles.")
                }
            }
        }
    }
    menu?.let { (title, url) -> VideoActions(title.name, { menu = null }) { menu = null; open(title, url, it) } }
}

@Composable
internal fun MobileLibrary(open: (MetaPreview, String?, VideoAction) -> Unit, collections: () -> Unit,
    iptv: () -> Unit, recordings: () -> Unit, party: () -> Unit, cloud: () -> Unit, servers: () -> Unit,
    vm: LibraryViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var filters by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<LibraryEntry?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("Library") }
            Action("Filter and sort") { filters = true }
        }
        LazyRow {
            item { Action("Collections", onClick = collections) }
            item { Action("IPTV library", onClick = iptv) }
            item { Action("Recordings", onClick = recordings) }
            item { Action("Watch Party", onClick = party) }
            item { Action("Cloud files", onClick = cloud) }
            item { Action("Server libraries", onClick = servers) }
        }
        LazyRow { items(state.listTabs) { tab -> Action(tab.title, selected = tab.key == state.selectedListKey) { vm.onSelectListTab(tab.key) } } }
        LazyRow { items(state.availableTypeTabs) { tab -> Action(tab.label, selected = tab == state.selectedTypeTab) { vm.onSelectTypeTab(tab) } } }
        if (state.visibleItems.isEmpty()) Label(state.errorMessage ?: if (state.isLoading) "Loading library…" else
            if (state.allItems.isEmpty()) "Save a movie or series from its details to find it here." else "No titles match these filters.",
            Modifier.padding(12.dp))
        LazyVerticalGrid(GridCells.Adaptive(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.visibleItems) { entry -> Poster(entry.toMetaPreview(), Modifier.fillMaxWidth(),
                { open(entry.toMetaPreview(), entry.addonBaseUrl, VideoAction.Details) }, { menu = entry }) }
        }
    }
    if (filters) TouchSheet({ filters = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            SectionTitle("Sort by")
            state.availableSortOptions.forEach { option -> Action(stringResource(option.labelResId), selected = option == state.selectedSortOption) {
                vm.onSelectSortOption(option); filters = false
            } }
            SectionTitle("Watched")
            LibraryWatchedFilter.entries.forEach { option -> Action(stringResource(option.labelResId), selected = state.selectedWatchedFilter == option) {
                vm.onSelectWatchedFilter(option); filters = false
            } }
            Action("Refresh library") { vm.onRefresh(); filters = false }
        }
    }
    menu?.let { entry -> VideoActions(entry.name, { menu = null }) { menu = null; open(entry.toMetaPreview(), entry.addonBaseUrl, it) } }
}

@Composable
internal fun MobileEpisodeOrder(meta: Meta, changed: () -> Unit, vm: TvdbOrderControlViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Action("Episode order") { vm.open(meta) }
    if (state.open) TouchSheet(vm::close) {
        SectionTitle("Episode order")
        LazyColumn {
            item { Action("Metadata provider", selected = meta.episodeOrder == null) { vm.select(meta, null, changed) } }
            if (state.busy) item { Label("Loading TVDB orders…") }
            items(state.orders) { order -> Action(order.name, selected = meta.episodeOrder?.selectedType == order.type) {
                vm.select(meta, order.type, changed)
            } }
            state.error?.let { item { Label(it); Action("Retry") { vm.open(meta) } } }
            item { Label("Metadata provided by TheTVDB", Modifier.padding(12.dp), small = true) }
        }
    }
}
