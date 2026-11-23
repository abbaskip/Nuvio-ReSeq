package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.*
import com.nuvio.tv.data.mediaserver.*
import com.nuvio.tv.data.mediaserver.plex.PlexAccountClient
import com.nuvio.tv.data.mediaserver.plex.PlexProvider
import com.nuvio.tv.data.mediaserver.plex.PlexReach
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import com.nuvio.tv.ui.screens.library.LibraryServersViewModel
import com.nuvio.tv.ui.screens.settings.*

@Composable
internal fun MobileServerShelves(open: OpenMobileTitle, back: () -> Unit, vm: LibraryServersViewModel = hiltViewModel()) {
    val state by vm.servers.collectAsStateWithLifecycle()
    val shelves by vm.shelves.collectAsStateWithLifecycle()
    LaunchedEffect(state.revision) { vm.load() }
    LazyColumn(contentPadding = PaddingValues(10.dp)) {
        item { BrowseHeading("Server libraries", back) }
        if (shelves == null) item { Label("Loading libraries…") }
        else if (shelves.isNullOrEmpty()) item { Label("Connect a media server and select a library in Settings.") }
        items(shelves.orEmpty(), key = { it.key }) { shelf ->
            val row = shelf.row
            if (row != null) TitleRail(shelf.title, row.items, open)
            else shelf.failure?.let { Label(shelf.title + " · " + stringResource(it.messageRes())) }
        }
    }
}

@Composable
internal fun MobileMediaServers(vm: MediaServersViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<ServerFailure?>(null) }
    state.connections.forEach { server ->
        SectionTitle(server.name)
        Label(server.userName)
        Action("Enabled", selected = server.enabled) { vm.setEnabled(server.id, !server.enabled) }
        Action("Use server metadata", selected = server.useCatalogMetadata) { vm.setCatalogMetadata(server.id, !server.useCatalogMetadata) }
        Action("Import watch state", selected = server.importWatchState) { vm.setImportWatchState(server.id, !server.importWatchState) }
        server.libraries.forEach { library -> Action(library.name, selected = library.selected) { vm.setLibrarySelected(server.id, library.id, !library.selected) } }
        Action(if (refreshing == server.id) "Refreshing…" else "Refresh libraries") { vm.refreshLibraries(server.id) { failure = it } }
        Action(if (removing == server.id) "Confirm disconnect" else "Disconnect") {
            if (removing == server.id) { vm.remove(server.id); removing = null } else removing = server.id
        }
        state.failures[server.id]?.let { Label(stringResource(it.messageRes())) }
    }
    vm.providers.forEach { provider -> Action("Connect " + provider.displayName) { vm.resetSignIn(); adding = provider.id } }
    failure?.let { Label(stringResource(it.messageRes())) }
    vm.providers.find { it.id == adding }?.let { provider -> ServerSignIn(provider, vm) { adding = null } }
}

@Composable
private fun ServerSignIn(provider: ServerProvider, vm: MediaServersViewModel, close: () -> Unit) {
    val latest by rememberUpdatedState(close)
    val uri = LocalUriHandler.current
    var address by rememberSaveable(provider.id) { mutableStateOf("") }
    var user by remember(provider.id) { mutableStateOf("") }
    var secret by remember(provider.id) { mutableStateOf("") }
    @Composable fun password(title: String) {
        OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Label(title) }, singleLine = true,
            shape = RectangleShape, visualTransformation = PasswordVisualTransformation())
    }
    TouchSheet(close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(12.dp)) {
            BrowseHeading(provider.displayName, close)
            when (provider.id) {
                PlexProvider.ID -> {
                    val flow = vm.plexSignIn
                    val s by flow.state.collectAsStateWithLifecycle()
                    DisposableEffect(flow) { flow.start { latest() }; onDispose { flow.close() } }
                    LifecycleStartEffect(flow) { flow.resume(); onStopOrDispose { flow.pause() } }
                    when (s.step) {
                        PlexSignInStep.CODE -> { s.pin?.let { pin ->
                            Label(pin.code, strong = true)
                            Action("Open Plex sign-in") { uri.openUri("https://${PlexAccountClient.LINK_PAGE}?pin=${pin.code}") }
                        }; Action("New code", onClick = flow::newCode) }
                        PlexSignInStep.SERVERS -> s.servers.forEach { server -> Action(server.name) {
                            if (!s.busy && !s.serversLoading && server.reach != PlexReach.OFFLINE) flow.pickServer(server)
                        } }
                        PlexSignInStep.USERS -> s.users.forEach { member -> Action(member.name) { flow.pickUser(member) } }
                        PlexSignInStep.PIN -> { password("Profile PIN"); Action("Unlock") { flow.submitPin(secret); secret = "" } }
                        PlexSignInStep.SIGNING_IN -> Label("Signing in…")
                        PlexSignInStep.FAILED -> Action("Retry", onClick = flow::newCode)
                    }
                    if (s.pinWrong) Label("Incorrect PIN")
                    s.error?.let { Label(stringResource(it.messageRes())) }
                }
                SiloProvider.ID -> {
                    val flow = vm.siloSignIn
                    val s by flow.state.collectAsStateWithLifecycle()
                    DisposableEffect(flow) { flow.start { latest() }; onDispose { flow.close() } }
                    when (s.step) {
                        SiloSignInStep.ADDRESS -> { MobileTextInput("Server address", address) { address = it }; Action("Connect") { flow.find(address.trim()) } }
                        SiloSignInStep.CODE -> {
                            s.code?.let { Label(it.shownCode, strong = true); Action("Open sign-in page") { uri.openUri(it.linkWithCode) } }
                            Action("New code", onClick = flow::newCode)
                            Action("Use password", onClick = flow::usePassword)
                        }
                        SiloSignInStep.PASSWORD -> {
                            MobileTextInput("Username", user) { user = it }; password("Password")
                            Action("Sign in") { flow.signInWithPassword(user, secret); secret = "" }
                            Action("Use code", onClick = flow::useCode)
                        }
                        SiloSignInStep.PROFILES -> s.profiles.forEach { profile -> Action(profile.name) { flow.pickProfile(profile) } }
                        SiloSignInStep.PIN -> { password("Profile PIN"); Action("Unlock") { flow.submitPin(secret); secret = "" } }
                        SiloSignInStep.FAILED -> Action("Change server", onClick = flow::backToAddress)
                        else -> Label("Connecting…")
                    }
                    if (s.pinWrong) Label("Incorrect PIN")
                    s.error?.let { Label(stringResource(it.messageRes())) }
                }
                else -> {
                    val s by vm.signIn.collectAsStateWithLifecycle()
                    MobileTextInput("Server address", address) { address = it }
                    MobileTextInput("Username", user) { user = it }; password("Password")
                    Action(if (s.connecting) "Connecting…" else "Sign in") { vm.connect(provider, address.trim(), user, secret) { latest() } }
                    s.error?.let { Label(stringResource(it.messageRes())) }
                }
            }
        }
    }
}
