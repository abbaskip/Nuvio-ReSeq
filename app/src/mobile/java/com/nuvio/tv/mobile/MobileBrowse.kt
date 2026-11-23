// Touch catalogue, search and library layouts adapted from NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.detail.TvdbOrderControlViewModel
import com.nuvio.tv.ui.screens.library.*
import com.nuvio.tv.ui.screens.search.*

@Composable
internal fun MobileSearch(open: (MetaPreview, String?, VideoAction) -> Unit, advanced: () -> Unit, flickPick: () -> Unit,
    guide: GuideLookup? = null, back: () -> Unit = {}, onGuideMatch: (MetaPreview, String?) -> Unit = { _, _ -> }, vm: SearchViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var menu by remember { mutableStateOf<Pair<MetaPreview, String?>?>(null) }
    var removeRecent by remember { mutableStateOf<String?>(null) }
    val submit = { vm.onEvent(SearchEvent.SubmitSearch); keyboard?.hide(); Unit }
    var autoOpen by rememberSaveable(guide) { mutableStateOf(guide != null) }
    LaunchedEffect(vm, guide) {
        if (guide == null) vm.ensureDiscoverLoaded()
        else if (state.submittedQuery.isEmpty()) {
            vm.onEvent(SearchEvent.QueryChanged(guide.query))
            vm.onEvent(SearchEvent.SubmitSearch)
        }
    }
    LaunchedEffect(guide, state.submittedQuery, state.isSearching, state.catalogRows, autoOpen) {
        if (guide != null && autoOpen && state.submittedQuery == guide.query && !state.isSearching) {
            guide.exactMatch(state.catalogRows)?.let { (item, addon) ->
                autoOpen = false
                onGuideMatch(item, addon)
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        if (guide != null) BrowseHeading("Find in Nuvio", back) else Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("Search") }
            Action("Flick Pick", onClick = flickPick)
            Action("Advanced Discover", onClick = advanced)
        }
        OutlinedTextField(state.query, { autoOpen = false; vm.onEvent(SearchEvent.QueryChanged(it)) }, Modifier.fillMaxWidth(),
            singleLine = true, shape = RectangleShape, label = { Label("Movies and series") },
            trailingIcon = { TextButton(onClick = submit) { Text("Find") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
        val grid = rememberLazyGridState()
        LaunchedEffect(state.submittedQuery, state.selectedDiscoverCatalogKey) { grid.scrollToItem(0) }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), state = grid, verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (state.query.isBlank()) {
                if (state.recentSearches.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow {
                        items(state.recentSearches) { term -> Box(Modifier.combinedClickable(
                            onClick = { vm.onEvent(SearchEvent.QueryChanged(term)); submit() },
                            onLongClickLabel = "Remove search", onLongClick = { removeRecent = term }
                        ).heightIn(min = 48.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Label(term) } }
                        item { Action("Clear history") { removeRecent = "" } }
                    }
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
                if (state.suggestions.isNotEmpty() && state.query != state.submittedQuery) item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow { items(state.suggestions) { suggestion -> Action(suggestion) { vm.onEvent(SearchEvent.QueryChanged(suggestion)); submit() } } }
                }
                state.catalogRows.forEach { row ->
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(row.catalogName) }
                    items(row.items) { item -> Poster(item, Modifier.fillMaxWidth(),
                        { autoOpen = false; open(item, row.addonBaseUrl, VideoAction.Details) }, { autoOpen = false; menu = item to row.addonBaseUrl }) }
                    if (row.hasMore && row.supportsSkip) item(span = { GridItemSpan(maxLineSpan) }) {
                        Action("More from " + row.catalogName) { vm.onEvent(SearchEvent.LoadMoreCatalog(row.catalogId, row.addonId, row.apiType)) }
                    }
                }
                if (state.catalogRows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Label(state.error ?: if (state.isSearching) "Searching…" else if (state.submittedQuery.isBlank()) "Enter a title to search." else "No matching titles.")
                }
            }
        }
    }
    menu?.let { (title, url) -> VideoActions(title.name, { menu = null }) { menu = null; open(title, url, it) } }
    removeRecent?.let { term -> ConfirmAction(if (term.isBlank()) "Clear search history?" else "Remove " + term + "?", "Remove", { removeRecent = null }) {
        vm.onEvent(if (term.isBlank()) SearchEvent.ClearRecentSearches else SearchEvent.RemoveRecentSearch(term))
        removeRecent = null
    } }
}

@Composable
internal fun MobileLibrary(open: (MetaPreview, String?, VideoAction) -> Unit, collections: () -> Unit,
    iptv: () -> Unit, recordings: () -> Unit, party: () -> Unit, cloud: () -> Unit, servers: () -> Unit, mix: () -> Unit, downloads: () -> Unit,
    vm: LibraryViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var filters by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<LibraryEntry?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("Library") }
            if (state.listManagement != null && state.isTrackingAuthenticated) Action("Manage lists", onClick = vm::onOpenManageLists)
            Action("Filter and sort") { filters = true }
        }
        DownloadsLink(downloads)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            item { LibraryShortcut("Collections", Icons.Default.Collections, collections) }
            item { LibraryShortcut("TV Mix", Icons.Default.Shuffle, mix) }
            item { LibraryShortcut("IPTV library", Icons.Default.LiveTv, iptv) }
            item { LibraryShortcut("Recordings", Icons.Default.FiberManualRecord, recordings) }
            item { LibraryShortcut("Watch Party", Icons.Default.Groups, party) }
            item { LibraryShortcut("Cloud files", Icons.Default.Cloud, cloud) }
            item { LibraryShortcut("Server libraries", Icons.Default.Storage, servers) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { items(state.listTabs) { tab -> Action(tab.title, selected = tab.key == state.selectedListKey) { vm.onSelectListTab(tab.key) } } }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(vertical = 4.dp)) { items(state.availableTypeTabs) { tab -> Action(tab.label, selected = tab == state.selectedTypeTab) { vm.onSelectTypeTab(tab) } } }
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
            if (state.availableGenres.isNotEmpty()) {
                SectionTitle("Genre")
                LazyRow { item { Action("All genres", selected = state.selectedGenre == null) { vm.onSelectGenre(null) } }
                    items(state.availableGenres) { genre -> Action(genre.label, selected = state.selectedGenre == genre.key) { vm.onSelectGenre(genre.key) } } }
            }
            if (state.availableYears.isNotEmpty()) {
                SectionTitle("Year")
                LazyRow { item { Action("All years", selected = state.selectedYear == null) { vm.onSelectYear(null) } }
                    items(state.availableYears) { year -> Action(year.label, selected = state.selectedYear == year.key) { vm.onSelectYear(year.key) } } }
            }
            Action("Refresh library") { vm.onRefresh(); filters = false }
        }
    }
    menu?.let { entry -> VideoActions(entry.name, { menu = null }) { menu = null; open(entry.toMetaPreview(), entry.addonBaseUrl, it) } }
    if (state.showManageDialog) MobileLibraryLists(state, vm)
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

@Composable
private fun LibraryShortcut(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit) {
    Row(Modifier.mediaPanel().clickable(onClick = open).heightIn(min = 52.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Label(label, strong = true)
    }
}
