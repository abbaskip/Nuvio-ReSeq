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
    DEBRID("Debrid providers"), THEME("Appearance"), IPTV("IPTV sources"), TMDB("TMDB metadata"), PROVIDERS("AnimeSkip"),
    SERVERS("Media servers"), HOME("Home and title details"), ABOUT("About and credits")
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
    CompositionLocalProvider(LocalSettingsMenu provides true) {
        Column(Modifier.fillMaxSize()) {
            if (page == null || page == SettingsPage.ADDONS) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val groups = listOf(
                        "Accounts and backup" to listOf(SettingsPage.ACCOUNT, SettingsPage.PROFILES, SettingsPage.TRACKING, SettingsPage.DRIVE),
                        "Content sources" to listOf(SettingsPage.IPTV, SettingsPage.ADDONS, SettingsPage.PLUGINS, SettingsPage.DEBRID, SettingsPage.SERVERS),
                        "Home and playback" to listOf(SettingsPage.HOME, SettingsPage.PLAYBACK, SettingsPage.THEME),
                        "Metadata and extras" to listOf(SettingsPage.TMDB, SettingsPage.PROVIDERS, SettingsPage.ABOUT)
                    )
                    groups.forEach { (title, destinations) ->
                        SettingsHeading(title)
                        destinations.forEach { option -> SettingsLink(option.title) { page = option } }
                    }
                }
                if (page == SettingsPage.ADDONS) CatalogSources { page = null }
            } else {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Action("Back") { page = null }
                    Label(page!!.title, Modifier.padding(start = 12.dp), strong = true)
                }
                HorizontalDivider()
                when (page) {
                    SettingsPage.IPTV -> MobileSetup()
                    SettingsPage.DRIVE -> MobileSetup(driveOnly = true)
                    SettingsPage.ABOUT -> MobileAbout()
                    else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        key(page) { when (page) {
                            SettingsPage.ACCOUNT -> AccountSettings()
                            SettingsPage.PROFILES -> ProfilesSettings()
                            SettingsPage.PLUGINS -> ExtensionSettings()
                            SettingsPage.PLAYBACK -> PlaybackSettings()
                            SettingsPage.HOME -> MobileHomeSettings()
                            SettingsPage.TRACKING -> TrackerSettings()
                            SettingsPage.DEBRID -> DebridSettings(catalog.debrid)
                            SettingsPage.THEME -> AppearanceSettings(catalog.themes)
                            SettingsPage.TMDB -> MobileTmdbSettings()
                            SettingsPage.PROVIDERS -> MobileAnimeSkipSettings()
                            SettingsPage.SERVERS -> MobileMediaServers()
                            else -> Unit
                        } }
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
internal fun MobileSecretField(label: String, value: String, update: (String) -> Unit) = Field(label, value, true, update)

@Composable
private fun AccountSettings(vm: AccountViewModel = hiltViewModel(), link: MobileAccountLink = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val login by link.ui.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(link) {
        link.foreground(true)
        onPauseOrDispose { link.foreground(false) }
    }
    val uri = LocalUriHandler.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val signed = state.authState as? AuthState.FullAccount
    SectionTitle("Account")
    if (signed != null) {
        Label(signed.email)
        var requested by remember(signed.userId) { mutableStateOf(false) }
        Action("Sync account now") { link.syncNow(); requested = true }
        if (requested) Label("Account sync requested.")
        Action("Sign out") { vm.signOut() }
    } else if (vm.usesEmailPasswordLogin) {
        Field("Email", email) { email = it }
        Field("Password", password, true) { password = it }
        Row { Action("Sign in") { vm.signIn(email.trim(), password) }; Action("Create account") { vm.signUp(email.trim(), password) } }
    } else {
        if (login.code == null && !login.busy) Action(if (login.message.isEmpty()) "Sign in to Nuvio" else "Request new code", onClick = link::start)
        login.code?.let { Label(it, strong = true) }
        login.address?.let { url ->
            var showAddress by rememberSaveable(url) { mutableStateOf(false) }
            Action("Open Nuvio sign-in") {
                try { uri.openUri(url) } catch (_: IllegalArgumentException) { showAddress = true }
            }
            if (showAddress) androidx.compose.foundation.text.selection.SelectionContainer {
                Label("Open this address in a browser: $url")
            }
        }
        if (login.message.isNotEmpty()) Label(login.message)
        if (login.code != null) Row {
            Action("Check approval", onClick = link::checkNow)
            Action("Cancel sign-in", onClick = link::cancel)
        }
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
    SectionTitle("Switch profile")
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
        SectionTitle("New profile")
        Field("New profile name", name) { name = it }
        Action("Create profile") { if (name.isNotBlank()) vm.createProfile(name.trim(), "#455A64") { name = "" } }
    }
    var editing by remember { mutableStateOf<Int?>(null) }
    SectionTitle("Manage profiles")
    profiles.filter { it.id == active || active == 1 }.forEach { profile ->
        Action("Manage " + profile.name) { editing = profile.id }
    }
    profiles.firstOrNull { it.id == editing }?.let { profile ->
        MobileProfileEditor(profile, locks[profile.id] == true || vm.isProfilePinEnabled(profile.id), vm) { editing = null }
    }
    if (message.isNotBlank()) Label(message)
}

@Composable
private fun ExtensionSettings(vm: PluginViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val activity = androidx.activity.compose.LocalActivity.current
    if (vm.isReadOnly) {
        Label("This profile uses the primary profile’s extensions.")
    } else {
        SectionTitle("Extensions")
        TogglePreference("Use extensions", state.pluginsEnabled) { vm.onEvent(PluginUiEvent.SetPluginsEnabled(it)) }
        TogglePreference("Group streams by repository", state.groupStreamsByRepository) { vm.onEvent(PluginUiEvent.SetGroupStreamsByRepository(it)) }
        Action("Extension DNS · " + state.extensionDns) { vm.onEvent(PluginUiEvent.CycleExtensionDns) }
        SectionTitle("Source languages")
        Action("All languages", selected = state.sourceLanguages.isEmpty()) { vm.onEvent(PluginUiEvent.ToggleSourceLanguage(null)) }
        (state.installedLanguages.keys + state.sourceLanguages).sorted().forEach { code ->
            val language = java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
            Action(language, selected = code in state.sourceLanguages) { vm.onEvent(PluginUiEvent.ToggleSourceLanguage(code)) }
        }
        Field("Repository URL", url) { url = it }
        Action(if (state.isAddingRepo) "Adding repository…" else "Add repository") {
            if (!state.isAddingRepo && url.isNotBlank()) vm.onEvent(PluginUiEvent.AddRepository(url.trim()))
        }
    }
    state.repositories.forEach { repo ->
        SectionTitle(repo.name)
        if (!vm.isReadOnly) {
            Row {
                Action("Refresh") { vm.onEvent(PluginUiEvent.RefreshRepository(repo.id)) }
                Action("Remove") { remove = repo.id }
            }
        }
        state.scrapers.filter { it.repositoryId == repo.id }.forEach { scraper ->
            Action(scraper.name, selected = scraper.enabled) { selected = if (selected == scraper.id) null else scraper.id }
            if (selected == scraper.id) {
                if (scraper.description.isNotBlank()) Label(scraper.description)
                if (!vm.isReadOnly) TogglePreference("Enable extension", scraper.enabled) {
                    vm.onEvent(PluginUiEvent.ToggleScraper(scraper.id, it))
                }
                val configurable = !vm.isReadOnly && state.pluginsEnabled && scraper.enabled && repo.enabled &&
                    scraper.type == com.nuvio.tv.domain.model.RepositoryType.EXTERNAL_DEX
                val hasSettings by produceState(false, scraper, repo.lastUpdated, configurable) {
                    value = configurable && vm.hasExtensionSettings(scraper.id)
                }
                if (hasSettings && activity != null) Action("Extension settings") { vm.openExtensionSettings(activity, scraper.id) }
                if (scraper.enabled && state.pluginsEnabled && !vm.isReadOnly) {
                    Action(if (state.isTesting && state.testScraperId == scraper.id) "Testing…" else "Test extension") {
                        if (!state.isTesting) vm.onEvent(PluginUiEvent.TestScraper(scraper.id))
                    }
                }
                if (state.testScraperId == scraper.id) {
                    state.testDiagnostics?.steps?.forEach { Label(it) }
                    state.testResults?.let { results ->
                        Label("Sources found · " + results.size)
                        results.forEach { Label(it.title) }
                        Action("Clear results") { vm.onEvent(PluginUiEvent.ClearTestResults) }
                    }
                }
            }
        }
    }
    state.repositories.firstOrNull { it.id == remove }?.let { repo ->
        AlertDialog(onDismissRequest = { remove = null }, shape = RectangleShape,
            title = { Label("Remove " + repo.name + "?") },
            text = { Label("This removes its extensions from this profile.") },
            confirmButton = { Action("Remove repository") { vm.onEvent(PluginUiEvent.RemoveRepository(repo.id)); remove = null } },
            dismissButton = { Action("Cancel") { remove = null } })
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
    MobilePlaybackPreferences(state, vm)
}

@Composable
private fun TrackerSettings(trakt: TraktViewModel = hiltViewModel(), simkl: SimklSettingsViewModel = hiltViewModel(),
    tvdb: TvdbSettingsViewModel = hiltViewModel(), tracking: TrackingSettingsViewModel = hiltViewModel(),
    choices: MobileTrackerChoices = hiltViewModel()) {
    val t by trakt.uiState.collectAsStateWithLifecycle()
    val s by simkl.uiState.collectAsStateWithLifecycle()
    val db by tvdb.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val sources by tracking.uiState.collectAsStateWithLifecycle()
    if (sources.connectedProviderIds.isNotEmpty()) {
        TrackerSourceGroup("Continue Watching source", sources.availableWatchProgressSources, choices.progress, ::trackerName)
        TrackerSourceGroup("Library source", sources.availableLibrarySourceModes, choices.library, ::trackerName)
        TrackerSourceGroup("Personal ratings source", listOf(null) + sources.availableRatingSources, choices.rating) {
            it?.let(::trackerName) ?: "All connected trackers"
        }
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
    MobileMdbListSettings()
    SectionTitle("TheTVDB")
    var key by remember { mutableStateOf("") }
    if (db.configured) Label("API key configured")
    Field("TVDB API key", key, true) { key = it }
    Row { Action("Save key") { if (key.isNotBlank()) { tvdb.save(key.trim()); key = "" } }; Action("Test connection", onClick = tvdb::test) }
    db.message?.let { Label(it) }
}

internal fun trackerName(source: Enum<*>): String = when (source.name) {
    "MDBLIST" -> "MDBList"
    "LOCAL" -> "Nuvio library"
    "NUVIO_SYNC" -> "Nuvio sync"
    else -> source.name.lowercase().replaceFirstChar { it.uppercase() }
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
    SectionTitle("Theme")
    com.nuvio.tv.domain.model.AppTheme.entries.filter { it != com.nuvio.tv.domain.model.AppTheme.CUSTOM }.forEach { theme ->
        Action(theme.displayName, selected = selected.theme == theme) { scope.launch { store.setTheme(theme) } }
    }
    SectionTitle("Display")
    val amoled by store.amoledMode.collectAsStateWithLifecycle(false)
    val darkSurfaces by store.amoledSurfacesMode.collectAsStateWithLifecycle(false)
    TogglePreference("AMOLED background", amoled) { scope.launch { store.setAmoledMode(it) } }
    TogglePreference("AMOLED panels", darkSurfaces) { scope.launch { store.setAmoledSurfacesMode(it) } }
    var edit by rememberSaveable { mutableStateOf(false) }
    Action("Custom theme", selected = selected.theme == com.nuvio.tv.domain.model.AppTheme.CUSTOM) { edit = true }
    if (edit) MobileThemeEditor(selected.customColors, { edit = false }) { colors ->
        scope.launch { store.setCustomTheme(colors); edit = false }
    }
}
