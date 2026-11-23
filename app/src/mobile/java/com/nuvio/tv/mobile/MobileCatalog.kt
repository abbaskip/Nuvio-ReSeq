// Touch poster rows and long-press action sheets adapted from NuvioPlusIPTV-Mobile. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import android.net.Uri
import com.nuvio.tv.R
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterUrl
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.detail.*
import com.nuvio.tv.ui.screens.home.*
import com.nuvio.tv.ui.screens.settings.TrackingSettingsViewModel
import com.nuvio.tv.ui.screens.tvmix.TvMixViewModel
import com.nuvio.tv.ui.screens.tvmix.streamRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
internal class MobileCatalogViewModel @Inject constructor(
    val downloads: MobileOfflineMedia, val profiles: ProfileManager, val external: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    val themes: com.nuvio.tv.data.local.ThemeDataStore, val debrid: com.nuvio.tv.data.local.DebridSettingsDataStore,
    val layout: LayoutPreferenceDataStore, val playback: com.nuvio.tv.data.local.PlayerSettingsDataStore,
    val progress: com.nuvio.tv.data.cw.ContinueWatchingRepository, val sync: com.nuvio.tv.core.sync.StartupSyncService,
    val watchProgress: com.nuvio.tv.domain.repository.WatchProgressRepository,
    val refresh: com.nuvio.tv.core.tracking.TrackingProgressRefreshCoordinator
) : ViewModel()

internal enum class VideoAction { Play, Download, Manual, Details, Restart }

internal fun mobileDetailRoute(item: MetaPreview, addon: String?, action: VideoAction): String =
    Screen.Detail.createRoute(item.id, item.apiType, addon) + "&operation=" + action.name + "&previewTitle=" + Uri.encode(item.name)

@Composable
internal fun MobileCatalog(tab: Tab, playerVisible: (Boolean) -> Unit, onWatch: (com.nuvio.tv.livetv.model.LiveChannel) -> Unit = {},
    guideContent: (@Composable ((GuideLookup) -> Unit) -> Unit)? = null,
    catalog: MobileCatalogViewModel = hiltViewModel(), tracking: TrackingSettingsViewModel = hiltViewModel()) {
    val profile by catalog.profiles.activeProfileId.collectAsStateWithLifecycle()
    key(profile, tab) { CatalogNavigation(profile, catalog, tab, playerVisible, onWatch, tracking, guideContent) }
}

@Composable
private fun CatalogNavigation(profile: Int, catalog: MobileCatalogViewModel, tab: Tab, playerVisible: (Boolean) -> Unit,
    onWatch: (com.nuvio.tv.livetv.model.LiveChannel) -> Unit, tracking: TrackingSettingsViewModel,
    guideContent: (@Composable ((GuideLookup) -> Unit) -> Unit)?, mix: TvMixViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val cast = LocalCasting.current
    LaunchedEffect(cast, tab, profile) {
        if (tab == Tab.HOME) cast.requestedSources.collect { video ->
            if (video != null && video.profile == profile && cast.requestedSources.compareAndSet(video, null))
                nav.navigate(video.sources(manual = true, explicitAuto = false) + "&operation=Manual") { launchSingleTop = true }
        }
    }
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
        if (item.isTvMix) {
            mix.open(item.id.removePrefix("tvmix:"))
            nav.navigate("mobile-mix") { launchSingleTop = true }
        } else if (action == VideoAction.Play && item.apiType == "movie") {
            scope.launch {
                val video = MobileVideo(profile, item.apiType, item.id, item.id, item.name, poster = item.poster)
                val local = withContext(Dispatchers.IO) { catalog.downloads.localRoute(video) }
                nav.navigate(local?.takeIf { cast.state.value.device == null } ?: mobileDetailRoute(item, addon, action))
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
            val restart = action == VideoAction.Restart
            val local = withContext(Dispatchers.IO) { catalog.downloads.localRoute(video, fromBeginning = restart) }
            when {
                local != null && cast.state.value.device == null && (action == VideoAction.Play || restart) -> nav.navigate(local)
                local != null && action == VideoAction.Download -> Toast.makeText(context, "Already downloaded", Toast.LENGTH_SHORT).show()
                else -> nav.navigate(video.sources(action == VideoAction.Manual, action == VideoAction.Download, restart) + "&operation=" + action.name)
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
                Tab.GUIDE -> guideContent?.invoke { nav.navigate(it.route()) }
                Tab.CALENDAR -> MobileCalendar(onWatch, onTitle = { id, type, title ->
                    nav.navigate(Screen.Detail.createRoute(id, type) + "&operation=Details&previewTitle=" + Uri.encode(title))
                })
                Tab.SEARCH -> MobileSearch(catalog.layout, openTitle, { nav.navigate("advanced") }, { nav.navigate(Screen.FlickPick.route) })
                Tab.LIBRARY -> MobileLibrary(openTitle, { nav.navigate(Screen.Collections.route) },
                    { nav.navigate(Screen.OnDemand.route) }, { nav.navigate("mobile-recordings") }, { nav.navigate("mobile-party") },
                    { nav.navigate("mobile-cloud") }, { nav.navigate("mobile-servers") }, { nav.navigate("mobile-mix") }, { nav.navigate("downloads") })
                else -> CatalogHome(profile, catalog, openVideo, openTitle, { nav.navigate(it) }, tracking)
            }
        }
        composable(GuideLookup.pattern) { entry ->
            val listing = remember(entry) { GuideLookup.from(requireNotNull(entry.arguments)) }
            fun details(item: MetaPreview, addon: String?, exact: Boolean) {
                val focus = if (listing.matchesTitle(item)) "&guideNumber=" + Uri.encode(listing.number) +
                    "&guideSubtitle=" + Uri.encode(listing.subtitle) else ""
                nav.navigate(mobileDetailRoute(item, addon, VideoAction.Details) + focus) {
                    if (exact) popUpTo(entry.destination.id) { inclusive = true }
                }
            }
            MobileSearch(catalog.layout, open = { item, addon, action ->
                if (action == VideoAction.Details) details(item, addon, false) else openTitle(item, addon, action)
            }, advanced = { nav.navigate("advanced") }, flickPick = { nav.navigate(Screen.FlickPick.route) },
                guide = listing, back = { nav.popBackStack() }, onGuideMatch = { item, addon -> details(item, addon, true) })
        }
        mobileBrowseDestinations(nav, openTitle)
        composable("downloads") { MobileDownloads(profile, catalog.downloads, back = { nav.popBackStack() }, play = { nav.navigate(it) }) }
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
        composable(Screen.Detail.route + "&operation={operation}&previewTitle={previewTitle}&guideNumber={guideNumber}&guideSubtitle={guideSubtitle}") { entry ->
            val args = requireNotNull(entry.arguments)
            CatalogDetail(profile, args.getString("operation"), args.getString("previewTitle").orEmpty(),
                args.getString("itemId").orEmpty(), args.getString("itemType").orEmpty(), openVideo,
                { nav.popBackStack() }, { nav.navigate(it) }, openTitle, mix, catalog.layout, args.getString("addonBaseUrl")?.takeUnless { it == "{addonBaseUrl}" },
                args.getString("guideNumber").orEmpty(), args.getString("guideSubtitle").orEmpty())
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
            MobileSourceDestination(video, catalog.downloads, download = optional("operation") == VideoAction.Download.name,
                manual = optional("operation") == VideoAction.Manual.name || optional("manualSelection").toBoolean(),
                fromBeginning = optional("startFromBeginning") == "true", back = leave, play = { route ->
                nav.navigate(route) { popUpTo(entry.destination.id) { inclusive = true } }
            })
        }
        composable(Screen.Player.route) { entry ->
            DisposableEffect(Unit) { playerVisible(true); onDispose { playerVisible(false) } }
            val args = entry.arguments
            val leave = { mix.session.end(); nav.popBackStack(); Unit }
            androidx.activity.compose.BackHandler(onBack = leave)
            if (args?.getString("streamName") == "Downloaded" && args.getString("streamUrl")?.startsWith("file:") == true) {
                val video = remember(entry) { MobileVideo(profile, args.getString("contentType").orEmpty(),
                    args.getString("contentId").orEmpty(), args.getString("videoId").orEmpty(), args.getString("contentName").orEmpty(),
                    args.getString("season")?.toIntOrNull(), args.getString("episode")?.toIntOrNull(), args.getString("episodeTitle"),
                    args.getString("tvdbContext"), args.getString("poster")) }
                if (!downloadUsesInternalPlayer(video, args.getString("startFromBeginning").toBoolean(), catalog,
                        cancel = leave, externalOpened = { if (nav.currentBackStackEntry == entry) nav.popBackStack() })) return@composable
            }
            MobileVodPlayer(media = catalog.downloads, back = leave, openParty = openPartyTitle, onMovieCompleted = { pendingRating = it },
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
    open: (MetaPreview, String?, VideoAction) -> Unit, navigate: (String) -> Unit,
    tracking: TrackingSettingsViewModel, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        vm.setHomeContentActive(true)
        vm.beginShuffleHomeVisit()
        vm.refreshHomeCatalogsIfStale()
        onStopOrDispose { vm.setHomeContentActive(false) }
    }
    val resolved by vm.initialCwResolved.collectAsStateWithLifecycle()
    val trackingState by tracking.uiState.collectAsStateWithLifecycle()
    val source = trackingState.watchProgressSource
    LaunchedEffect(source, profile, trackingState.isReady) {
        if (trackingState.isReady && source == com.nuvio.tv.data.local.WatchProgressSource.NUVIO_SYNC) {
            catalog.watchProgress.allProgress.distinctUntilChanged().collect {
                catalog.refresh.refreshProjection()
            }
        }
    }
    var refreshing by remember(source) { mutableStateOf(false) }
    var refreshError by remember(source) { mutableStateOf<String?>(null) }
    val revision by catalog.downloads.changes.collectAsStateWithLifecycle()
    val transfers by catalog.downloads.transfers.collectAsStateWithLifecycle()
    // The TV presentation suppresses captions in its Modern layout. Mobile uses the profile's actual preference.
    val showCaptions by catalog.layout.posterLabelsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val saved by produceState(emptyList<OfflineFile>(), profile, revision) {
        value = withContext(Dispatchers.IO) { catalog.downloads.saved(profile) }
    }
    var menu by remember { mutableStateOf<Pair<MetaPreview,String?>?>(null) }
    var resumeMenu by remember { mutableStateOf<Pair<MobileVideo, ContinueWatchingItem>?>(null) }
    var manage by remember { mutableStateOf<OfflineFile?>(null) }
    var removeDownload by remember { mutableStateOf<OfflineFile?>(null) }
    var providers by rememberSaveable { mutableStateOf(false) }
    var orderRows by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val width = LocalConfiguration.current.screenWidthDp.dp
    val gutter = if (width >= 600.dp) 24.dp else 16.dp
    val posterWidth = (((width - gutter - 30.dp) / 3.2f).coerceIn(88.dp, 146.dp) * (state.posterCardWidthDp / 126f)).coerceIn(64.dp, 240.dp)
    val corners = androidx.compose.foundation.shape.RoundedCornerShape((state.posterCardCornerRadiusDp.coerceIn(0, 60) * 7f / 12f).dp)
    val homePattern = patternForScreen(state.customPosterUrlPattern, CustomPosterScreen.HOME, state.customPosterEnabledScreens)
    val continuePattern = patternForScreen(state.customPosterUrlPattern, CustomPosterScreen.CONTINUE_WATCHING, state.customPosterEnabledScreens)
    val insets = PaddingValues(horizontal = gutter)
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to colors.surface, .5f to colors.background, 1f to colors.background)),
        contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = gutter).heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                BrandWordmark(Modifier.size(96.dp, 32.dp), contentDescription = "Nuvio ReSeq")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { navigate(Screen.FlickPick.route) }) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Label("Flick Pick", strong = true)
                }
                TextButton(onClick = { orderRows = true }, contentPadding = PaddingValues(horizontal = 5.dp)) {
                    Icon(Icons.Default.SwapVert, null, Modifier.size(18.dp))
                    Label("Reorder", small = true)
                }
                IconButton(onClick = { providers = true }) {
                    Icon(Icons.Default.Tune, contentDescription = "Catalog sources", modifier = Modifier.size(20.dp), tint = colors.onSurface)
                }
            }
        }
        val hero = state.heroItems.ifEmpty { state.catalogRows.firstOrNull()?.items.orEmpty() }
        if (state.heroSectionEnabled && hero.isNotEmpty()) item {
            HomeHighlights(hero.take(8), gutter, state.homeImdbRatingsVisibility.showRatings, open = { open(it, null, VideoAction.Details) },
                more = { if (it.isTvMix) open(it, null, VideoAction.Details) else menu = it to null })
        }
        if (state.continueWatchingEnabled && state.continueWatchingItems.isEmpty()) item {
            ShelfHeading("Continue watching", gutter, trackerName(source))
            Row(Modifier.fillMaxWidth().padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
                Label(refreshError ?: if (!resolved || refreshing) "Checking watch progress…" else "No unfinished titles from this source.",
                    Modifier.weight(1f), small = true)
                TextButton(enabled = !refreshing, onClick = {
                    refreshing = true
                    refreshError = null
                    scope.launch {
                        try {
                            if (source == com.nuvio.tv.data.local.WatchProgressSource.NUVIO_SYNC) catalog.sync.requestSyncNow()
                            val failures = catalog.progress.refreshSelected(com.nuvio.tv.core.tracking.TrackingRefreshIntent.USER_INITIATED)
                            if (failures.isNotEmpty()) refreshError = "Couldn’t refresh " + trackerName(source) + ". Try again."
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            refreshError = "Couldn’t read watch progress. Try again."
                        } finally { refreshing = false }
                    }
                }) { Label("Refresh", small = true) }
            }
        }
        if (state.continueWatchingEnabled) listOf(
            "Continue watching" to state.continueWatchingItems, "Upcoming" to state.upcomingItems
        ).forEach { (heading, entries) -> if (entries.isNotEmpty()) item {
            ShelfHeading(heading, gutter)
            LazyRow(contentPadding = insets, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries) { original ->
                    val entry = remember(original, continuePattern) { original.withCustomPosterUrl(continuePattern) }
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
                        val customLandscape = when (entry) {
                            is ContinueWatchingItem.InProgress -> entry.customLandscapePoster
                            is ContinueWatchingItem.NextUp -> entry.customLandscapePoster
                        }
                        val fallback = when (entry) {
                            is ContinueWatchingItem.InProgress -> entry.originalPoster
                            is ContinueWatchingItem.NextUp -> entry.originalPoster
                        }
                        val artwork = thumbnail.takeIf { state.useEpisodeThumbnailsInCw } ?: customLandscape ?: video.poster
                        Box(Modifier.fillMaxWidth().height(96.dp).clip(corners).background(colors.surfaceVariant),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp), tint = colors.onSurfaceVariant)
                            if (!artwork.isNullOrBlank()) AsyncImage(posterModel(artwork, fallback), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (entry.shufflePlayback) Icon(Icons.Default.Shuffle, "Episode shuffle",
                                Modifier.align(Alignment.TopEnd).padding(5.dp)
                                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .75f), mediaCorners).padding(4.dp).size(17.dp),
                                tint = androidx.compose.ui.graphics.Color.White)
                        }
                        if (entry is ContinueWatchingItem.InProgress) LinearProgressIndicator(
                            progress = { entry.progress.progressPercentage }, modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = "Watch progress for " + video.label
                            }
                        )
                        Label(video.label, Modifier.padding(top = 4.dp), lines = 2)
                        if (entry is ContinueWatchingItem.NextUp && !entry.info.hasAired) entry.info.airDateLabel?.let { Label(it, small = true) }
                    }
                }
            }
        } }
        if (saved.isNotEmpty()) {
            item { ShelfHeading("Downloads", gutter, seeAll = { navigate("downloads") }) }
            items(saved, key = { "offline-" + it.video.key }) { entry ->
                Row(Modifier.padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
                    Action(entry.video.label, Modifier.weight(1f)) { openVideo(entry.video, VideoAction.Play) }
                    Action("Manage") { manage = entry }
                }
            }
        }
        items(transfers.values.filter { it.video.profile == profile && (it.active || it.text != "Downloaded") }) {
            Label(it.video.label + " · " + it.text, Modifier.padding(horizontal = gutter))
        }
        if (state.catalogRows.isEmpty()) item { Label(state.error ?: if (state.isLoading) "Loading catalogues…" else "Add a catalogue source to browse titles.", Modifier.padding(gutter)) }
        val rows = state.homeRows.ifEmpty { state.catalogRows.map { HomeRow.Catalog(it) } }
        items(rows) { entry ->
            if (entry is HomeRow.CollectionRow) {
                ShelfHeading(entry.collection.title, gutter)
                LazyRow(contentPadding = insets, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(entry.collection.folders) { folder ->
                        Action(folder.title) { navigate(Screen.FolderDetail.createRoute(entry.collection.id, folder.id)) }
                    }
                }
                return@items
            }
            if (entry is HomeRow.PlaceholderCatalog) {
                LaunchedEffect(entry.catalogKey) { vm.requestLazyCatalogLoad(entry.catalogKey) }
                ShelfHeading(entry.displayTitle, gutter)
                Spacer(Modifier.height(120.dp))
                return@items
            }
            val row = (entry as HomeRow.Catalog).row
            val typeLabel = when (row.apiType) { "series" -> "Series"; "movie" -> "Movies"; else -> null }
            val subtitle = listOfNotNull(typeLabel, row.addonName.takeIf { state.catalogAddonNameEnabled && it.isNotBlank() }).joinToString(" · ")
            ShelfHeading(row.catalogName, gutter, subtitle) {
                navigate(if (row.apiType == TvMixHomeCatalog.TYPE) "mobile-mix" else Screen.CatalogSeeAll.createRoute(row.catalogId, row.addonId, row.apiType))
            }
            LazyRow(contentPadding = insets, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(row.items) { item ->
                    val cover = remember(item, homePattern) { if (item.isTvMix) item else item.withCustomPosterUrl(homePattern) }
                    Poster(cover, Modifier.width(posterWidth), open = { open(item, row.addonBaseUrl, VideoAction.Details) },
                        more = { if (item.isTvMix) open(item, null, VideoAction.Details) else menu = item to row.addonBaseUrl }, showCaption = showCaptions, corners = corners)
                }
                if (row.hasMore && row.supportsSkip) item {
                    Action("More titles") { vm.onEvent(HomeEvent.OnLoadMoreCatalog(row.catalogId, row.addonId, row.apiType)) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    menu?.let { (item, addon) ->
        val key = homeItemStatusKey(item.id, item.apiType)
        val movie = item.apiType == "movie"
        VideoActions(item.name, { menu = null }, extra = {
            if (movie || item.apiType in setOf("series", "tv")) {
                val watched = state.movieWatchedStatus[key] == true
                val ready = key !in state.movieWatchedPending
                fun toggle(date: Long?) {
                    if (movie) vm.togglePosterMovieWatched(item, date) else vm.togglePosterSeriesWatched(item, date)
                    menu = null
                }
                val subject = if (movie) item.name else "${item.name} · All episodes"
                if (watched) MediaIconAction(if (movie) "Mark unwatched" else "Mark series unwatched", Icons.Default.RemoveDone,
                    Modifier.fillMaxWidth(), showLabel = true, enabled = ready) { toggle(null) }
                else MarkWatchedAction(if (movie) "Mark watched" else "Mark series watched", subject, Modifier.fillMaxWidth(), ready, ::toggle)
            }
        }) { action -> menu = null; open(item, addon, action) }
    }
    manage?.let { entry -> TouchSheet({ manage = null }) {
        SectionTitle(entry.video.label)
        Action("Play downloaded file") { manage = null; openVideo(entry.video, VideoAction.Play) }
        Action("Remove download") { manage = null; removeDownload = entry }
    } }
    removeDownload?.let { entry -> DeleteDownloadDialog(entry, cancel = { removeDownload = null }) {
        removeDownload = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { catalog.downloads.remove(entry) } }
            result.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
        }
    } }
    if (orderRows) TouchSheet({ orderRows = false }) { MobileCatalogOrder { orderRows = false } }
    if (providers) CatalogSources { providers = false }
    resumeMenu?.let { (video, row) ->
        VideoActions(video.label, { resumeMenu = null }, playable = !row.chooseEpisode && (row !is ContinueWatchingItem.NextUp || row.info.hasAired), extra = {
            if (row.canMarkWatched()) MarkWatchedAction("Mark watched", video.label, Modifier.fillMaxWidth()) { date ->
                vm.onEvent(HomeEvent.OnMarkContinueWatchingWatched(row, date))
                resumeMenu = null
            }
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
            item { Action("Reorder Home rows") { ordering = true } }
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
internal fun MobileCatalogOrder(vm: com.nuvio.tv.ui.screens.addon.CatalogOrderViewModel = hiltViewModel(), back: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    DisposableEffect(vm) { onDispose { vm.cancelMove() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        BrowseHeading("Reorder Home rows", back)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Action("Custom order", Modifier.weight(1f), !state.followAddonsOrder) { if (state.followAddonsOrder) vm.toggleFollowAddonsOrder(false) }
            Action("Source order", Modifier.weight(1f), state.followAddonsOrder) { if (!state.followAddonsOrder) vm.toggleFollowAddonsOrder(true) }
        }
        if (state.followAddonsOrder) Label("Choose Custom order to move individual lists.", Modifier.padding(vertical = 4.dp), small = true)
        if (state.isLoading) Label("Loading lists…") else if (state.items.isEmpty()) Label("Your catalogue lists and collections will appear here.")
        LazyColumn(state = list, contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(state.items, key = { _, item -> item.key }) { index, item ->
                fun move(by: Int) {
                    val destination = index + by
                    val first = list.firstVisibleItemIndex
                    val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
                    // Keep the moved row in view instead of anchoring to the previous first row's key.
                    val newFirst = when {
                        destination < first -> destination
                        destination > last -> first + 1
                        else -> first
                    }
                    list.requestScrollToItem(newFirst, if (newFirst == first && destination != first) list.firstVisibleItemScrollOffset else 0)
                    vm.enterMoveMode(item.key)
                    vm.moveDuringDrag(item.key, by)
                    vm.confirmMove()
                }
                Row(Modifier.fillMaxWidth().mediaPanel().padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(item.catalogName, strong = true, lines = 2)
                        Label(listOfNotNull(item.addonName.takeIf { it.isNotBlank() }, item.typeLabel.takeIf { it.isNotBlank() },
                            "Pinned".takeIf { item.isPinnedToTop }).joinToString(" · "), small = true, lines = 2)
                    }
                    IconButton(onClick = { move(-1) }, enabled = item.canMoveUp) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move " + item.catalogName + " up")
                    }
                    IconButton(onClick = { move(1) }, enabled = item.canMoveDown) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move " + item.catalogName + " down")
                    }
                    IconButton(onClick = { vm.toggleCatalogEnabled(item.disableKey) }) {
                        Icon(if (item.isDisabled) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            (if (item.isDisabled) "Show " else "Hide ") + item.catalogName)
                    }
                }
            }
        }
    }
}
@Composable
internal fun VideoActions(label: String, close: () -> Unit, playable: Boolean = true, showDetails: Boolean = true,
    extra: @Composable () -> Unit = {}, choose: (VideoAction) -> Unit) {
    TouchSheet(close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            SectionTitle(label)
            if (playable) {
                Action("Play", Modifier.fillMaxWidth()) { choose(VideoAction.Play) }
                Action("Play manually", Modifier.fillMaxWidth()) { choose(VideoAction.Manual) }
                DownloadAction(modifier = Modifier.fillMaxWidth(), showLabel = true) { choose(VideoAction.Download) }
            }
            if (showDetails) Action("Title details", Modifier.fillMaxWidth()) { choose(VideoAction.Details) }
            extra()
        }
    }
}

@Composable
private fun CatalogDetail(profile: Int, initial: String?, preview: String, id: String, type: String,
    open: (MobileVideo, VideoAction) -> Unit, back: () -> Unit, navigate: (String) -> Unit,
    openTitle: (MetaPreview, String?, VideoAction) -> Unit, mix: TvMixViewModel, layout: LayoutPreferenceDataStore, sourceAddon: String?,
    guideNumber: String, guideSubtitle: String, vm: MetaDetailsViewModel = hiltViewModel(), ratings: RatingViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val meta = state.meta
    val movie = type == "movie"
    val detailScope = rememberCoroutineScope()
    val trailerNetwork = LocalTrailerNetwork.current
    val trailersAllowed by trailerNetwork.playbackAllowed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(ratings, context) {
        ratings.results.collect { result ->
            val message = when {
                !result.saved -> context.getString(R.string.rating_failed, result.provider?.let { context.getString(trackingProviderNameRes(it)) }.orEmpty())
                result.removed -> context.getString(R.string.rating_removed)
                else -> context.getString(R.string.rating_saved)
            }
            vm.onEvent(MetaDetailsEvent.OnShowMessage(message, isError = !result.saved))
        }
    }
    LaunchedEffect(state.userMessage) {
        state.userMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.onEvent(MetaDetailsEvent.OnClearMessage)
        }
    }
    var previouslyBlocked by remember(vm) { mutableStateOf(!trailersAllowed) }
    LaunchedEffect(trailersAllowed) {
        if (trailersAllowed && previouslyBlocked) vm.retryMissingTrailer()
        previouslyBlocked = !trailersAllowed
    }
    fun trailerAction(event: MetaDetailsEvent) {
        if (trailerNetwork.allowsPlaybackNow()) vm.onEvent(event)
        else Toast.makeText(context, TRAILER_DATA_MESSAGE, Toast.LENGTH_SHORT).show()
    }
    var comments by rememberSaveable { mutableStateOf(false) }
    var shuffle by rememberSaveable { mutableStateOf(false) }
    var addToMix by rememberSaveable { mutableStateOf(false) }
    val airing = remember(meta, guideNumber, guideSubtitle) { meta?.let { guideEpisode(it.videos, guideNumber, guideSubtitle) } }
    var selectedAiringSeason by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(airing, state.seasons) {
        val season = airing?.displaySeason
        if (!selectedAiringSeason && season != null && season in state.seasons) {
            vm.onEvent(MetaDetailsEvent.OnSeasonSelected(season))
            selectedAiringSeason = true
        }
    }
    val hero = airing ?: meta?.takeUnless { state.shufflePoolEmpty }?.let { resolveHeroPlaybackVideo(it, state.nextToWatch, state.episodesForSeason) }
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
    var showExtras by rememberSaveable(id, type) { mutableStateOf(false) }
    val episodes = remember(state.episodesForSeason) { state.episodesForSeason.distinctBy { it.episodeListKey } }
    val ambiguousSlots = remember(episodes) { episodes.ambiguousEpisodeSlots() }
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
    val list = rememberLazyListState()
    var resumed by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        resumed = true
        onPauseOrDispose { resumed = false; vm.onEvent(MetaDetailsEvent.OnLifecyclePause) }
    }
    val castState by LocalCasting.current.state.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= 720.dp && maxWidth > maxHeight
        val heroVisible by remember(split, list) { derivedStateOf { split || list.firstVisibleItemIndex == 0 } }
        val showingDialog = comments || shuffle || addToMix || menu != null || state.showListPicker || state.isSharedTrailerOverlayVisible
        val canPreview = resumed && trailersAllowed && !showingDialog && castState.item == null &&
            (initial == null || initial == VideoAction.Details.name)
        val startTimer = canPreview && heroVisible && !list.isScrollInProgress && meta != null
        LaunchedEffect(startTimer) {
            vm.onEvent(if (startTimer) MetaDetailsEvent.OnPlayButtonFocused else MetaDetailsEvent.OnUserInteraction)
        }
        val header: @Composable (Boolean) -> Unit = { wide ->
            MobileDetailHero(meta, detailTitle, wide, back, preview = {
                if (state.isBackgroundTrailerPlaying && trailersAllowed && resumed) {
                    com.nuvio.tv.ui.components.TrailerPlayer(trailerUrl = state.trailerUrl, trailerAudioUrl = state.trailerAudioUrl,
                        isPlaying = true, isPaused = !canPreview || (state.pauseBackgroundTrailerOnScroll && list.isScrollInProgress),
                        muted = false, cropToFill = true, onEnded = { vm.onEvent(MetaDetailsEvent.OnTrailerEnded) },
                        onError = { vm.onEvent(MetaDetailsEvent.OnTrailerEnded) }, modifier = Modifier.fillMaxSize())
                }
            }) { menu = target }
        }
        val padding = if (minOf(maxWidth, maxHeight) >= 600.dp || maxWidth >= 720.dp) 20.dp else 8.dp
        Row(Modifier.fillMaxSize()) {
            if (split) Column(Modifier.weight(.38f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = padding, end = 12.dp)) { header(true) }
            LazyColumn(Modifier.weight(if (split) .62f else 1f), state = list, contentPadding = PaddingValues(horizontal = padding, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!split) item { header(false) }
        if (state.shufflePoolEmpty) item { Label("No episodes match this show's shuffle settings.") }
        if (target != null) item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val prefix = if (airing == null && state.nextToWatch?.isResume == true) "Resume" else "Play"
            val label = if (!movie && hero?.displaySeason != null && hero.displayEpisode != null)
                "$prefix S${hero.displaySeason} E${hero.displayEpisode}" else prefix
            PrimaryMediaAction(label, Modifier.weight(1f), onLongClick = { menu = target }) { open(target, VideoAction.Play) }
            MediaIconAction("Play manually", Icons.Default.Tune, showLabel = true) { open(target, VideoAction.Manual) }
            DownloadAction { open(target, VideoAction.Download) }
        } }
        if (!movie && hero != null && target != null) item {
            Row(Modifier.fillMaxWidth().mediaPanel().clickable { menu = target }.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaArt(hero.thumbnail ?: meta?.backdropUrl, Icons.Default.PlayArrow, Modifier.size(80.dp, 48.dp).clip(mediaCorners))
                Column(Modifier.weight(1f)) {
                    Label(if (airing != null) "Episode from the guide" else if (state.nextToWatch?.isResume == true) "Continue watching" else "Up next", small = true)
                    Label("S${hero.displaySeason} E${hero.displayEpisode}  ·  ${hero.title}", strong = true, lines = 2)
                }
            }
        }
        if (!meta?.description.isNullOrBlank()) item { Label(meta?.description.orEmpty(), Modifier.padding(horizontal = 5.dp), small = true, lines = 5) }
        if (meta != null) item {
            if (state.isMdbListRatingsActive && state.mdbListRatings != null)
                com.nuvio.tv.ui.components.MDBListRatingsRow(state.mdbListRatings!!, Modifier.horizontalScroll(rememberScrollState()).padding(5.dp),
                    order = state.mdbListRatingOrder, iconSize = 17.dp)
            else if (state.overallRatingsVisibility.showStandardDetailRatings(state.isMdbListRatingsActive))
                Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    meta.imdbRating?.let { StandardRating("IMDb", R.raw.imdb_logo_2016, it) }
                    state.tmdbRating?.let { StandardRating("TMDB", R.raw.mdblist_tmdb, it) }
                }
        }
        if (meta != null) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { val saved = state.isInLibrary || state.isInWatchlist
                    MediaIconAction(if (saved) "Remove from library" else "Add to library", if (saved) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd) {
                    if (!state.defaultLibraryTogglePending) vm.onEvent(MetaDetailsEvent.OnToggleLibrary)
                } }
                if (movie) item {
                    if (state.isMovieWatched) MediaIconAction("Mark unwatched", Icons.Default.RemoveDone, showLabel = true,
                        enabled = !state.isMovieWatchedPending) { vm.onEvent(MetaDetailsEvent.OnToggleMovieWatched) }
                    else MarkWatchedAction("Mark watched", meta.name, enabled = !state.isMovieWatchedPending) {
                        vm.onEvent(MetaDetailsEvent.OnMarkMovieWatched(it))
                    }
                }
                if (state.libraryListTabs.isNotEmpty()) item { MediaIconAction("Choose lists", Icons.Default.PlaylistAdd) { vm.onEvent(MetaDetailsEvent.OnLibraryLongPress) } }
                item { MobileRating(meta.ratingReference()) }
                if (!movie) item { MobileEpisodeOrder(meta, vm::reloadEpisodeOrder) }
                if (!movie && state.randomEpisodeEnabled) item { MediaIconAction("Episode shuffle", Icons.Default.Shuffle, selected = state.episodeShuffle.enabled) { shuffle = true } }
                if (!movie && state.seasons.isNotEmpty()) item {
                    SeasonWatchedAction(state.selectedSeason, meta.name, vm.isSeasonFullyWatched(state.selectedSeason),
                        enabled = state.episodeWatchedPendingKeys.isEmpty(), send = vm::onEvent)
                }
                if (state.trailerButtonEnabled && state.trailerUrl != null) item { Action("Play trailer") { trailerAction(MetaDetailsEvent.OnTrailerButtonClick) } }
                if (state.shouldShowCommentsSection) item { Action("Reviews and comments") { comments = true } }
            }
        }
        if (meta?.apiType in setOf("series", "tv", "anime")) item { Action("Add to TV Mix") { addToMix = true } }
        if (state.seasons.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Action("Episodes", Modifier.weight(1f), !showExtras) { showExtras = false }
                Action("Cast and extras", Modifier.weight(1f), showExtras) { showExtras = true }
            }
        }
        if (!showExtras && state.seasons.isNotEmpty() && meta != null) {
            item { SeasonRail(meta, state.seasons, state.selectedSeason, state.seasonPostersEnabled,
                togglePosters = { detailScope.launch { layout.setDetailSeasonPostersEnabled(!state.seasonPostersEnabled) } }) {
                vm.onEvent(MetaDetailsEvent.OnSeasonSelected(it))
            } }
            items(episodes, key = { "episode-${it.episodeIdentity?.orderType}-${it.episodeListKey}" }) { episode ->
                val video = selection(episode)
                val tracking = state.trackingForEpisode(episode, (episode.displaySeason to episode.displayEpisode) in ambiguousSlots)
                val rating = (tracking.displayKey?.let(state.episodeImdbRatings::get) ?: episode.rating)
                    .takeIf { state.detailImdbRatingsVisibility.showEpisodeRating(tracking.watched) }
                EpisodeTile(episode, tracking.watched, tracking.progress?.progressPercentage ?: 0f, rating, video != null,
                    open = { video?.let { open(it, VideoAction.Play) } },
                    highlighted = airing != null && airing.episodeListKey == episode.episodeListKey) { close ->
                    EpisodeSheetActions(episode, tracking, state, meta, vm.isSeasonFullyWatched(state.selectedSeason),
                        play = video?.let { selected -> { action -> close(); open(selected, action) } },
                        send = { close(); vm.onEvent(it) },
                        remove = { event, last -> close(); vm.removeEpisodeWatch(episode.forEpisodeSource(), event, last, "Last watch removed") },
                        comments = { close(); vm.onEvent(MetaDetailsEvent.OnCommentsEpisodeSelected(episode)); comments = true })
                }
            }
        } else if (meta != null) {
            if (state.trailerButtonEnabled && meta.trailers.isNotEmpty()) item {
                if (trailersAllowed) TrailerRail(meta) { trailerAction(MetaDetailsEvent.OnSharedTrailerSelected(it)) }
                else {
                    SectionTitle("Trailers")
                    Label(TRAILER_DATA_MESSAGE, small = true)
                }
            }
            titleConnections(meta, state, navigate, openTitle)
        }
        state.error?.let { item { Label(it) } }
            }
        }
    }
    if (!trailersAllowed && state.isBackgroundTrailerPlaying) LaunchedEffect(vm) { vm.onEvent(MetaDetailsEvent.OnTrailerEnded) }
    if (state.isSharedTrailerOverlayVisible || state.isTrailerPlaying) {
        if (trailersAllowed) MobileTrailer(state, vm::onEvent)
        else LaunchedEffect(state.isSharedTrailerOverlayVisible) {
            vm.onEvent(if (state.isSharedTrailerOverlayVisible) MetaDetailsEvent.OnDismissSharedTrailer else MetaDetailsEvent.OnTrailerEnded)
            Toast.makeText(context, TRAILER_DATA_MESSAGE, Toast.LENGTH_SHORT).show()
        }
    }
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
internal fun MobileRating(media: com.nuvio.tv.core.tracking.TrackingMediaReference, modifier: Modifier = Modifier, vm: RatingViewModel = hiltViewModel()) {
    val state by rememberRatingState(media, vm)
    var show by remember { mutableStateOf(false) }
    if (!state.isAvailable) return
    MediaIconAction(state.rating?.let { "Rated $it" } ?: "Rate", Icons.Default.Star, modifier, showLabel = true) { show = true }
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
