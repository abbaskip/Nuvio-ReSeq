// Touch filmography, company rows and folder grids adapted from NuvioPlusIPTV-Mobile (GPLv3).
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import com.nuvio.tv.R
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.*
import androidx.navigation.compose.composable
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.cast.*
import com.nuvio.tv.ui.screens.collection.FolderDetailViewModel
import com.nuvio.tv.ui.screens.detail.MetaDetailsUiState
import com.nuvio.tv.ui.screens.flickpick.*
import com.nuvio.tv.ui.screens.home.*
import com.nuvio.tv.ui.screens.tmdb.*
import java.time.LocalDate

internal typealias OpenMobileTitle = (MetaPreview, String?, VideoAction) -> Unit

internal fun NavGraphBuilder.mobileBrowseDestinations(nav: NavHostController, open: OpenMobileTitle) {
    val back: () -> Unit = { nav.popBackStack() }
    composable(Screen.OnDemand.route) { MobileIptvLibrary(back, { nav.navigate(it) }) }
    composable("mobile-recordings") { MobileRecordings(back, { nav.navigate(it) }) }
    composable(Screen.Collections.route) {
        MobileCollections(back, { nav.navigate(Screen.CollectionEditor.createRoute(it)) },
            { collection, folder -> nav.navigate(Screen.FolderDetail.createRoute(collection, folder)) })
    }
    composable(Screen.CollectionEditor.route, arguments = listOf(navArgument("collectionId") { defaultValue = "" })) { MobileCollectionEditor(back) }
    composable(Screen.FolderDetail.route) { MobileFolder(back, open) }
    composable(Screen.FlickPick.route) { MobileFlickPick(back, open) }
    composable(Screen.CastDetail.route, arguments = listOf(navArgument("preferCrew") { type = NavType.BoolType; defaultValue = false })) { MobileCast(back, open) }
    composable(Screen.TmdbEntityBrowse.route, arguments = listOf(navArgument("entityId") { type = NavType.IntType })) { MobileEntity(back, open) }
    composable(Screen.CatalogSeeAll.route, arguments = listOf(navArgument("fromSearch") { type = NavType.BoolType; defaultValue = false })) { entry ->
        val parent = remember(entry) { nav.getBackStackEntry("catalog") }
        val vm: HomeViewModel = hiltViewModel(parent)
        val state by vm.uiState.collectAsStateWithLifecycle()
        val args = entry.arguments
        val row = state.catalogRows.find { it.addonId == args?.getString("addonId") && it.catalogId == args.getString("catalogId") && it.apiType == args.getString("type") }
        Column { BrowseHeading(row?.catalogName ?: "Catalogue", back)
            if (row == null) Label("This catalogue is unavailable.")
            else MobilePosterGrid(row.items, open, row.addonBaseUrl, row.hasMore && row.supportsSkip && !row.isLoading) {
                vm.onEvent(HomeEvent.OnLoadMoreCatalog(row.catalogId, row.addonId, row.apiType))
            }
        }
    }
}

@Composable
internal fun BrowseHeading(title: String, back: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        PlayerIcon("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = back)
        SectionTitle(title)
    }
}

@Composable
internal fun MobileTextInput(label: String, value: String, update: (String) -> Unit) {
    OutlinedTextField(value, update, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, shape = RectangleShape)
}

@Composable
internal fun MobilePosterGrid(titles: List<MetaPreview>, open: OpenMobileTitle, addon: String? = null, hasMore: Boolean = false, more: () -> Unit = {}) {
    var menu by remember { mutableStateOf<MetaPreview?>(null) }
    LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(titles.size) { index ->
            val item = titles[index]
            Poster(item, Modifier.fillMaxWidth(), { open(item, item.sourceAddonBaseUrl ?: addon, VideoAction.Details) }, { menu = item })
        }
        if (hasMore) item(span = { GridItemSpan(maxLineSpan) }) { Action("Load more", onClick = more) }
    }
    menu?.let { item -> VideoActions(item.name, { menu = null }) { menu = null; open(item, item.sourceAddonBaseUrl ?: addon, it) } }
}

@Composable
internal fun TitleRail(name: String, titles: List<MetaPreview>, open: OpenMobileTitle) {
    var menu by remember { mutableStateOf<MetaPreview?>(null) }
    if (titles.isNotEmpty()) {
        SectionTitle(name)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(titles) { item ->
                Poster(item, Modifier.width(110.dp), { open(item, item.sourceAddonBaseUrl, VideoAction.Details) }, { menu = item })
            }
        }
    }
    menu?.let { item -> VideoActions(item.name, { menu = null }) { menu = null; open(item, item.sourceAddonBaseUrl, it) } }
}

internal fun LazyListScope.titleConnections(meta: Meta, state: MetaDetailsUiState, navigate: (String) -> Unit, open: OpenMobileTitle) {
    val cast = meta.castMembers.ifEmpty { meta.cast.map { MetaCastMember(it) } }
    if (cast.isNotEmpty()) item {
        SectionTitle("Cast")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(cast) { member ->
                val id = member.tmdbId
                Column(Modifier.width(108.dp).mediaPanel().then(if (id != null) Modifier.clickable { navigate(Screen.CastDetail.createRoute(id, member.name)) } else Modifier)) {
                    MediaArt(member.photo, Icons.Default.Person, Modifier.fillMaxWidth().height(118.dp))
                    Column(Modifier.padding(6.dp)) {
                        Label(member.name, strong = true, lines = 2)
                        member.character?.let { Label(it, small = true, lines = 2) }
                    }
                }
            }
        }
    }
    listOf("Networks" to meta.networks, "Production companies" to meta.productionCompanies).forEach { (title, companies) ->
        if (companies.isNotEmpty()) item {
            SectionTitle(title)
            LazyRow { items(companies) { company ->
                val id = company.tmdbId
                if (id == null) Label(company.name) else Action(company.name) {
                    navigate(Screen.TmdbEntityBrowse.createRoute(if (title == "Networks") "network" else "company", id, company.name, meta.apiType))
                }
            } }
        }
    }
    if (state.collection.isNotEmpty()) item { TitleRail(state.collectionName ?: "Collection", state.collection, open) }
    if (state.moreLikeThis.isNotEmpty()) item { TitleRail("More like this", state.moreLikeThis, open) }
}

@Composable
private fun MobileCast(back: () -> Unit, open: OpenMobileTitle, vm: CastDetailViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(10.dp)) {
        item { BrowseHeading(vm.personName, back) }
        when (val page = state) {
            CastDetailUiState.Loading -> item { LinearProgressIndicator() }
            is CastDetailUiState.Error -> item { Label(page.message); Action("Retry", onClick = vm::retry) }
            is CastDetailUiState.Success -> {
                val person = page.personDetail
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    person.profilePhoto?.let { AsyncImage(it, person.name, Modifier.width(100.dp).height(150.dp)) }
                    Column { listOfNotNull(person.knownFor, person.placeOfBirth, person.birthday, person.deathday).forEach { Label(it) } }
                } }
                person.biography?.let { item { Text(it) } }
                item { TitleRail("Movies", person.movieCredits, open); TitleRail("TV", person.tvCredits, open) }
            }
        }
    }
}

@Composable
private fun MobileEntity(back: () -> Unit, open: OpenMobileTitle, vm: TmdbEntityBrowseViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(10.dp)) {
        item { BrowseHeading(vm.entityName, back) }
        when (val page = state) {
            TmdbEntityBrowseUiState.Loading -> item { LinearProgressIndicator() }
            is TmdbEntityBrowseUiState.Error -> item { Label(page.message); Action("Retry", onClick = vm::retry) }
            is TmdbEntityBrowseUiState.Success -> {
                page.data.header.description?.let { item { Text(it) } }
                items(page.data.rails) { rail ->
                    TitleRail(rail.mediaType.value.uppercase() + " · " + rail.railType.name.lowercase().replace('_', ' '), rail.items, open)
                    if (rail.hasMore && !rail.isLoading) Action("Load more") { vm.loadMoreRail(rail.mediaType, rail.railType) }
                }
            }
        }
    }
}

@Composable
private fun MobileFolder(back: () -> Unit, open: OpenMobileTitle, vm: FolderDetailViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val tab = state.tabs.getOrNull(state.selectedTabIndex)
    Column(Modifier.fillMaxSize()) {
        BrowseHeading(state.folder?.title ?: state.collectionTitle, back)
        LazyRow { itemsIndexed(state.tabs) { index, entry -> Action(entry.label, selected = index == state.selectedTabIndex) { vm.selectTab(index) } } }
        if (state.isLoading || tab?.isLoading == true) LinearProgressIndicator()
        tab?.error?.let { Label(it) }
        val row = tab?.catalogRow
        if (row != null) MobilePosterGrid(row.items, open, row.addonBaseUrl, row.hasMore && !row.isLoading) { vm.loadMoreItems(state.selectedTabIndex) }
    }
}

@Composable
private fun MobileFlickPick(back: () -> Unit, open: OpenMobileTitle, vm: FlickPickViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val filters = state.filters
    val region = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].country
    LaunchedEffect(region) { vm.configureRatings(region) }
    DisposableEffect(vm) { onDispose(vm::cancelPick) }
    LaunchedEffect(state.pick) { vm.takePick()?.first?.let { open(it, it.sourceAddonBaseUrl, VideoAction.Play) } }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Box(Modifier.fillMaxWidth().mediaPanel()) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            PlayerIcon("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = back)
                            Label("Pick an unwatched movie from Discover or one of your lists.", Modifier.padding(top = 8.dp), lines = 3)
                        }
                        Image(painterResource(R.drawable.flick_pick_artwork), "Flick Pick", Modifier.weight(1.25f).heightIn(min = 112.dp, max = 166.dp).aspectRatio(4f / 3f), contentScale = ContentScale.Fit)
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Action("Discover", Modifier.weight(1f), selected = !state.fromList) { vm.mode(false) }
                Action("From a list", Modifier.weight(1f), selected = state.fromList) { vm.mode(true) }
            } }
            if (state.fromList) {
                if (state.lists.isEmpty()) item { Label(if (state.libraryLoaded) "No movie lists available." else "Loading movie lists…", Modifier.mediaPanel().padding(14.dp)) }
                items(state.lists) { list -> Action(list.tab.title, Modifier.fillMaxWidth(), state.selectedList == list.key) { vm.list(list.key) } }
            } else {
                item { FlickFilterGroup("Movie preferences") {
                    MobileTextInput("Keywords", filters.keywords) { vm.filters(filters.copy(keywords = it)) }
                    if (state.genres.isNotEmpty()) {
                        Label("Genres", strong = true)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { items(state.genres) { genre ->
                            Action(listOfNotNull(genre.name, filters.genres[genre.id]?.name?.lowercase()).joinToString(" · "),
                                selected = genre.id in filters.genres) { vm.filters(filters.cycle(genre.id)) }
                        } }
                    }
                } }
                item { FlickFilterGroup("Release dates") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { items(FlickPickEra.entries.toList()) { era ->
                        Action(era.name.lowercase().replace('_', ' '), selected = filters.era == era) { vm.filters(filters.withEra(era, LocalDate.now())) }
                    } }
                    MobileTextInput("From (YYYY-MM-DD)", filters.from) { vm.filters(filters.copy(era = null, from = it)) }
                    MobileTextInput("Through (YYYY-MM-DD)", filters.to) { vm.filters(filters.copy(era = null, to = it)) }
                } }
            }
            state.ratingSystem?.let { system -> item { FlickFilterGroup("Maximum rating · " + system.country) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { items(listOf("") + system.ranks.keys) { rating ->
                    Action(rating.ifBlank { "Any" }, selected = state.maximumRating.orEmpty() == rating) { vm.maximumRating(rating) }
                } }
            } } }
            state.failure?.let { item { Label(it.name.lowercase().replace('_', ' ')) } }
            if (state.empty) item { Label("No unwatched movies matched.") }
        }
        HorizontalDivider(color = colors.outlineVariant)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy) Action("Cancel", Modifier.weight(1f), onClick = vm::cancelPick)
            else PrimaryMediaAction("Pick and play", Modifier.weight(1f), onClick = vm::play)
            Action("Reset", onClick = vm::reset)
        }
    }
}

@Composable
private fun FlickFilterGroup(title: String, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().mediaPanel().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SectionTitle(title)
        body()
    }
}
