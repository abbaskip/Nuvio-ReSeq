// Custom colour controls adapted from NuvioPlusIPTV-Mobile CustomThemeEditor.kt. See NOTICE-MOBILE.md.
package com.nuvio.tv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.parseHexColor
import com.nuvio.tv.domain.model.formatHexColor
import com.nuvio.tv.ui.screens.settings.*

@Composable
internal fun MobileThemeEditor(initial: CustomThemeColors, back: () -> Unit, save: (CustomThemeColors) -> Unit) {
    var encoded by rememberSaveable { mutableStateOf(initial.encode()) }
    val colors = CustomThemeColors.decode(encoded)
    var gradient by rememberSaveable { mutableStateOf(!initial.isSolid) }
    var stop by rememberSaveable { mutableIntStateOf(0) }
    var hex by rememberSaveable(stop, gradient) { mutableStateOf(formatHexColor(colors.colors[stop])) }
    val displayed = if (gradient) colors else CustomThemeColors.solid(colors.first)
    TouchSheet(back) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(12.dp)) {
            BrowseHeading("Custom theme", back)
            Box(Modifier.fillMaxWidth().height(56.dp).background(Brush.horizontalGradient(displayed.colors.map { Color(0xff000000.toInt() or it) })))
            Row {
                Action("Solid", selected = !gradient) { stop = 0; gradient = false }
                Action("Gradient", selected = gradient) { gradient = true }
            }
            if (gradient) Row { repeat(3) { i -> Action("Colour ${i + 1}", selected = i == stop) { stop = i } } }
            MobileTextInput("Hex colour", hex) { value -> hex = value; parseHexColor(value)?.let { encoded = colors.withColor(stop, it).encode() } }
            if (parseHexColor(hex) == null) Label("Enter six hexadecimal digits, for example #678AFF.")
            Action("Apply theme") { if (parseHexColor(hex) != null) save(displayed) }
        }
    }
}

@Composable
internal fun MobileTmdbSettings(vm: TmdbSettingsViewModel = hiltViewModel()) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val language = remember(s.language) { mutableStateOf(s.language) }
    Action(if (s.hasApiKey) "Replace API key" else "Add API key") { key = true }
    if (s.hasApiKey) Action(if (remove) "Confirm remove API key" else "Remove API key") {
        if (remove) vm.clearApiKey(s.profileId) { remove = false } else remove = true
    }
    if (key) MobileTmdbKey(vm) { key = false }
    MobileTextInput("Metadata language", language.value) { language.value = it }
    Action("Save language") { if (language.value.isNotBlank()) vm.onEvent(TmdbSettingsEvent.SetLanguage(language.value.trim())) }
    Action("Use TMDB metadata", selected = s.enabled) { vm.onEvent(TmdbSettingsEvent.ToggleEnabled(!s.enabled)) }
    Action("Home highlights", selected = s.modernHomeEnabled) { vm.onEvent(TmdbSettingsEvent.ToggleModernHomeEnabled(!s.modernHomeEnabled)) }
    Action("Enrich Continue Watching", selected = s.enrichContinueWatching) { vm.onEvent(TmdbSettingsEvent.ToggleEnrichContinueWatching(!s.enrichContinueWatching)) }
    Action("Artwork", selected = s.useArtwork) { vm.onEvent(TmdbSettingsEvent.ToggleArtwork(!s.useArtwork)) }
    Action("Basic information", selected = s.useBasicInfo) { vm.onEvent(TmdbSettingsEvent.ToggleBasicInfo(!s.useBasicInfo)) }
    Action("Detailed information", selected = s.useDetails) { vm.onEvent(TmdbSettingsEvent.ToggleDetails(!s.useDetails)) }
    Action("Cast and crew", selected = s.useCredits) { vm.onEvent(TmdbSettingsEvent.ToggleCredits(!s.useCredits)) }
    Action("Production companies", selected = s.useProductions) { vm.onEvent(TmdbSettingsEvent.ToggleProductions(!s.useProductions)) }
    Action("Networks", selected = s.useNetworks) { vm.onEvent(TmdbSettingsEvent.ToggleNetworks(!s.useNetworks)) }
    Action("Episodes", selected = s.useEpisodes) { vm.onEvent(TmdbSettingsEvent.ToggleEpisodes(!s.useEpisodes)) }
    Action("Season posters", selected = s.useSeasonPosters) { vm.onEvent(TmdbSettingsEvent.ToggleSeasonPosters(!s.useSeasonPosters)) }
    Action("Trailers", selected = s.useTrailers) { vm.onEvent(TmdbSettingsEvent.ToggleTrailers(!s.useTrailers)) }
    Action("More like this", selected = s.useMoreLikeThis) { vm.onEvent(TmdbSettingsEvent.ToggleMoreLikeThis(!s.useMoreLikeThis)) }
    Action("Collections", selected = s.useCollections) { vm.onEvent(TmdbSettingsEvent.ToggleCollections(!s.useCollections)) }
}
