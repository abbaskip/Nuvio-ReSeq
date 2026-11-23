package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.ui.LiveTvSettingsViewModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.ui.text.input.PasswordVisualTransformation

private data class SourceRemoval(val name: String, val remove: () -> Unit)

@Composable
internal fun MobileSetup(driveOnly: Boolean = false, vm: LiveTvSettingsViewModel = hiltViewModel()) {
    val lists by vm.playlists.collectAsStateWithLifecycle()
    val guides by vm.epgSources.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val drive = vm.driveSync
    val backup by drive.state.collectAsStateWithLifecycle()
    var add by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var keyTransfer by remember { mutableStateOf<Boolean?>(null) }
    var playbackRestore by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val keyVm: com.nuvio.tv.livetv.sync.ProviderKeyTransferViewModel = hiltViewModel()
    val keyState by keyVm.state.collectAsStateWithLifecycle()
    var removal by remember { mutableStateOf<SourceRemoval?>(null) }
    var playlist by remember { mutableStateOf<com.nuvio.tv.livetv.model.PlaylistSource?>(null) }
    var epg by remember { mutableStateOf<com.nuvio.tv.livetv.model.EpgSource?>(null) }
    var options by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!driveOnly) {
        item { SectionTitle("Playlists") }
        itemsIndexed(lists, key = { _, s -> "playlist-" + s.id }) { index, source ->
            SourceRow(source.name, source.enabled, remove = {
                removal = SourceRemoval(source.name) { vm.removePlaylist(source.id) }
            }) { vm.setPlaylistEnabled(source.id, it) }
            LazyRow {
                item { Action("Edit playlist") { playlist = source } }
                item { Action("Update now") { vm.updatePlaylistNow(source) } }
                if (index > 0) item { Action("Move up") { vm.movePlaylist(source.id, -1) } }
                if (index < lists.lastIndex) item { Action("Move down") { vm.movePlaylist(source.id, 1) } }
            }
        }
        item { Action("Add M3U playlist") { add = "M3U playlist" } }
        item { Action("Add Xtream account") { add = "Xtream account" } }
        item { Action("Add Stalker portal") { add = "Stalker portal" } }
        item { SectionTitle("Programme guides") }
        items(guides, key = { "guide-" + it.id }) { source ->
            SourceRow(source.name, source.enabled, remove = {
                removal = SourceRemoval(source.name) { vm.removeEpg(source.id) }
            }) { vm.setEpgEnabled(source.id, it) }
            Action("Edit guide") { epg = source }
        }
        item { Action("Add XMLTV guide") { add = "XMLTV guide" } }
        if (lists.isNotEmpty() || guides.isNotEmpty()) item { Action("Refresh sources") { vm.refreshAll() } }
        item { Action("Guide and recordings") { options = true } }
        (status.error ?: status.message)?.let { item { Label(it) } }
        } else {
        item { Label("Connect the same Google account as your other device. Choose Restore for a standard backup, including IPTV and TVDB. Your standard backup opens without a passphrase.", Modifier.padding(8.dp)) }
        if (!backup.available) item { Label("Google Drive sign-in is unavailable in this build.") }
        else if (!backup.connected) item { Action("Connect Google Drive", onClick = drive::beginSignIn) }
        else {
            backup.email?.let { item { Label(it) } }
            item { SectionTitle("Device backup") }
            item { TogglePreference("Automatic sync", backup.autoSync, drive::setAutoSync) }
            if (!backup.busy) items(listOf("Back up", "Restore", "Disconnect")) { action ->
                Action(action) { confirmation = action }
            }
            item { SectionTitle("TV Mix and Shuffle") }
            item { SettingsLink("Restore TV Mix and Shuffle") { playbackRestore = true } }
            item { SectionTitle("Provider API keys"); Label("Transfer this profile’s TMDB, Gemini, debrid, MDBList and AnimeSkip keys in a separate passphrase-encrypted backup.", small = true) }
            if (!keyState.busy) item { Row {
                Action("Back up keys") { keyTransfer = true }; Action("Restore keys") { keyTransfer = false }
            } } else item { LinearProgressIndicator() }
            keyState.message?.let { item { Label(it) } }
        }
        backup.message?.let { item { Label(it, small = true) } }
        if (backup.lastSyncMs > 0) item { Label("Last sync · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.lastSyncMs)), small = true) }
        }
        if (!driveOnly) {
        item { SectionTitle("Credits") }
        item { Label("Mobile layout and touch adaptations from homelessbrian/NuvioPlusIPTV-Mobile, based on Nuvio Mobile and ghbarker’s phone port. Licensed under GNU GPL v3.", small = true) }
        item { Action("Mobile source") { uri.openUri("https://github.com/homelessbrian/NuvioPlusIPTV-Mobile/tree/cbd3d8575217d85ff251d831b6cf72b90c1e4b4d") } }
        }
    }
    add?.let { kind ->
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var mac by remember { mutableStateOf("") }
        var serial by remember { mutableStateOf("") }
        var live by remember { mutableStateOf(true) }
        var vod by remember { mutableStateOf(true) }
        var invalid by remember { mutableStateOf(false) }
        var sourceError by remember { mutableStateOf<String?>(null) }
        TouchSheet({ add = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(kind)
                OutlinedTextField(name, { name = it }, label = { Label("Name") }, singleLine = true, shape = RectangleShape)
                OutlinedTextField(address, { address = it; invalid = false }, label = { Label("URL") }, singleLine = true,
                    isError = invalid, shape = RectangleShape)
                if (invalid) Label("Enter an HTTP or HTTPS URL.")
                sourceError?.let { Label(it) }
                if (kind == "Stalker portal") {
                    MobileTextInput("MAC address", mac) { mac = it }
                    MobileTextInput("Serial number (optional)", serial) { serial = it }
                }
                if (kind == "Xtream account" || kind == "Stalker portal") {
                    OutlinedTextField(username, { username = it }, label = { Label("Username") }, singleLine = true, shape = RectangleShape)
                    OutlinedTextField(password, { password = it }, label = { Label("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), shape = RectangleShape)
                    SourceRow("Import live channels", live) { live = it }
                    SourceRow("Import movies and series", vod) { vod = it }
                }
                Action("Add") {
                    val url = address.trim().toHttpUrlOrNull()
                    if (url == null) invalid = true
                    else {
                        val label = name.trim().ifBlank { url.host }
                        if (kind in setOf("Xtream account", "Stalker portal") && !live && !vod) {
                            sourceError = "Select live channels, movies and series, or both."
                            return@Action
                        }
                        when (kind) {
                            "M3U playlist" -> vm.addPlaylist(label, address.trim(), "")
                            "Xtream account" -> {
                                val login = com.nuvio.tv.livetv.model.PlaylistSource.credentialsFromLink(address.trim())
                                if (username.ifBlank { login?.first.orEmpty() }.isBlank() || password.ifBlank { login?.second.orEmpty() }.isBlank()) {
                                    sourceError = "Enter a username and password or paste the provider’s full playlist link."
                                    return@Action
                                }
                                vm.addXtream(label, address.trim(), username.trim(), password, importVod = vod, importLive = live)
                            }
                            "Stalker portal" -> {
                                if (mac.isBlank()) { sourceError = "Enter the MAC address for this portal."; return@Action }
                                if (runCatching { com.nuvio.tv.livetv.data.StalkerPortalClient.endpoint(address.trim()) }.isFailure) {
                                    sourceError = "Enter the portal address without a login, query or fragment."
                                    return@Action
                                }
                                vm.addStalker(label, address.trim(), mac.trim(), username.trim(), password, serial.trim(), vod, live)
                            }
                            else -> vm.addEpg(label, address.trim())
                        }
                        add = null
                    }
                }
            }
        }
    }
    playlist?.let { MobilePlaylistEditor(it, vm) { playlist = null } }
    if (options) MobileGuideSettings(vm) { options = false }
    epg?.let { guide ->
        var name by remember(guide.id) { mutableStateOf(guide.name) }
        var url by remember(guide.id) { mutableStateOf(guide.url) }
        var invalid by remember { mutableStateOf(false) }
        TouchSheet({ epg = null }) { Column(Modifier.imePadding().padding(12.dp)) {
            SectionTitle("Edit guide")
            MobileTextInput("Name", name) { name = it }; MobileTextInput("XMLTV address", url) { url = it }
            if (invalid) Label("Enter an HTTP or HTTPS address.")
            Action("Save guide") {
                if (url.trim().toHttpUrlOrNull() == null) invalid = true
                else { vm.saveEpg(guide.copy(name = name.trim(), url = url.trim())); epg = null }
            }
        } }
    }
    removal?.let { source ->
        AlertDialog(onDismissRequest = { removal = null }, shape = RectangleShape, title = { Label("Remove " + source.name + "?") },
            text = { Label("Remove this source and its imported data from this device.") },
            confirmButton = { Action("Remove") { source.remove(); removal = null } },
            dismissButton = { Action("Cancel") { removal = null } })
    }
    backup.signIn?.let { prompt ->
        TouchSheet(drive::cancelSignIn) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Connect Google Drive")
                Label("Enter code " + prompt.userCode + " on Google’s sign-in page.")
                Action("Open sign-in page") { uri.openUri(prompt.qrUrl) }
                Action("Cancel", onClick = drive::cancelSignIn)
            }
        }
    }
    confirmation?.let { action ->
        val remote by produceState<com.nuvio.tv.livetv.sync.LiveTvDriveSync.BackupInfo?>(null, action) {
            if (action == "Restore") value = drive.backupInfo()
        }
        AlertDialog(onDismissRequest = { confirmation = null }, shape = RectangleShape,
            title = { Label("$action?", strong = true) }, text = {
                Label(when (action) {
                    "Restore" -> listOfNotNull(remote?.let { "Saved by " + it.device + " on " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.savedAtMs)) },
                        "Restore IPTV playlists and logins, guides, Sports settings, TVDB keys and orders, Shuffle choices and mixes for matching profiles.").joinToString("\n\n")
                    "Back up" -> "Save this device’s ReSeq configuration to Google Drive."
                    else -> "Stop syncing this device with Google Drive."
                })
            }, confirmButton = { Action(action) {
                when (action) { "Restore" -> drive.restoreOnMobile(); "Back up" -> drive.backUpNow(); else -> drive.disconnect() }
                confirmation = null
            } }, dismissButton = { Action("Cancel") { confirmation = null } })
    }
    if (playbackRestore && backup.connected) MobilePlaybackRestore(drive) { playbackRestore = false }
    keyTransfer?.let { upload ->
        var phrase by remember { mutableStateOf("") }
        var repeat by remember { mutableStateOf("") }
        TouchSheet({ keyTransfer = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp)) {
                SectionTitle(if (upload) "Back up provider keys" else "Restore provider keys")
                Label(if (upload) "Choose a passphrase of at least 10 characters and keep it to unlock your keys on other devices."
                    else "Enter the passphrase you chose in Back up keys. If you used the regular Back up action on TV, close this and choose Restore instead.", small = true)
                OutlinedTextField(phrase, { phrase = it }, singleLine = true, label = { Label("Backup passphrase") }, visualTransformation = PasswordVisualTransformation())
                if (upload) OutlinedTextField(repeat, { repeat = it }, singleLine = true,
                    label = { Label("Confirm passphrase") }, visualTransformation = PasswordVisualTransformation())
                Button(enabled = if (upload) phrase.length >= 10 && phrase == repeat else phrase.isNotEmpty(),
                    onClick = { keyVm.transfer(upload, phrase); keyTransfer = null }, shape = RectangleShape) {
                    Text(if (upload) "Back up keys" else "Restore keys")
                }
            }
        }
    }

}

@Composable
private fun SourceRow(name: String, enabled: Boolean, remove: (() -> Unit)? = null, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).toggleable(enabled, role = androidx.compose.ui.semantics.Role.Switch,
            onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
            Label(name, Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
        if (remove != null) Action("Remove", onClick = remove)
    }
}
