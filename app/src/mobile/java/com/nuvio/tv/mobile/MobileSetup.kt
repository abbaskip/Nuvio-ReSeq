package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
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
internal fun MobileSetup(vm: LiveTvSettingsViewModel = hiltViewModel()) {
    val lists by vm.playlists.collectAsStateWithLifecycle()
    val guides by vm.epgSources.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val drive = vm.driveSync
    val backup by drive.state.collectAsStateWithLifecycle()
    var add by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<SourceRemoval?>(null) }
    val uri = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SectionTitle("Playlists and guides") }
        items(lists, key = { "playlist-" + it.id }) { source ->
            SourceRow(source.name, source.enabled, remove = {
                removal = SourceRemoval(source.name) { vm.removePlaylist(source.id) }
            }) { vm.setPlaylistEnabled(source.id, it) }
        }
        item { Action("Add M3U playlist") { add = "M3U playlist" } }
        item { Action("Add Xtream account") { add = "Xtream account" } }
        items(guides, key = { "guide-" + it.id }) { source ->
            SourceRow(source.name, source.enabled, remove = {
                removal = SourceRemoval(source.name) { vm.removeEpg(source.id) }
            }) { vm.setEpgEnabled(source.id, it) }
        }
        item { Action("Add XMLTV guide") { add = "XMLTV guide" } }
        if (lists.isNotEmpty() || guides.isNotEmpty()) item { Action("Refresh sources") { vm.refreshAll() } }
        (status.error ?: status.message)?.let { item { Label(it) } }
        item { SectionTitle("Drive backup") }
        if (!backup.available) item { Label("Google Drive sign-in is unavailable in this build.") }
        else if (!backup.connected) item { Action("Connect Google Drive") { drive.beginSignIn() } }
        else {
            backup.email?.let { item { Label(it) } }
            item { SourceRow("Automatic sync", backup.autoSync, change = drive::setAutoSync) }
            if (!backup.busy) items(listOf("Back up", "Restore", "Disconnect")) { action ->
                Action(action) { confirmation = action }
            }
        }
        backup.message?.let { item { Label(it, small = true) } }
        item { SectionTitle("Credits") }
        item { Label("Mobile layout and touch adaptations from homelessbrian/NuvioPlusIPTV-Mobile, based on Nuvio Mobile and ghbarker’s phone port. Licensed under GNU GPL v3.", small = true) }
        item { Action("Mobile source") { uri.openUri("https://github.com/homelessbrian/NuvioPlusIPTV-Mobile/tree/cbd3d8575217d85ff251d831b6cf72b90c1e4b4d") } }
    }
    add?.let { kind ->
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        TouchSheet({ add = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(kind)
                OutlinedTextField(name, { name = it }, label = { Label("Name") }, singleLine = true, shape = RectangleShape)
                OutlinedTextField(address, { address = it; invalid = false }, label = { Label("URL") }, singleLine = true,
                    isError = invalid, shape = RectangleShape)
                if (invalid) Label("Enter an HTTP or HTTPS URL.")
                if (kind == "Xtream account") {
                    OutlinedTextField(username, { username = it }, label = { Label("Username") }, singleLine = true, shape = RectangleShape)
                    OutlinedTextField(password, { password = it }, label = { Label("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), shape = RectangleShape)
                }
                Action("Add") {
                    val url = address.trim().toHttpUrlOrNull()
                    if (url == null) invalid = true
                    else {
                        val label = name.trim().ifBlank { url.host }
                        when (kind) {
                            "M3U playlist" -> vm.addPlaylist(label, address.trim(), "")
                            "Xtream account" -> {
                                if (username.isBlank() || password.isEmpty()) return@Action
                                vm.addXtream(label, address.trim(), username.trim(), password, importVod = false)
                            }
                            else -> vm.addEpg(label, address.trim())
                        }
                        add = null
                    }
                }
            }
        }
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
                Action("Open sign-in page") { uri.openUri(prompt.url) }
                Action("Cancel", onClick = drive::cancelSignIn)
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null }, shape = RectangleShape,
            title = { Label("$action?", strong = true) }, text = {
                Label(when (action) {
                    "Restore" -> "Replace this device’s IPTV, Sports, TVDB episode orders and keys, Shuffle choices and mixes with the saved configuration for matching profiles."
                    "Back up" -> "Save this device’s ReSeq configuration to Google Drive."
                    else -> "Stop syncing this device with Google Drive."
                })
            }, confirmButton = { Action(action) {
                when (action) { "Restore" -> drive.restoreNow(); "Back up" -> drive.backUpNow(); else -> drive.disconnect() }
                confirmation = null
            } }, dismissButton = { Action("Cancel") { confirmation = null } })
    }
}

@Composable
private fun SourceRow(name: String, enabled: Boolean, remove: (() -> Unit)? = null, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(name, Modifier.weight(1f))
        if (remove != null) Action("Remove", onClick = remove)
        Switch(checked = enabled, onCheckedChange = change)
    }
}
