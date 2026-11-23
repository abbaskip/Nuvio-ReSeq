package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.*
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.livetv.ui.LiveTvSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun MobilePlaylistEditor(source: PlaylistSource, vm: LiveTvSettingsViewModel, close: () -> Unit) {
    var draft by remember(source.id) { mutableStateOf(source) }
    var error by remember { mutableStateOf("") }
    val address = when { source.isStalker -> draft.stalkerPortal; source.isXtream -> draft.xtreamServer; else -> draft.url }
    TouchSheet(close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), modifier = Modifier.imePadding()) {
            item { BrowseHeading("Edit playlist", close) }
            item { MobileTextInput("Name", draft.name) { draft = draft.copy(name = it) } }
            item { MobileTextInput("Address", address) {
                draft = when { source.isStalker -> draft.copy(stalkerPortal = it); source.isXtream -> draft.copy(xtreamServer = it); else -> draft.copy(url = it) }
            } }
            if (source.isStalker) item {
                MobileTextInput("MAC address", draft.stalkerMac) { draft = draft.copy(stalkerMac = it) }
                MobileTextInput("Serial number", draft.stalkerSerial) { draft = draft.copy(stalkerSerial = it) }
            }
            if (source.isStalker || source.isXtream) item {
                MobileTextInput("Username", if (source.isStalker) draft.stalkerUsername else draft.xtreamUsername) {
                    draft = if (source.isStalker) draft.copy(stalkerUsername = it) else draft.copy(xtreamUsername = it)
                }
                OutlinedTextField(if (source.isStalker) draft.stalkerPassword else draft.xtreamPassword, {
                    draft = if (source.isStalker) draft.copy(stalkerPassword = it) else draft.copy(xtreamPassword = it)
                }, label = { Label("Password") }, singleLine = true, shape = RectangleShape, visualTransformation = PasswordVisualTransformation())
                Action("Import live channels", selected = draft.importLive) { draft = draft.copy(importLive = !draft.importLive) }
            }
            item { Action("Import on demand", selected = draft.importVod) { draft = draft.copy(importVod = !draft.importVod) } }
            if (draft.importVod) item {
                Action("Movies", selected = draft.vodMovies) { draft = draft.copy(vodMovies = !draft.vodMovies) }
                Action("Series", selected = draft.vodSeries) { draft = draft.copy(vodSeries = !draft.vodSeries) }
                Action("Only cache artwork for browsed titles", selected = draft.vodSourceOnly) { draft = draft.copy(vodSourceOnly = !draft.vodSourceOnly) }
            }
            item { MobileTextInput("User agent", draft.userAgent) { draft = draft.copy(userAgent = it) } }
            item { Action("Use guide from playlist", selected = draft.useEmbeddedEpg) { draft = draft.copy(useEmbeddedEpg = !draft.useEmbeddedEpg) } }
            if (source.isXtream) item { LazyRow { items(listOf("auto", "ts", "m3u8")) { format ->
                Action(format, selected = draft.streamFormat == format) { draft = draft.copy(streamFormat = format) }
            } } }
            item { Action("Save changes") {
                if (address.trim().toHttpUrlOrNull() == null) error = "Enter an HTTP or HTTPS address."
                else if (!draft.importLive && !draft.importVod && !source.isM3u) error = "Select live channels or on demand."
                else { vm.savePlaylist(draft); close() }
            }; if (error.isNotEmpty()) Label(error) }
        }
    }
}

@Composable
internal fun MobileGuideSettings(vm: LiveTvSettingsViewModel, close: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val user by vm.userState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val drives by produceState(emptyList<RecordingStorage.Choice>()) { value = withContext(Dispatchers.IO) { RecordingStorage.choices(context) } }
    val storage by vm.storage.collectAsStateWithLifecycle()
    val busy by vm.storageBusy.collectAsStateWithLifecycle()
    val message by vm.storageMessage.collectAsStateWithLifecycle()
    TouchSheet(close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { BrowseHeading("Guide and recordings", close) }
            item { Action("24-hour clock", selected = s.use24HourClock) { vm.update { it.copy(use24HourClock = !it.use24HourClock) } } }
            item { Action("Download guide descriptions", selected = s.epgDescriptions) { vm.updateAndReloadEpg { it.copy(epgDescriptions = !it.epgDescriptions) } } }
            item { ChoiceRow("Past guide (hours)", s.epgPastHours, listOf(0, 12, 24, 48, 72, 168)) { v -> vm.updateAndReloadEpg { it.copy(epgPastHours = v) } } }
            item { ChoiceRow("Future guide (days)", s.epgFutureDays, listOf(1, 2, 3, 5, 7, 14)) { v -> vm.updateAndReloadEpg { it.copy(epgFutureDays = v) } } }
            item { ChoiceRow("Guide refresh (hours)", s.epgRefreshHours, listOf(3, 6, 12, 24)) { v -> vm.update { it.copy(epgRefreshHours = v) } } }
            item { ChoiceRow("Playlist refresh (hours)", s.playlistRefreshHours, listOf(3, 6, 12, 24, 48)) { v -> vm.update { it.copy(playlistRefreshHours = v) } } }
            item { Action("Automatic reconnect", selected = s.autoReconnect) { vm.update { it.copy(autoReconnect = !it.autoReconnect) } } }
            item { Action("Try backup channel", selected = s.autoBackupChannel) { vm.update { it.copy(autoBackupChannel = !it.autoBackupChannel) } } }
            item { Action("Cache catch-up", selected = s.catchupCache) { vm.update { it.copy(catchupCache = !it.catchupCache) } } }
            item { Action("Timeshift", selected = s.timeshiftEnabled) { vm.update { it.copy(timeshiftEnabled = !it.timeshiftEnabled) } } }
            if (s.timeshiftEnabled) item { ChoiceRow("Timeshift (minutes)", s.timeshiftMinutes, listOf(5, 10, 15, 30, 60)) { v -> vm.update { it.copy(timeshiftMinutes = v) } } }
            item { SectionTitle("Recordings") }
            items(drives) { drive -> Action(drive.label + " · " + android.text.format.Formatter.formatShortFileSize(context, drive.freeBytes) + " free", selected = s.recordingStorage == drive.id) {
                vm.update { it.copy(recordingStorage = drive.id) }
            } }
            item { ChoiceRow("Start early (minutes)", s.recordPreMinutes, listOf(0, 2, 5, 10, 15, 30)) { v -> vm.update { it.copy(recordPreMinutes = v) } } }
            item { ChoiceRow("Finish late (minutes)", s.recordPostMinutes, listOf(0, 2, 5, 10, 15, 30, 60)) { v -> vm.update { it.copy(recordPostMinutes = v) } } }
            items(user.hiddenChannels.toList()) { id -> Action("Unhide channel · " + vm.channelLabel(id)) { vm.unhideChannel(id) } }
            items(user.hiddenGroups.toList()) { id -> Action("Unhide group · " + vm.groupLabel(id)) { vm.unhideGroup(id) } }
            item { SectionTitle("IPTV storage"); Action(if (busy) "Measuring…" else "Measure storage") { if (!busy) vm.measureStorage() } }
            storage?.let { item { Label(it.readable()) } }
            message?.let { item { Label(it) } }
        }
    }
}

@Composable
internal fun ChoiceRow(label: String, value: Int, options: List<Int>, select: (Int) -> Unit) {
    Label(label)
    LazyRow { items((options + value).distinct().sorted()) { option -> Action(option.toString(), selected = option == value) { select(option) } } }
}
