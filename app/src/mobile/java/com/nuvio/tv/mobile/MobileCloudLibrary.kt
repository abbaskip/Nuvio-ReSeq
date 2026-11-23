package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.cloud.*
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.library.LibraryViewModel
import kotlinx.coroutines.launch

@Composable
internal fun MobileCloudLibrary(back: () -> Unit, play: (String) -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val cloud = state.cloudLibrary
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<CloudLibraryItem?>(null) }
    var choice by remember { mutableStateOf<CloudLibraryPlaybackInfo?>(null) }
    LaunchedEffect(cloud.isEnabled, state.cloudLibrarySettingsVersion) { vm.ensureCloudLibraryLoaded() }
    fun internal(info: CloudLibraryPlaybackInfo) {
        val file = info.filename ?: info.file.name
        play(Screen.Player.createRoute(streamUrl = info.url, title = file, streamName = file, contentType = "cloud",
            contentName = info.item.name, videoId = "${info.item.stableKey}:${info.file.stableKey}", season = 1,
            episode = info.sequenceIndex + 1, episodeTitle = file, filename = file, videoSize = info.videoSizeBytes,
            addonName = info.item.providerName, streamDescription = info.item.name, cloudSessionToken = info.sessionToken))
    }
    fun resolve(item: CloudLibraryItem, file: CloudLibraryFile) {
        vm.resolveCloudPlayback(item, file) { info ->
            picker = null
            scope.launch {
                when (vm.getPlayerPreference()) {
                    PlayerPreference.INTERNAL -> internal(info)
                    PlayerPreference.EXTERNAL -> vm.launchCloudPlaybackExternally(info, context)
                    PlayerPreference.ASK_EVERY_TIME -> choice = info
                }
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        item { BrowseHeading("Cloud files", back) }
        item { MobileTextInput("Search files", state.cloudSearchQuery, vm::onCloudSearchQueryChange) }
        item { LazyRow {
            item { Action("All providers", selected = state.selectedCloudProviderId == null) { vm.onSelectCloudProvider(null) } }
            items(cloud.providers) { provider -> Action(provider.providerName, selected = state.selectedCloudProviderId == provider.providerId) { vm.onSelectCloudProvider(provider.providerId) } }
        } }
        item { LazyRow { items(listOf<CloudLibraryItemType?>(null) + CloudLibraryItemType.entries) { type ->
            Action(type?.name ?: "All files", selected = state.selectedCloudType == type) { vm.onSelectCloudType(type) }
        } } }
        item { Action(if (cloud.isRefreshing) "Refreshing…" else "Refresh", onClick = vm::refreshCloudLibrary) }
        when {
            !cloud.isEnabled -> item { Label("Enable the cloud library in debrid settings.") }
            cloud.isLoaded && !cloud.hasConnectedProvider -> item { Label("Connect a provider in Settings → Debrid providers.") }
            cloud.isLoaded && state.visibleCloudItems.isEmpty() -> item { Label("No matching files.") }
        }
        cloud.providers.forEach { p -> p.errorMessage?.let { item { Label(p.providerName + " · " + it) } } }
        state.errorMessage?.let { item { Label(it) } }
        state.transientMessage?.let { item { Label(it) } }
        items(state.visibleCloudItems, key = { it.stableKey }) { item ->
            Action(item.name + " · " + item.providerName, Modifier.fillMaxWidth()) {
                when (item.playableFiles.size) {
                    0 -> vm.onCloudItemHasNoPlayableFiles()
                    1 -> resolve(item, item.playableFiles.first())
                    else -> picker = item
                }
            }
        }
    }
    picker?.let { item -> TouchSheet({ picker = null }) {
        LazyColumn {
            item { SectionTitle(item.name) }
            items(item.playableFiles) { file -> Action(file.name) { if (state.resolvingCloudFileKey == null) resolve(item, file) } }
        }
    } }
    choice?.let { info -> TouchSheet({ choice = null }) {
        Action("ReSeq player") { choice = null; internal(info) }
        Action("External player") { choice = null; scope.launch { vm.launchCloudPlaybackExternally(info, context) } }
    } }
}
