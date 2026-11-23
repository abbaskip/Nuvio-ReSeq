package com.nuvio.tv.mobile

import android.text.Html
import android.text.style.URLSpan
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MobileSportsPreferences(vm: SportsViewModel, close: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var parentId by rememberSaveable { mutableStateOf<String?>(null) }
    val parent = parentId?.let(SportsTaxonomy.byId::get)
    val back = { if (parent == null) close() else parentId = parent.parentId }
    LaunchedEffect(vm) { configured = withContext(Dispatchers.IO) { vm.gemini.configured() } }
    fun save(value: String?) { scope.launch {
        sportsCatching { vm.gemini.save(value) }.onSuccess {
            configured = !value.isNullOrBlank(); key = ""; note = if (configured) "Key saved" else "Key removed"
        }.onFailure { note = "Could not save the Gemini key." }
    } }
    TouchSheet(back) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            item { BrowseHeading(parent?.label ?: "Sports preferences", back) }
            if (parent == null) item {
                Label("Sports and TV coverage region")
                LazyRow { items(SportsCatalog.regions.toList()) { (code, label) -> Action(label, selected = SportsCatalog.regionOf(s) == code) {
                    vm.repo.updateSettings { it.copy(region = code) }
                } } }
                TogglePreference("Show only games on my channels", s.onlyOnMyChannels) { v -> vm.repo.updateSettings { it.copy(onlyOnMyChannels = v) } }
                TogglePreference("Best channel first", s.bestChannelFirst) { v -> vm.repo.updateSettings { it.copy(bestChannelFirst = v) } }
                MobileSecretField("Gemini API key", key) { key = it }
                Row { Action("Save key") { if (key.isNotBlank()) save(key.trim()) }; if (configured) Action("Remove key") { save(null) } }
                if (note.isNotBlank()) Label(note)
                Label("Gemini guide matching sends uncertain fixture and programme text to Google using your key. Google project charges may apply.")
                TogglePreference("Use Gemini guide matching", s.backgroundGeminiMatching) { v ->
                    if (!v || configured) vm.repo.updateSettings { it.copy(backgroundGeminiMatching = v) } else note = "Save a Gemini key first."
                }
                SectionTitle("Sports and competitions")
            }
            items(parent?.children ?: SportsTaxonomy.browseRoots, key = { it.id }) { node ->
                Row {
                    Action(node.label, Modifier.weight(1f), selected = node.id !in s.excludedSportsNodes) {
                        vm.repo.updateSettings { current ->
                            val excluded = current.excludedSportsNodes
                            current.copy(excludedSportsNodes = if (node.id in excluded) excluded - node.id else excluded + node.id)
                        }
                    }
                    if (node.children.isNotEmpty()) Action("Browse") { parentId = node.id }
                }
            }
        }
    }
}

internal val LiveChannel.sportsChannelLabel: String
    get() = listOf(name, sourceName).filter(String::isNotBlank).distinct().joinToString(" · ")

@Composable
internal fun MobileFixtureChannels(event: SportsEvent, matches: List<ChannelMatch>, vm: SportsViewModel, close: () -> Unit) {
    val all by vm.repo.channelsForPicker.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf<LiveChannel?>(null) }
    val found = remember(query, all) {
        val word = SportsCatalog.norm(query).trim()
        if (word.length < 2) emptyList() else all.asSequence().filter { SportsCatalog.norm(it.sportsChannelLabel).contains(word) }.take(80).toList()
    }
    TouchSheet(close) {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item { BrowseHeading("Match channels", close); Label(event.title) }
            val channel = picked
            if (channel == null) {
                items(matches, key = { it.channel.key }) { m -> Action("Exclude " + m.channel.sportsChannelLabel) { vm.repo.blockChannel(event, m.channel.key) } }
                item { MobileTextInput("Find channel", query) { query = it } }
                items(found, key = { it.key }) { ch -> Action(ch.sportsChannelLabel) { picked = ch } }
                if (query.length >= 2 && found.isEmpty()) item { Label("No matching channels.") }
            } else item {
                SectionTitle(channel.sportsChannelLabel)
                Action("Use for this game") { vm.repo.pickChannel(event, channel.key, false); close() }
                if (event.broadcasts.isNotEmpty()) Action("Use for " + event.broadcasts.joinToString()) { vm.repo.pickChannel(event, channel.key, true); close() }
                if (SportsCatalog.league(event.leagueId)?.kind == LeagueKind.EVENT) Action("Use for this competition") { vm.repo.pickChannel(event, channel.key, false, forLeague = true); close() }
                event.sides.forEach { side -> Action("Use for " + side.team.name) { vm.repo.pickChannel(event, channel.key, false, forTeam = side.team); close() } }
                Action("Choose another channel") { picked = null }
            }
        }
    }
}

@Composable
internal fun MobileSportsLookup(event: SportsEvent, vm: SportsViewModel, close: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var revision by remember { mutableIntStateOf(0) }
    var answer by remember { mutableStateOf<android.text.Spanned?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(event.id, settings.region, revision) {
        answer = null; error = null
        sportsCatching { vm.gemini.lookup(event, SportsCatalog.regionOf(settings)) }
            .onSuccess { answer = Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT) }
            .onFailure { error = it.message ?: "Could not load coverage." }
    }
    TouchSheet(close) {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item {
                BrowseHeading("Gemini coverage lookup", close)
                Label(event.title, strong = true)
                val text = answer
                if (text != null) {
                    Label(text.toString().trim())
                    text.getSpans(0, text.length, URLSpan::class.java).distinctBy { it.url }.forEach { span ->
                        if (android.net.Uri.parse(span.url).scheme == "https") Action(text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString()) { uri.openUri(span.url) }
                    }
                } else Label(error ?: "Checking sources…")
                if (error != null || text != null) Action("Refresh") { revision++ }
            }
        }
    }
}
