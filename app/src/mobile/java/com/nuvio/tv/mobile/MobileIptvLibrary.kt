// Touch IPTV browsing and programme sheets adapted from NuvioPlusIPTV-Mobile (GPLv3).
package com.nuvio.tv.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ondemand.*
import com.nuvio.tv.livetv.record.LiveTvRecording
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.ui.navigation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MobileIptvLibrary(back: () -> Unit, play: (String) -> Unit, vm: OnDemandViewModel = hiltViewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<VodItem?>(null) }
    var locked by remember { mutableStateOf<OnDemandSection?>(null) }
    var global by rememberSaveable { mutableStateOf(false) }
    var categories by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val matches by produceState<List<VodItem>>(emptyList(), global, query) {
        value = emptyList()
        if (global && query.trim().length >= 2) { delay(250); value = vm.searchEverywhere(query) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        BrowseHeading("IPTV movies and series", back)
        LazyRow {
            items(VodKind.entries.toList()) { kind -> Action(if (kind == VodKind.MOVIE) "Movies" else "Series", selected = state.kind == kind && !global) {
                global = false; vm.selectKind(kind)
            } }
            item { Action("Search all", selected = global) { global = true } }
            if (!global) item { Action("Manage categories") { categories = true } }
            if (!global && state.hasGenres) item { Action("Genres", selected = state.byGenre, onClick = vm::toggleGenres) }
        }
        MobileTextInput(if (global) "Search all IPTV libraries" else "Search this library", if (global) query else state.query) { if (global) query = it else vm.search(it) }
        if (!global) {
            LazyRow { items(state.sections) { section ->
                val title = when (section) { OnDemandSection.All -> "All"; OnDemandSection.Recent -> "Recent"
                    is OnDemandSection.Category -> section.category.name; is OnDemandSection.Genre -> section.name }
                Action(title, selected = section.key == state.selectedKey) {
                    if (vm.isLocked(section)) locked = section else vm.select(section)
                }
            } }
            LazyRow { items(VodSort.entries.toList()) { sort -> Action(sort.label, selected = sort == state.sort) { vm.setSort(sort) } } }
        }
        val items = if (global) matches else state.items
        if (state.loading && !global) LinearProgressIndicator()
        if (items.isEmpty() && !state.loading) Label(if (global) "Search by movie or series title." else "No titles in this selection. Enable movie and series import for your IPTV source.")
        LazyVerticalGrid(GridCells.Adaptive(108.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items.size) { index ->
                val item = items[index]
                Column(Modifier.fillMaxWidth().clickable { selected = item }) {
                    AsyncImage(vm.posterFor(item), null, Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentScale = ContentScale.Crop)
                    Label(vm.nameFor(item), small = true, lines = 2)
                }
            }
            if (!global && !state.endReached && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Action("Load more", onClick = vm::loadMore) }
        }
    }
    locked?.let { section -> MobilePinPrompt("Unlock category", { locked = null }) { pin ->
        vm.unlock(section, pin).also { if (it) { locked = null; vm.select(section) } }
    } }
    if (categories) MobileVodCategories(vm, state.kind) { categories = false }
    selected?.let { item ->
        val info by produceState<VodInfo?>(null, item.uid) { value = vm.info(item) }
        val versions by produceState<List<OnDemandRepository.Version>>(emptyList(), item.uid) {
            if (item.kind == VodKind.MOVIE) value = vm.versions(item)
        }
        var season by remember(item.uid) { mutableStateOf<Int?>(null) }
        var error by remember(item.uid) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        fun playUrl(url: String?, title: String) {
            if (url.isNullOrBlank()) { error = "The provider did not return a playback URL."; return }
            selected = null
            play(Screen.Player.createRoute(streamUrl = url, title = title, contentType = item.kind.key, contentName = title,
                poster = item.icon, addonName = OnDemandStreams.GROUP_NAME))
        }
        TouchSheet({ selected = null }) {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { SectionTitle(vm.nameFor(item))
                    Label(listOfNotNull(item.year?.toString(), info?.genre, info?.durationText, item.rating).joinToString(" · "))
                    info?.plot?.let { Text(it) }
                    error?.let { Label(it) }
                }
                if (item.kind == VodKind.MOVIE) {
                    if (versions.size > 1) items(versions) { version -> Action("Play · " + version.label + " " + version.detail) { scope.launch { playUrl(vm.movieUrl(version.item), item.name) } } }
                    else item { Action("Play") { scope.launch { playUrl(vm.movieUrl(item), item.name) } } }
                } else {
                    val eps = info?.episodes.orEmpty()
                    val seasons = eps.map { it.season }.distinct()
                    val active = season ?: seasons.firstOrNull()
                    item { LazyRow { items(seasons) { s -> Action("Season " + s, selected = active == s) { season = s } } } }
                    items(eps.filter { it.season == active }) { e -> Action("S${e.season}E${e.episode} · ${e.title}") { playUrl(e.url, "${item.name} · S${e.season}E${e.episode}") } }
                    if (eps.isEmpty()) item { Label(if (info == null) "Loading episodes…" else "The provider has no episodes for this series.") }
                }
            }
        }
    }
}

@Composable
internal fun MobileRecordings(back: () -> Unit, play: (String) -> Unit, vm: LiveTvViewModel = hiltViewModel()) {
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val rules by vm.seriesRules.collectAsStateWithLifecycle()
    var remove by remember { mutableStateOf<LiveTvRecording?>(null) }
    LazyColumn(contentPadding = PaddingValues(10.dp)) {
        item { BrowseHeading("Recordings", back) }
        if (recordings.isEmpty() && schedules.isEmpty() && rules.isEmpty()) item { Label("Select a programme in the guide to record it or schedule its series.") }
        items(recordings, key = { it.id }) { rec ->
            SectionTitle(rec.title)
            Label(rec.channelName + " · " + rec.status.name.lowercase())
            rec.problem?.let { Label(it) }
            Row {
                Action("Play") { play(Screen.Player.createRoute(streamUrl = vm.recorder.playUrl(rec), title = rec.title, contentType = "movie", contentName = rec.title)) }
                if (rec.isRecording) Action("Stop") { vm.recorder.stop(rec.id) }
                else Action("Delete") { remove = rec }
            }
        }
        if (schedules.isNotEmpty()) item { SectionTitle("Scheduled") }
        items(schedules) { s -> Label(s.title + " · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.startMs))); Action("Cancel recording") { vm.scheduler.cancel(s.id) } }
        if (rules.isNotEmpty()) item { SectionTitle("Series rules") }
        items(rules) { rule -> Label(rule.title); Action("Cancel series") { vm.scheduler.removeSeries(rule.id) } }
    }
    remove?.let { rec -> ConfirmAction("Delete " + rec.title + "?", "Delete", { remove = null }) { vm.recorder.delete(rec.id); remove = null } }
}
