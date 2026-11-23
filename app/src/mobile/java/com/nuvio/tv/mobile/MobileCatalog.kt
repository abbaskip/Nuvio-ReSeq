// Touch poster rows and long-press action sheets adapted from NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import coil3.compose.AsyncImage
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.detail.*
import com.nuvio.tv.ui.screens.home.*
import com.nuvio.tv.ui.screens.tvmix.TvMixViewModel
import com.nuvio.tv.ui.screens.tvmix.streamRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
internal class MobileCatalogViewModel @Inject constructor(
    val downloads: MobileOfflineMedia, val profiles: ProfileManager, val external: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    val themes: com.nuvio.tv.data.local.ThemeDataStore, val debrid: com.nuvio.tv.data.local.DebridSettingsDataStore
) : ViewModel()

internal enum class VideoAction { Play, Download, Manual, Details }

internal fun mobileDetailRoute(item: MetaPreview, addon: String?, action: VideoAction): String =
    Screen.Detail.createRoute(item.id, item.apiType, addon) + "&operation=" + action.name + "&previewTitle=" + Uri.encode(item.name)

@Composable
internal fun MobileCatalog(tab: Tab, playerVisible: (Boolean) -> Unit, onWatch: (com.nuvio.tv.livetv.model.LiveChannel) -> Unit = {},
    catalog: MobileCatalogViewModel = hiltViewModel()) {
    val profile by catalog.profiles.activeProfileId.collectAsStateWithLifecycle()
    key(profile, tab) { CatalogNavigation(profile, catalog, tab, playerVisible, onWatch) }
}

@Composable
private fun CatalogNavigation(profile: Int, catalog: MobileCatalogViewModel, tab: Tab, playerVisible: (Boolean) -> Unit,
    onWatch: (com.nuvio.tv.livetv.model.LiveChannel) -> Unit,
    mix: TvMixViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    var pendingRating by remember(profile) { mutableStateOf<com.nuvio.tv.core.tracking.MovieRatingRequest?>(null) }
    val failed by mix.session.sourceFailures.collectAsStateWithLifecycle()
    val returned by mix.session.externalReturn.collectAsStateWithLifecycle()
    fun returnToMix() {
        if (nav.currentDestination?.route != "mobile-mix" && !nav.popBackStack("mobile-mix", false)) {
            nav.navigate("mobile-mix") { launchSingleTop = true }
        }
    }
    LaunchedEffect(failed) {
        failed?.let { if (mix.session.sourceFailures.compareAndSet(it, null) && mix.sourceUnavailable(it)) returnToMix() }
    }
    LaunchedEffect(returned) {
        returned?.let { result ->
            if (mix.session.externalReturn.compareAndSet(result, null) && mix.session.playing.value === result.playing) {
                if (result.completed) mix.completed(result.autoplay) else mix.session.end()
                returnToMix()
            }
        }
    }
    LaunchedEffect(route) {
        if (route != null && route != "mobile-mix" && !route.startsWith("player/") && !route.startsWith("stream/")) mix.end()
    }
    DisposableEffect(profile, tab) { onDispose { if ((context as? android.app.Activity)?.isChangingConfigurations != true) mix.end() } }
    val openTitle: (MetaPreview, String?, VideoAction) -> Unit = { item, addon, action ->
        if (action == VideoAction.Play && item.apiType == "movie") {
            scope.launch {
                val video = MobileVideo(profile, item.apiType, item.id, item.id, item.name, poster = item.poster)
                val local = withContext(Dispatchers.IO) { catalog.downloads.localRoute(video) }
                nav.navigate(local ?: mobileDetailRoute(item, addon, action))
            }
        } else nav.navigate(mobileDetailRoute(item, addon, action))
    }
    val openVideo: (MobileVideo, VideoAction) -> Unit = { video, action ->
        scope.launch {
            if (action == VideoAction.Details) {
                nav.navigate(Screen.Detail.createRoute(video.contentId, video.type, returnFocusSeason = video.season, returnFocusEpisode = video.episode) +
                    "&operation=Details&previewTitle=" + Uri.encode(video.title))
                return@launch
            }
            val local = withContext(Dispatchers.IO) { catalog.downloads.localRoute(video) }
            when {
                local != null && action == VideoAction.Play -> nav.navigate(local)
                local != null && action == VideoAction.Download -> Toast.makeText(context, "Already downloaded", Toast.LENGTH_SHORT).show()
                else -> nav.navigate(video.sources(action == VideoAction.Manual, action == VideoAction.Download) + "&operation=" + action.name)
            }
        }
    }
    val openPartyTitle: (com.nuvio.tv.core.party.PartyMedia) -> Unit = { media ->
        if (nav.currentDestination?.route == Screen.Player.route) nav.popBackStack()
        openVideo(MobileVideo(profile, media.type, media.metaId, media.videoId ?: media.metaId, media.title, media.season,
            media.episode, media.episodeTitle, poster = media.poster), VideoAction.Play)
    }
    LaunchedEffect(catalog.external, profile) {
        catalog.external.autoPlayNext.collect { next ->
            if (next.profileId == profile && catalog.external.claimAutoPlayNextNavigation(next)) {
                val video = MobileVideo(profile, next.contentType, next.contentId, next.nextVideoId, next.contentName,
                    next.nextSeason, next.nextEpisode, tvdb = next.tvdbContextJson, poster = next.poster)
                if (nav.currentDestination?.route?.startsWith("stream/") == true) nav.popBackStack()
                openVideo(video, VideoAction.Play)
            }
        }
    }
    NavHost(nav, startDestination = "catalog") {
        composable("catalog") {
            when (tab) {
                Tab.CALENDAR -> MobileCalendar(onWatch, onTitle = { id, type, title ->
                    nav.navigate(Screen.Detail.createRoute(id, type) + "&operation=Details&previewTitle=" + Uri.encode(title))
                })
                Tab.SEARCH -> MobileSearch(openTitle, { nav.navigate("advanced") }, { nav.navigate(Screen.FlickPick.route) })
                Tab.LIBRARY -> MobileLibrary(openTitle, { nav.navigate(Screen.Collections.route) },
                    { nav.navigate(Screen.OnDemand.route) }, { nav.navigate("mobile-recordings") }, { nav.navigate("mobile-party") },
                    { nav.navigate("mobile-cloud") }, { nav.navigate("mobile-servers") }, { nav.navigate("mobile-mix") })
                else -> CatalogHome(profile, catalog, openVideo, openTitle, { nav.navigate(it) })
            }
        }
        mobileBrowseDestinations(nav, openTitle)
        composable("mobile-cloud") { MobileCloudLibrary({ nav.popBackStack() }, { nav.navigate(it) }) }
        composable("mobile-servers") { MobileServerShelves(openTitle, { nav.popBackStack() }) }
        composable("mobile-party") {
            val parties: com.nuvio.tv.ui.screens.party.PartyViewModel = hiltViewModel()
            MobileWatchParty(parties.party, null, openPartyTitle) { nav.popBackStack() }
        }
        composable("advanced") {
            MobileDiscover(openTitle = { item, action -> openTitle(item, null, action) }, openMix = { nav.navigate("mobile-mix") }, mix = mix)
        }
        composable("mobile-mix") {
            MixList(mix, back = { nav.popBackStack() }) { pick ->
                val expected = mix.session.playing.value
                scope.launch {
                    val episode = pick.video
                    val video = MobileVideo(profile, pick.meta.apiType, pick.meta.id, episode.id, pick.meta.name,
                        episode.sourceSeason, episode.sourceEpisode, episode.title, episode.tvdbPlaybackContext()?.encode(), pick.meta.poster)
                    val local = withContext(Dispatchers.IO) { catalog.downloads.localRoute(video, fromBeginning = true) }
                    if (mix.session.playing.value === expected && nav.currentDestination?.route == "mobile-mix")
                        nav.navigate(local ?: (pick.streamRoute(profile) + "&operation=Play"))
                }
            }
        }
        composable(Screen.Detail.route + "&operation={operation}&previewTitle={previewTitle}") { entry ->
            val args = requireNotNull(entry.arguments)
            CatalogDetail(profile, args.getString("operation"), args.getString("previewTitle").orEmpty(),
                args.getString("itemId").orEmpty(), args.getString("itemType").orEmpty(), openVideo,
                { nav.popBackStack() }, { nav.navigate(it) }, openTitle, mix, args.getString("addonBaseUrl")?.takeUnless { it == "{addonBaseUrl}" })
        }
        composable(Screen.Stream.route + "&operation={operation}",
            arguments = listOf(navArgument("autoSelectStream") { type = NavType.BoolType; defaultValue = false })) { entry ->
            val args = requireNotNull(entry.arguments)
            fun optional(name: String) = args.getString(name)?.takeIf { it.isNotBlank() && it != "{$name}" }
            val video = MobileVideo(profile, optional("contentType").orEmpty(), optional("contentId").orEmpty(),
                optional("videoId").orEmpty(), optional("title").orEmpty(), optional("season")?.toIntOrNull(),
                optional("episode")?.toIntOrNull(), optional("episodeName"), optional("tvdbContext"), optional("poster"))
            val leave = { mix.session.end(); nav.popBackStack(); Unit }
            androidx.activity.compose.BackHandler(onBack = leave)
            MobileSources(video, optional("operation") == VideoAction.Download.name, optional("startFromBeginning") == "true", leave, { route ->
                nav.navigate(route) { popUpTo(entry.destination.id) { inclusive = true } }
            })
        }
        composable(Screen.Player.route) { entry ->
            DisposableEffect(Unit) { playerVisible(true); onDispose { playerVisible(false) } }
            val args = entry.arguments
            val leave = { mix.session.end(); nav.popBackStack(); Unit }
            androidx.activity.compose.BackHandler(onBack = leave)
            MobileVodPlayer(back = leave, openParty = openPartyTitle, onMovieCompleted = { pendingRating = it },
                recommendation = { rec, action ->
                    nav.navigate(Screen.Detail.createRoute(rec.id, rec.contentType, rec.sourceAddonBaseUrl) +
                        "&operation=" + action.name + "&previewTitle=" + Uri.encode(rec.title)) {
                        popUpTo(entry.destination.id) { inclusive = true }
                    }
                }, mixCompleted = if (mix.session.matches(profile, args?.getString("contentId"), args?.getString("videoId"))) {
                { autoplay, group ->
                    if (mix.session.matches(profile, args?.getString("contentId"), args?.getString("videoId"))) {
                        mix.completed(autoplay, group); returnToMix()
                    }
                }
            } else null)
        }
    }
    if (route != null && !route.startsWith("player/") && !route.startsWith("stream/"))
        pendingRating?.let { MobileMovieRating(it, close = { pendingRating = null }) }
}

@Composable
private fun CatalogHome(profile: Int, catalog: MobileCatalogViewModel, openVideo: (MobileVideo, VideoAction) -> Unit,
    open: (MetaPreview, String?, VideoAction) -> Unit, navigate: (String) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        vm.setHomeContentActive(true)
        vm.refreshHomeCatalogsIfStale()
        onStopOrDispose { vm.setHomeContentActive(false) }
    }
    val revision by catalog.downloads.changes.collectAsStateWithLifecycle()
    val transfers by catalog.downloads.transfers.collectAsStateWithLifecycle()
    val saved by produceState(emptyList<OfflineFile>(), profile, revision) {
        value = withContext(Dispatchers.IO) { catalog.downloads.saved(profile) }
    }
    var menu by remember { mutableStateOf<Pair<MetaPreview,String?>?>(null) }
    var resumeMenu by remember { mutableStateOf<Pair<MobileVideo, ContinueWatchingItem>?>(null) }
    var manage by remember { mutableStateOf<OfflineFile?>(null) }
    var providers by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Row { Box(Modifier.weight(1f)) { SectionTitle("Home") }; Action("Catalog sources") { providers = true } } }
        val hero = state.heroItems.ifEmpty { state.catalogRows.firstOrNull()?.items.orEmpty() }
        if (state.heroSectionEnabled && hero.isNotEmpty()) item {
            HomeHighlights(hero.take(8), open = { open(it, null, VideoAction.Details) }, more = { menu = it to null })
        }
        if (state.continueWatchingEnabled) listOf(
            "Continue watching" to state.continueWatchingItems, "Upcoming" to state.upcomingItems
        ).forEach { (heading, entries) -> if (entries.isNotEmpty()) item {
            SectionTitle(heading)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries) { entry ->
                    val video = when (entry) {
                        is ContinueWatchingItem.InProgress -> entry.progress.let { MobileVideo(profile, it.contentType,
                            it.contentId, it.videoId, it.name, it.season, it.episode, it.episodeTitle, it.tvdbContext?.encode(), it.poster) }
                        is ContinueWatchingItem.NextUp -> entry.info.let { MobileVideo(profile, it.contentType,
                            it.contentId, it.videoId, it.name, it.season, it.episode, it.episodeTitle, it.tvdbContext?.encode(), it.poster) }
                    }
                    val playable = !entry.chooseEpisode && (entry !is ContinueWatchingItem.NextUp || entry.info.hasAired)
                    Column(Modifier.width(142.dp).combinedClickable(onClick = { openVideo(video, if (playable) VideoAction.Play else VideoAction.Details) },
                        onLongClick = { resumeMenu = video to entry })) {
                        val thumbnail = when (entry) {
                            is ContinueWatchingItem.InProgress -> entry.episodeThumbnail
                            is ContinueWatchingItem.NextUp -> entry.info.thumbnail.takeUnless { state.blurUnwatchedEpisodes }
                        }
                        val artwork = thumbnail.takeIf { state.useEpisodeThumbnailsInCw } ?: video.poster
                        AsyncImage(artwork, null, Modifier.fillMaxWidth().height(96.dp), contentScale = ContentScale.Crop)
                        if (entry is ContinueWatchingItem.InProgress) LinearProgressIndicator(
                            progress = { entry.progress.progressPercentage }, modifier = Modifier.fillMaxWidth()
                        )
                        Label(video.label, small = true, lines = 2)
                        if (entry is ContinueWatchingItem.NextUp && !entry.info.hasAired) entry.info.airDateLabel?.let { Label(it, small = true) }
                    }
                }
            }
        } }
        if (saved.isNotEmpty()) {
            item { SectionTitle("Downloads") }
            items(saved, key = { "offline-" + it.video.key }) { entry ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Action(entry.video.label, Modifier.weight(1f)) { openVideo(entry.video, VideoAction.Play) }
                    Action("Manage") { manage = entry }
                }
            }
        }
        items(transfers.values.filter { it.video.profile == profile && (it.active || it.text != "Downloaded") }) {
            Label(it.video.label + " · " + it.text)
        }
        if (state.catalogRows.isEmpty()) item { Label(state.error ?: if (state.isLoading) "Loading catalogues…" else "Add a catalogue source to browse titles.") }
        val rows = state.homeRows.ifEmpty { state.catalogRows.map { HomeRow.Catalog(it) } }
        items(rows) { entry ->
            if (entry is HomeRow.CollectionRow) {
                SectionTitle(entry.collection.title)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entry.collection.folders) { folder ->
                        Action(folder.title) { navigate(Screen.FolderDetail.createRoute(entry.collection.id, folder.id)) }
                    }
                }
                return@items
            }
            if (entry is HomeRow.PlaceholderCatalog) {
                LaunchedEffect(entry.catalogKey) { vm.requestLazyCatalogLoad(entry.catalogKey) }
                SectionTitle(entry.displayTitle)
                Spacer(Modifier.height(120.dp))
                return@items
            }
            val row = (entry as HomeRow.Catalog).row
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle(row.catalogName)
                    if (state.catalogAddonNameEnabled) Label(row.addonName, small = true)
                }
                Action("See all") { navigate(Screen.CatalogSeeAll.createRoute(row.catalogId, row.addonId, row.apiType)) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(row.items) { item ->
                    Column(Modifier.width(110.dp).combinedClickable(onClick = { open(item, row.addonBaseUrl, VideoAction.Details) },
                        onLongClickLabel = "Title actions", onLongClick = { menu = item to row.addonBaseUrl })) {
                        AsyncImage(item.poster, if (state.posterLabelsEnabled) null else item.name,
                            Modifier.fillMaxWidth().aspectRatio(2f / 3), contentScale = ContentScale.Crop)
                        if (state.posterLabelsEnabled) Label(item.name, small = true, lines = 2)
                    }
                }
                if (row.hasMore && row.supportsSkip) item {
                    Action("More titles") { vm.onEvent(HomeEvent.OnLoadMoreCatalog(row.catalogId, row.addonId, row.apiType)) }
                }
            }
        }
    }
    menu?.let { (item, addon) -> VideoActions(item.name, { menu = null }) { action -> menu = null; open(item, addon, action) } }
    manage?.let { entry -> TouchSheet({ manage = null }) {
        SectionTitle(entry.video.label)
        Action("Play downloaded file") { manage = null; openVideo(entry.video, VideoAction.Play) }
        Action("Remove download") {
            manage = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { catalog.downloads.remove(entry) } }
                result.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
            }
        }
    } }
    if (providers) CatalogSources { providers = false }
    resumeMenu?.let { (video, row) ->
        VideoActions(video.label, { resumeMenu = null }, playable = !row.chooseEpisode && (row !is ContinueWatchingItem.NextUp || row.info.hasAired), extra = {
            if (row.canMarkWatched()) Action("Mark watched") { vm.onEvent(HomeEvent.OnMarkContinueWatchingWatched(row)); resumeMenu = null }
            Action("Remove from Continue watching") {
                vm.onEvent(HomeEvent.OnRemoveContinueWatching(video.contentId, video.season, video.episode, row is ContinueWatchingItem.NextUp))
                resumeMenu = null
            }
        }) { resumeMenu = null; openVideo(video, it) }
    }
}

@Composable
internal fun CatalogSources(vm: com.nuvio.tv.ui.screens.addon.AddonManagerViewModel = hiltViewModel(), close: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var remove by remember { mutableStateOf<String?>(null) }
    var ordering by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(vm) { onDispose { vm.cancelAddonMove() } }
    LaunchedEffect(state.transientMessage) {
        if (state.transientMessage != null && !state.transientMessageIsError && state.installUrl.isBlank()) {
            vm.clearTransientMessage()
            close()
        }
    }
    TouchSheet(close) {
        if (ordering) MobileCatalogOrder { ordering = false } else LazyColumn(contentPadding = PaddingValues(12.dp), modifier = Modifier.imePadding()) {
            item { BrowseHeading("Catalog sources", close) }
            item { Action("Catalog order and visibility") { ordering = true } }
            itemsIndexed(state.installedAddons, key = { _, addon -> addon.baseUrl }) { index, addon ->
                Label(addon.name, strong = true)
                if (!vm.isReadOnly) LazyRow {
                    item { Action(if (addon.enabled) "Enabled" else "Disabled", selected = addon.enabled) { vm.setAddonEnabled(addon.baseUrl, !addon.enabled) } }
                    fun move(by: Int) {
                        vm.enterAddonMoveMode(addon.baseUrl)
                        vm.moveAddonDuringDrag(addon.baseUrl, by)
                        vm.confirmAddonMove()
                    }
                    if (index > 0) item { Action("Move up") { move(-1) } }
                    if (index < state.installedAddons.lastIndex) item { Action("Move down") { move(1) } }
                    item { Action(if (remove == addon.baseUrl) "Confirm removal" else "Remove") {
                        if (remove == addon.baseUrl) { vm.removeAddon(addon.baseUrl); remove = null } else remove = addon.baseUrl
                    } }
                }
            }
            if (!vm.isReadOnly) {
                item { MobileTextInput("Manifest URL", state.installUrl, vm::onInstallUrlChange) }
                item { Action(if (state.isInstalling) "Checking…" else "Install source") { if (!state.isInstalling) vm.installAddon() } }
                item { Action("Sync sources now", onClick = vm::requestAddonSyncNow) }
            }
            (state.error ?: state.transientMessage)?.let { item { Label(it) } }
        }
    }
}

@Composable
private fun MobileCatalogOrder(vm: com.nuvio.tv.ui.screens.addon.CatalogOrderViewModel = hiltViewModel(), back: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.cancelMove() } }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item { BrowseHeading("Catalog order", back) }
        item { Action("Follow source order", selected = state.followAddonsOrder) { vm.toggleFollowAddonsOrder(!state.followAddonsOrder) } }
        if (state.isLoading) item { Label("Loading catalogs…") }
        items(state.items, key = { it.key }) { item ->
            Label(item.catalogName + " · " + item.addonName, strong = true)
            Row {
                Action(if (item.isDisabled) "Hidden" else "Visible", selected = !item.isDisabled) { vm.toggleCatalogEnabled(item.disableKey) }
                fun move(by: Int) { vm.enterMoveMode(item.key); vm.moveDuringDrag(item.key, by); vm.confirmMove() }
                if (item.canMoveUp) Action("Up") { move(-1) }
                if (item.canMoveDown) Action("Down") { move(1) }
            }
        }
    }
}
@Composable
internal fun VideoActions(label: String, close: () -> Unit, playable: Boolean = true, showDetails: Boolean = true,
    extra: @Composable () -> Unit = {}, choose: (VideoAction) -> Unit) {
    TouchSheet(close) {
        SectionTitle(label)
        if (playable) {
            Action("Play", Modifier.fillMaxWidth()) { choose(VideoAction.Play) }
            Action("Play manually", Modifier.fillMaxWidth()) { choose(VideoAction.Manual) }
            Action("Download", Modifier.fillMaxWidth()) { choose(VideoAction.Download) }
        }
        if (showDetails) Action("Title details", Modifier.fillMaxWidth()) { choose(VideoAction.Details) }
        extra()
    }
}

@Composable
private fun CatalogDetail(profile: Int, initial: String?, preview: String, id: String, type: String,
    open: (MobileVideo, VideoAction) -> Unit, back: () -> Unit, navigate: (String) -> Unit,
    openTitle: (MetaPreview, String?, VideoAction) -> Unit, mix: TvMixViewModel, sourceAddon: String?, vm: MetaDetailsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val meta = state.meta
    val movie = type == "movie"
    val detailScope = rememberCoroutineScope()
    var comments by rememberSaveable { mutableStateOf(false) }
    var shuffle by rememberSaveable { mutableStateOf(false) }
    var addToMix by rememberSaveable { mutableStateOf(false) }
    val hero = meta?.takeUnless { state.shufflePoolEmpty }?.let { resolveHeroPlaybackVideo(it, state.nextToWatch, state.episodesForSeason) }
    fun selection(selected: Video?): MobileVideo? {
        val title = meta?.name ?: preview
        if (movie && selected == null) return MobileVideo(profile, type, id, meta?.behaviorHints?.defaultVideoId ?: id, title, poster = meta?.poster)
        val episode = selected?.let { v -> meta?.videos?.resolvedEpisodeForPlayback(v.tvdbPlaybackContext(), v.id)
            ?: v.takeIf { it.episodeIdentity == null && it.hasResolvedSource && meta?.episodeOrder == null } } ?: return null
        return MobileVideo(profile, type, id, episode.id, title, episode.sourceSeason, episode.sourceEpisode, episode.title,
            episode.tvdbPlaybackContext()?.encode(), meta?.poster)
    }
    var dispatched by rememberSaveable { mutableStateOf(false) }
    val target = selection(hero)
    LaunchedEffect(target, state.isLoading) {
        val action = VideoAction.entries.find { it.name == initial } ?: return@LaunchedEffect
        if (!dispatched && action != VideoAction.Details && target != null && (!state.isLoading || movie)) {
            dispatched = true
            open(target, action)
        }
    }
    var menu by remember { mutableStateOf<MobileVideo?>(null) }
    val detailTitle = meta?.name ?: preview
    if (addToMix && meta != null) TouchSheet({ addToMix = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(12.dp)) {
            val item = MetaPreview(id = meta.id, type = meta.type, rawType = meta.rawType, name = meta.name,
                poster = meta.poster, rawPosterUrl = meta.rawPosterUrl, posterShape = meta.posterShape, background = meta.background,
                logo = meta.logo, description = meta.description, releaseInfo = meta.releaseInfo, imdbRating = meta.imdbRating,
                genres = meta.genres, imdbId = meta.imdbId, sourceAddonBaseUrl = sourceAddon)
            MobileAddToMix(item, mix) { addToMix = false }
        }
    }
    val header: @Composable (Boolean) -> Unit = { wide ->
        MobileDetailHero(meta, detailTitle, wide, back) { menu = target }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= 720.dp && maxWidth > maxHeight
        val padding = if (minOf(maxWidth, maxHeight) >= 600.dp || maxWidth >= 720.dp) 32.dp else 18.dp
        Row(Modifier.fillMaxSize()) {
            if (split) Column(Modifier.weight(.38f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = padding, end = 12.dp)) { header(true) }
            LazyColumn(Modifier.weight(if (split) .62f else 1f), contentPadding = PaddingValues(horizontal = padding, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!split) item { header(false) }
        if (state.shufflePoolEmpty) item { Label("No episodes match this show's shuffle settings.") }
        if (target != null) item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f).combinedClickable(onClick = { open(target, VideoAction.Play) }, onLongClick = { menu = target })
                .heightIn(min = 48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Label("Play", strong = true) }
            Action("Play manually") { open(target, VideoAction.Manual) }
            Action("Download") { open(target, VideoAction.Download) }
        } }
        if (meta != null) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Action(if (state.isInLibrary || state.isInWatchlist) "Remove from library" else "Add to library") {
                    if (!state.defaultLibraryTogglePending) vm.onEvent(MetaDetailsEvent.OnToggleLibrary)
                } }
                if (movie) item { Action(if (state.isMovieWatched) "Mark unwatched" else "Mark watched") {
                    vm.onEvent(MetaDetailsEvent.OnToggleMovieWatched)
                } }
                if (state.libraryListTabs.isNotEmpty()) item { Action("Choose lists") { vm.onEvent(MetaDetailsEvent.OnLibraryLongPress) } }
                item { MobileRating(meta.ratingReference()) }
                if (!movie) item { MobileEpisodeOrder(meta, vm::reloadEpisodeOrder) }
                if (!movie && state.randomEpisodeEnabled) item { Action("Episode shuffle", selected = state.episodeShuffle.enabled) { shuffle = true } }
                if (!movie) state.selectedSeason?.let { season -> item {
                    val watched = vm.isSeasonFullyWatched(season)
                    Action(if (watched) "Mark season unwatched" else "Mark season watched") {
                        vm.onEvent(if (watched) MetaDetailsEvent.OnMarkSeasonUnwatched(season) else MetaDetailsEvent.OnMarkSeasonWatched(season))
                    }
                } }
                if (state.trailerButtonEnabled && state.trailerUrl != null) item { Action("Play trailer") { vm.onEvent(MetaDetailsEvent.OnTrailerButtonClick) } }
                if (state.shouldShowCommentsSection) item { Action("Reviews and comments") { comments = true } }
            }
            if (state.trailerButtonEnabled && meta.trailers.isNotEmpty()) LazyRow { itemsIndexed(meta.trailers) { i, trailer ->
                Action(trailer.name ?: "Trailer ${i + 1}") { vm.onEvent(MetaDetailsEvent.OnSharedTrailerSelected(trailer)) }
            } }
            Label(listOfNotNull(meta.releaseInfo, meta.imdbRating?.let { "IMDb $it" },
                meta.genres.takeIf { it.isNotEmpty() }?.joinToString(" · ")).joinToString("   "), small = true)
        }
        if (meta != null) titleConnections(meta, state, navigate, openTitle)
        if (meta?.apiType in setOf("series", "tv", "anime")) item { Action("Add to TV Mix") { addToMix = true } }
        if (state.seasons.isNotEmpty()) item { LazyRow { items(state.seasons) { season ->
            Action("Season $season", selected = season == state.selectedSeason) { vm.onEvent(MetaDetailsEvent.OnSeasonSelected(season)) }
        } } }
        items(state.episodesForSeason) { episode ->
            val video = selection(episode)
            Column(Modifier.fillMaxWidth().combinedClickable(enabled = video != null, onClick = { menu = video },
                onLongClick = { menu = video }).heightIn(min = 54.dp).padding(6.dp)) {
                Label("S${episode.displaySeason}E${episode.displayEpisode} · ${episode.title}", strong = true)
                Row {
                    Action(if ((episode.season to episode.episode) in state.watchedEpisodes) "Mark unwatched" else "Mark watched") {
                        vm.onEvent(MetaDetailsEvent.OnToggleEpisodeWatched(episode))
                    }
                    meta?.episodeRatingReference(episode)?.let { MobileRating(it) }
                }
            }
        }
        state.error?.let { item { Label(it) } }
            }
        }
    }
    if (state.isSharedTrailerOverlayVisible || state.isTrailerPlaying) MobileTrailer(state, vm::onEvent)
    if (comments) MobileComments(state, vm::onEvent) { comments = false }
    if (shuffle) TouchSheet({ shuffle = false }) {
        SectionTitle("Episode shuffle")
        Action("Shuffle episodes", selected = state.episodeShuffle.enabled) { detailScope.launch {
            vm.setEpisodeShuffle(state.episodeShuffle.copy(enabled = !state.episodeShuffle.enabled))
        } }
        Action("Include watched episodes", selected = state.episodeShuffle.includeWatched) { detailScope.launch {
            vm.setEpisodeShuffle(state.episodeShuffle.copy(includeWatched = !state.episodeShuffle.includeWatched))
        } }
    }
    menu?.let { video -> VideoActions(video.label, { menu = null }, showDetails = false) { action -> menu = null; open(video, action) } }
    if (state.showListPicker) TouchSheet({ vm.onEvent(MetaDetailsEvent.OnPickerDismiss) }) {
        SectionTitle("Choose lists")
        state.libraryListTabs.filter { it.isMembershipDestination }.forEach { list ->
            Action(list.title, selected = state.pickerMembership[list.key] == true) { vm.onEvent(MetaDetailsEvent.OnPickerMembershipToggled(list.key)) }
        }
        state.pickerError?.let { Label(it) }
        Action("Save lists") { if (!state.pickerPending) vm.onEvent(MetaDetailsEvent.OnPickerSave) }
    }
    if (state.removalConfirmations.isNotEmpty()) AlertDialog(onDismissRequest = { vm.onEvent(MetaDetailsEvent.OnRemovalCancelled) },
        title = { Label("Remove from tracking list?") },
        confirmButton = { Action("Remove") { vm.onEvent(MetaDetailsEvent.OnRemovalConfirmed) } },
        dismissButton = { Action("Cancel") { vm.onEvent(MetaDetailsEvent.OnRemovalCancelled) } })
}

@Composable
private fun MobileRating(media: com.nuvio.tv.core.tracking.TrackingMediaReference, vm: RatingViewModel = hiltViewModel()) {
    val state by rememberRatingState(media, vm)
    var show by remember { mutableStateOf(false) }
    if (!state.isAvailable) return
    Action(state.rating?.let { "Rated $it" } ?: "Rate") { show = true }
    if (show) TouchSheet({ show = false }) {
        SectionTitle("Your rating")
        for (row in listOf(1..5, 6..10)) Row { row.forEach { rating ->
            Action(rating.toString(), Modifier.weight(1f), state.rating == rating.toDouble()) {
                show = false; vm.rate(media, rating, state) {}
            }
        } }
        if (state.rating != null) Action("Remove rating") { show = false; vm.rate(media, null, state) {} }
    }
}
