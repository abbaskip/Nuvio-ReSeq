package com.nuvio.tv.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.screens.account.AccountViewModel
import com.nuvio.tv.ui.screens.plugin.*
import com.nuvio.tv.ui.screens.profile.ProfileSelectionViewModel
import com.nuvio.tv.ui.screens.settings.*
import com.nuvio.tv.data.local.*
import com.nuvio.tv.data.simkl.SimklConnectionMode
import kotlinx.coroutines.launch

private enum class SettingsPage(val title: String) {
    ACCOUNT("Nuvio account"), PROFILES("Profiles"), ADDONS("Catalog sources"), PLUGINS("Extensions"),
    PLAYBACK("Playback and source selection"), TRACKING("Tracking and TVDB"), DRIVE("Google Drive backup and restore"),
    DEBRID("Debrid providers"), THEME("Appearance"), IPTV("IPTV sources"), TMDB("TMDB metadata"), SERVERS("Media servers"), ABOUT("About and credits")
}

internal val LocalSelectedProfile = staticCompositionLocalOf<(Int) -> Unit> { {} }

@Composable
internal fun ProfileUnlock(profileId: Int, unlocked: () -> Unit, vm: ProfileSelectionViewModel = hiltViewModel()) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
        SectionTitle("Unlock profile")
        Field("Profile PIN", pin, true) { pin = it }
        Action("Unlock") { vm.verifyProfilePin(profileId, pin) { result ->
            if (result.getOrNull()?.unlocked == true) unlocked() else message = "Profile could not be unlocked."
        } }
        if (message.isNotBlank()) Label(message)
        val profiles by vm.profiles.collectAsStateWithLifecycle()
        profiles.filter { it.id != profileId }.forEach { other ->
            Action("Switch to " + other.name) { vm.selectProfile(other.id, {}, { message = "Could not switch profile." }) }
        }
    }
}

@Composable
internal fun MobileSettings(openIptv: Boolean, consumed: () -> Unit, catalog: MobileCatalogViewModel = hiltViewModel()) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    LaunchedEffect(openIptv) { if (openIptv) { page = SettingsPage.IPTV; consumed() } }
    BackHandler(page != null) { page = null }
    Column(Modifier.fillMaxSize()) {
        if (page == null || page == SettingsPage.ADDONS) {
            SectionTitle("Settings")
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsPage.entries.forEach { option -> Action(option.title, Modifier.fillMaxWidth()) { page = option } }
            }
            if (page == SettingsPage.ADDONS) CatalogSources { page = null }
        } else {
            Row { Action("Back") { page = null }; SectionTitle(page!!.title) }
            when (page) {
                SettingsPage.IPTV -> MobileSetup()
                SettingsPage.DRIVE -> MobileSetup(driveOnly = true)
                SettingsPage.ABOUT -> MobileAbout()
                else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (page) {
                        SettingsPage.ACCOUNT -> AccountSettings()
                        SettingsPage.PROFILES -> ProfilesSettings()
                        SettingsPage.PLUGINS -> ExtensionSettings()
                        SettingsPage.PLAYBACK -> PlaybackSettings()
                        SettingsPage.TRACKING -> TrackerSettings()
                        SettingsPage.DEBRID -> DebridSettings(catalog.debrid)
                        SettingsPage.THEME -> AppearanceSettings(catalog.themes)
                        SettingsPage.TMDB -> MobileTmdbSettings()
                        SettingsPage.SERVERS -> MobileMediaServers()
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, update: (String) -> Unit) {
    OutlinedTextField(value, update, Modifier.fillMaxWidth(), label = { Label(label) }, shape = RectangleShape,
        singleLine = true, visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}

@Composable
private fun AccountSettings(vm: AccountViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val signed = state.authState as? AuthState.FullAccount
    if (signed != null) {
        Label(signed.email)
        Action("Sign out") { vm.signOut() }
    } else if (vm.usesEmailPasswordLogin) {
        Field("Email", email) { email = it }
        Field("Password", password, true) { password = it }
        Row { Action("Sign in") { vm.signIn(email.trim(), password) }; Action("Create account") { vm.signUp(email.trim(), password) } }
    } else {
        if (state.qrLoginCode == null) Action("Sign in to Nuvio") { vm.startQrLogin() }
        state.qrLoginUserCode?.let { Label(it, strong = true) }
        (state.qrLoginUrl ?: state.qrLoginVerificationUri)?.let { url -> Action("Open Nuvio sign-in") { uri.openUri(url) } }
        state.qrLoginStatus?.let { Label(it) }
    }
    SectionTitle("Link devices")
    Field("Sync PIN", pin, true) { pin = it }
    if (signed != null) {
        Action("Generate sync code") { vm.generateSyncCode(pin) }
        state.generatedSyncCode?.let { Label(it) }
    }
    Field("Sync code from another device", code) { code = it }
    Action("Link this device") { vm.claimSyncCode(code.trim(), pin) }
    if (state.syncClaimSuccess) Label("Device linked")
    state.error?.let { Label(it) }
}

@Composable
private fun ProfilesSettings(vm: ProfileSelectionViewModel = hiltViewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.activeProfileId.collectAsStateWithLifecycle()
    val locks by vm.profilePinEnabled.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<Int?>(null) }
    var pin by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(vm) { vm.refreshProfilePinStates() }
    val selected = LocalSelectedProfile.current
    fun switch(id: Int, verified: Boolean = false) = vm.selectProfile(id, { selected(if (verified) id else -1); target = null; pin = ""; message = "" }, { message = "Could not switch profile." })
    profiles.forEach { profile -> Action(profile.name, Modifier.fillMaxWidth(), profile.id == active) {
        if (profile.id != active) {
            if (locks[profile.id] == true || vm.isProfilePinEnabled(profile.id)) { target = profile.id; pin = "" } else switch(profile.id)
        }
    } }
    target?.let { id ->
        Field("Profile PIN", pin, true) { pin = it }
        Action("Unlock profile") { vm.verifyProfilePin(id, pin) { if (it.getOrNull()?.unlocked == true) switch(id, verified = true) else message = "Profile could not be unlocked." } }
    }
    if (vm.canAddProfile) {
        Field("New profile name", name) { name = it }
        Action("Create profile") { if (name.isNotBlank()) vm.createProfile(name.trim(), "#455A64") { name = "" } }
    }
    if (message.isNotBlank()) Label(message)
}

@Composable
private fun ExtensionSettings(vm: PluginViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    Field("Repository URL", url) { url = it }
    Action("Add repository") { if (!state.isAddingRepo && url.isNotBlank()) vm.onEvent(PluginUiEvent.AddRepository(url.trim())) }
    state.repositories.forEach { repo ->
        SectionTitle(repo.name)
        Action("Refresh repository") { vm.onEvent(PluginUiEvent.RefreshRepository(repo.id)) }
        state.scrapers.filter { it.repositoryId == repo.id }.forEach { scraper -> Row {
            Label(scraper.name, Modifier.weight(1f))
            Switch(scraper.enabled, { vm.onEvent(PluginUiEvent.ToggleScraper(scraper.id, it)) })
        } }
    }
    state.pendingScraperEnable?.let { pending -> AlertDialog(onDismissRequest = { vm.onEvent(PluginUiEvent.DismissPendingScraperEnable) },
        title = { Label("Enable " + pending.scraperName + "?") }, text = { Label("Enable this extension only if you trust its repository.") },
        confirmButton = { Action("Enable") { vm.onEvent(PluginUiEvent.ConfirmPendingScraperEnable) } },
        dismissButton = { Action("Cancel") { vm.onEvent(PluginUiEvent.DismissPendingScraperEnable) } }) }
    (state.errorMessage ?: state.successMessage)?.let { Label(it) }
}

@Composable
private fun PlaybackSettings(vm: PlaybackSettingsViewModel = hiltViewModel()) {
    val state by vm.playerSettings.collectAsStateWithLifecycle(PlayerSettings())
    val scope = rememberCoroutineScope()
    SectionTitle("Player")
    PlayerPreference.entries.forEach { option ->
        Action(when (option) {
            PlayerPreference.EXTERNAL -> "External player"; PlayerPreference.INTERNAL -> "ReSeq player"; PlayerPreference.ASK_EVERY_TIME -> "Ask each time"
        }, selected = option == state.playerPreference) { scope.launch { vm.setPlayerPreference(option) } }
    }
    InternalPlayerEngine.entries.forEach { engine ->
        Action(when (engine) { InternalPlayerEngine.MVP_PLAYER -> "MPV"; InternalPlayerEngine.EXOPLAYER -> "ExoPlayer"; else -> "Automatic" },
            selected = state.internalPlayerEngine == engine) { scope.launch { vm.setInternalPlayerEngine(engine) } }
    }
    SectionTitle("Source selection")
    StreamAutoPlayMode.entries.forEach { mode ->
        Action(when (mode) { StreamAutoPlayMode.MANUAL -> "Choose manually"; StreamAutoPlayMode.FIRST_STREAM -> "First matching stream"
            else -> "Match by expression" }, selected = state.streamAutoPlayMode == mode) { scope.launch { vm.setStreamAutoPlayMode(mode) } }
    }
    if (state.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
        var pattern by remember(state.streamAutoPlayRegex) { mutableStateOf(state.streamAutoPlayRegex) }
        Field("Source expression", pattern) { pattern = it }
        Action("Save expression") { scope.launch { vm.setStreamAutoPlayRegex(pattern) } }
    }
    val torrent by vm.torrentSettingsFlow.collectAsStateWithLifecycle(null)
    torrent?.let { config -> Row { Label("Peer-to-peer playback", Modifier.weight(1f)); Switch(config.p2pEnabled, vm::setP2pEnabled) } }
}

@Composable
private fun TrackerSettings(trakt: TraktViewModel = hiltViewModel(), simkl: SimklSettingsViewModel = hiltViewModel(),
    tvdb: TvdbSettingsViewModel = hiltViewModel(), tracking: TrackingSettingsViewModel = hiltViewModel()) {
    val t by trakt.uiState.collectAsStateWithLifecycle()
    val s by simkl.uiState.collectAsStateWithLifecycle()
    val db by tvdb.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val sources by tracking.uiState.collectAsStateWithLifecycle()
    if (sources.connectedProviderIds.isNotEmpty()) {
        SectionTitle("Watch progress")
        sources.availableWatchProgressSources.forEach { source -> Action(source.name.replace('_', ' '),
            selected = source == sources.watchProgressSource) { tracking.selectWatchProgressSource(source) } }
        SectionTitle("Library source")
        sources.availableLibrarySourceModes.forEach { source -> Action(source.name,
            selected = source == sources.librarySourceMode) { tracking.selectLibrarySourceMode(source) } }
        SectionTitle("Ratings")
        sources.availableRatingSources.forEach { source -> Action(source.name,
            selected = source == sources.ratingSource) { tracking.selectRatingSource(source) } }
    }
    SectionTitle("Trakt")
    if (t.mode == TraktConnectionMode.CONNECTED) { Label(t.username ?: "Connected"); Action("Sync Trakt", onClick = trakt::onSyncNow) }
    else { Action("Connect Trakt", onClick = trakt::onConnectClick); t.deviceUserCode?.let { Label(it) }
        t.verificationUrl?.let { Action("Open Trakt") { uri.openUri(it) } } }
    (t.errorMessage ?: t.statusMessage)?.let { Label(it) }
    SectionTitle("Simkl")
    if (s.mode == SimklConnectionMode.CONNECTED) { Label(s.username ?: "Connected"); Action("Sync Simkl", onClick = simkl::onSyncNow) }
    else { Action("Connect Simkl", onClick = simkl::onConnect); s.userCode?.let { Label(it) }
        s.verificationUri?.let { Action("Open Simkl") { uri.openUri(it) } } }
    (s.errorMessage ?: s.statusMessage)?.let { Label(it) }
    SectionTitle("TheTVDB")
    var key by remember { mutableStateOf("") }
    if (db.configured) Label("API key configured")
    Field("TVDB API key", key, true) { key = it }
    Row { Action("Save key") { if (key.isNotBlank()) { tvdb.save(key.trim()); key = "" } }; Action("Test connection", onClick = tvdb::test) }
    db.message?.let { Label(it) }
}

@Composable
private fun DebridSettings(store: DebridSettingsDataStore) {
    val state by store.settings.collectAsStateWithLifecycle(com.nuvio.tv.domain.model.DebridSettings())
    val scope = rememberCoroutineScope()
    Row { Label("Use debrid providers", Modifier.weight(1f)); Switch(state.enabled, { scope.launch { store.setEnabled(it) } }) }
    Row { Label("Cloud library", Modifier.weight(1f)); Switch(state.cloudLibraryEnabled, { scope.launch { store.setCloudLibraryEnabled(it) } }) }
    com.nuvio.tv.core.debrid.DebridProviders.all().filter { it.visibleInUi }.forEach { provider ->
        var value by remember { mutableStateOf("") }
        SectionTitle(provider.displayName)
        if (state.apiKeyFor(provider.id).isNotBlank()) Label("API key configured")
        Field("API key", value, true) { value = it }
        Action("Save " + provider.displayName + " key") {
            val key = value.trim()
            if (key.isNotEmpty()) scope.launch { store.setProviderApiKey(provider.id, key); value = "" }
        }
    }
}

@Composable
private fun AppearanceSettings(store: ThemeDataStore) {
    val selected by store.themeSelection.collectAsStateWithLifecycle(com.nuvio.tv.domain.model.ThemeSelection())
    val scope = rememberCoroutineScope()
    com.nuvio.tv.domain.model.AppTheme.entries.filter { it != com.nuvio.tv.domain.model.AppTheme.CUSTOM }.forEach { theme ->
        Action(theme.displayName, selected = selected.theme == theme) { scope.launch { store.setTheme(theme) } }
    }
    val amoled by store.amoledMode.collectAsStateWithLifecycle(false)
    val darkSurfaces by store.amoledSurfacesMode.collectAsStateWithLifecycle(false)
    Action("AMOLED background", selected = amoled) { scope.launch { store.setAmoledMode(!amoled) } }
    Action("AMOLED panels", selected = darkSurfaces) { scope.launch { store.setAmoledSurfacesMode(!darkSurfaces) } }
    var edit by rememberSaveable { mutableStateOf(false) }
    Action("Custom theme", selected = selected.theme == com.nuvio.tv.domain.model.AppTheme.CUSTOM) { edit = true }
    if (edit) MobileThemeEditor(selected.customColors, { edit = false }) { colors ->
        scope.launch { store.setCustomTheme(colors); edit = false }
    }
}
