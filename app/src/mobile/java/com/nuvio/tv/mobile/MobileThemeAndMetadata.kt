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
    SectionTitle("TMDB account")
    Action(if (s.hasApiKey) "Replace API key" else "Add API key") { key = true }
    if (s.hasApiKey) Action(if (remove) "Confirm remove API key" else "Remove API key") {
        if (remove) vm.clearApiKey(s.profileId) { remove = false } else remove = true
    }
    if (key) MobileTmdbKey(vm) { key = false }
    SectionTitle("Metadata and language")
    MobileTextInput("Metadata language", language.value) { language.value = it }
    Action("Save language") { if (language.value.isNotBlank()) vm.onEvent(TmdbSettingsEvent.SetLanguage(language.value.trim())) }
    TogglePreference("Use TMDB metadata", s.enabled) { vm.onEvent(TmdbSettingsEvent.ToggleEnabled(it)) }
    TogglePreference("Home highlights", s.modernHomeEnabled) { vm.onEvent(TmdbSettingsEvent.ToggleModernHomeEnabled(it)) }
    TogglePreference("Enrich Continue Watching", s.enrichContinueWatching) { vm.onEvent(TmdbSettingsEvent.ToggleEnrichContinueWatching(it)) }
    SectionTitle("Title details")
    TogglePreference("Artwork", s.useArtwork) { vm.onEvent(TmdbSettingsEvent.ToggleArtwork(it)) }
    TogglePreference("Basic information", s.useBasicInfo) { vm.onEvent(TmdbSettingsEvent.ToggleBasicInfo(it)) }
    TogglePreference("Detailed information", s.useDetails) { vm.onEvent(TmdbSettingsEvent.ToggleDetails(it)) }
    TogglePreference("Cast and crew", s.useCredits) { vm.onEvent(TmdbSettingsEvent.ToggleCredits(it)) }
    TogglePreference("Production companies", s.useProductions) { vm.onEvent(TmdbSettingsEvent.ToggleProductions(it)) }
    TogglePreference("Networks", s.useNetworks) { vm.onEvent(TmdbSettingsEvent.ToggleNetworks(it)) }
    TogglePreference("Episodes", s.useEpisodes) { vm.onEvent(TmdbSettingsEvent.ToggleEpisodes(it)) }
    TogglePreference("Season posters", s.useSeasonPosters) { vm.onEvent(TmdbSettingsEvent.ToggleSeasonPosters(it)) }
    TogglePreference("Trailers", s.useTrailers) { vm.onEvent(TmdbSettingsEvent.ToggleTrailers(it)) }
    TogglePreference("More like this", s.useMoreLikeThis) { vm.onEvent(TmdbSettingsEvent.ToggleMoreLikeThis(it)) }
    TogglePreference("Collections", s.useCollections) { vm.onEvent(TmdbSettingsEvent.ToggleCollections(it)) }
}
