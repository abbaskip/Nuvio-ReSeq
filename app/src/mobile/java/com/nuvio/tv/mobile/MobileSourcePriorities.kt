package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.*
import com.nuvio.tv.ui.screens.settings.PlaybackSettingsViewModel
import kotlinx.coroutines.launch

private fun timerText(seconds: Int) = when (seconds) {
    PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED -> "Wait for all sources"
    0 -> "Immediately"
    else -> "$seconds s"
}

@Composable
internal fun MobileSourcePriorities(state: PlayerSettings, vm: PlaybackSettingsViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsLink("Autoplay source priorities") { open = true }
    if (!open) return
    val scope = rememberCoroutineScope()
    val addons by vm.installedAddonNames.collectAsStateWithLifecycle(emptyList())
    val plugins by vm.enabledPluginNames.collectAsStateWithLifecycle(emptyList())
    val wait = state.streamAutoPlayTimeoutSeconds
    TouchSheet({ open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BrowseHeading("Autoplay source priorities") { open = false }
            Label("Saved downloads play first.", small = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(StreamAutoPlayMode.MANUAL to "Choose manually", StreamAutoPlayMode.FIRST_STREAM to "First match",
                    StreamAutoPlayMode.REGEX_MATCH to "Match expression").forEach { (mode, label) ->
                    Action(label, selected = state.streamAutoPlayMode == mode) { scope.launch { vm.setStreamAutoPlayMode(mode) } }
                }
            }
            if (state.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) {
                Label("Choose First match or Match expression to set your autoplay priorities.", small = true)
            } else {
                if (state.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
                    var pattern by remember(state.streamAutoPlayRegex) { mutableStateOf(state.streamAutoPlayRegex) }
                    MobileTextInput("Source expression", pattern) { pattern = it }
                    Action("Save expression") { scope.launch { vm.setStreamAutoPlayRegex(pattern) } }
                }
                SettingsHeading("First result")
                TogglePreference("Play immediately from selected add-ons", state.streamAutoPlayImmediateEnabled) {
                    scope.launch { vm.setStreamAutoPlayImmediateEnabled(it) }
                }
                if (state.streamAutoPlayImmediateEnabled) {
                    ProviderSelection("Immediate add-ons", addons, state.streamAutoPlayImmediateAddons, "None") {
                        scope.launch { vm.setStreamAutoPlayImmediateAddons(it) }
                    }
                    TogglePreference("Include IPTV VOD in first results", state.streamAutoPlayImmediatePlaylistsEnabled) {
                        scope.launch { vm.setStreamAutoPlayImmediatePlaylistsEnabled(it) }
                    }
                }
                SettingsHeading("Approved sources")
                TimerSetting("Approved-source wait", wait, PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES) {
                    scope.launch { vm.setStreamAutoPlayTimeoutSeconds(it) }
                }
                val allowAddons = state.streamAutoPlayAddonsEnabled ?: (state.streamAutoPlaySource != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY)
                TogglePreference("Use installed add-ons", allowAddons) { scope.launch { vm.setStreamAutoPlayAddonsEnabled(it) } }
                if (allowAddons) ProviderSelection("Approved add-ons", addons, state.streamAutoPlaySelectedAddons, "All") {
                    scope.launch { vm.setStreamAutoPlaySelectedAddons(it) }
                }
                if (AppFeaturePolicy.pluginsEnabled) {
                    val allowPlugins = state.streamAutoPlayPluginsEnabled ?: (state.streamAutoPlaySource != StreamAutoPlaySource.INSTALLED_ADDONS_ONLY)
                    TogglePreference("Use enabled extensions", allowPlugins) { scope.launch { vm.setStreamAutoPlayPluginsEnabled(it) } }
                    if (allowPlugins) ProviderSelection("Approved extensions", plugins, state.streamAutoPlaySelectedPlugins, "All") {
                        scope.launch { vm.setStreamAutoPlaySelectedPlugins(it) }
                    }
                }
                TogglePreference("Include IPTV VOD in approved sources", state.streamAutoPlayPlaylistsEnabled) {
                    scope.launch { vm.setStreamAutoPlayPlaylistsEnabled(it) }
                }
                SettingsHeading("Any-source fallback")
                if (wait == PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED) {
                    Label("Choose a timed wait above to enable fallback.", small = true)
                } else {
                    TogglePreference("Fall back to any playable source", state.streamAutoPlayAnySourceFallbackEnabled) {
                        scope.launch { vm.setStreamAutoPlayAnySourceFallbackEnabled(it) }
                    }
                    if (state.streamAutoPlayAnySourceFallbackEnabled) {
                        Label("Both timers start when the search begins.", small = true)
                        TimerSetting("Fallback wait", PlayerSettings.normalizeAutoPlayFallbackSeconds(wait, state.streamAutoPlayAnySourceFallbackSeconds),
                            PlayerSettings.STREAM_AUTOPLAY_FALLBACK_TIMEOUT_VALUES.filter { it > wait }) {
                            scope.launch { vm.setStreamAutoPlayAnySourceFallbackSeconds(it) }
                        }
                        TogglePreference("Include IPTV VOD in fallback", state.streamAutoPlayFallbackPlaylistsEnabled) {
                            scope.launch { vm.setStreamAutoPlayFallbackPlaylistsEnabled(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerSetting(title: String, value: Int, values: List<Int>, save: (Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsLink("$title · ${timerText(value)}") { expanded = !expanded }
    if (expanded) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { seconds -> Action(timerText(seconds), selected = value == seconds) { save(seconds); expanded = false } }
    }
}

@Composable
private fun ProviderSelection(title: String, available: List<String>, selected: Set<String>, empty: String, save: (Set<String>) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = if (selected.isEmpty()) empty else selected.sorted().joinToString()
    SettingsLink("$title · $summary") { expanded = !expanded }
    if (expanded) {
        if (available.isEmpty() && selected.isEmpty()) Label("No stream providers available for this list.", small = true)
        val options = (available + selected).distinct().sorted()
        options.forEach { name -> TogglePreference(name, name in selected) { enabled ->
            save(if (enabled) selected + name else selected - name)
        } }
    }
}
