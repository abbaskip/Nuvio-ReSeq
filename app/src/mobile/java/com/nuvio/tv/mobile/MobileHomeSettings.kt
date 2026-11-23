package com.nuvio.tv.mobile

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.DetailImdbRatingsVisibility
import com.nuvio.tv.ui.screens.settings.LayoutSettingsEvent
import com.nuvio.tv.ui.screens.settings.LayoutSettingsViewModel

@Composable
internal fun MobileHomeSettings(vm: LayoutSettingsViewModel = hiltViewModel()) {
    val settings by vm.uiState.collectAsStateWithLifecycle()
    var order by rememberSaveable { mutableStateOf(false) }
    if (order) TouchSheet({ order = false }) { MobileCatalogOrder { order = false } }
    SectionTitle("Home")
    Action("Reorder Home rows") { order = true }
    TogglePreference("Show highlights", settings.heroSectionEnabled) { vm.onEvent(LayoutSettingsEvent.SetHeroSectionEnabled(it)) }
    if (settings.heroSectionEnabled && settings.availableCatalogs.isNotEmpty()) {
        SectionTitle("Highlight sources")
        settings.availableCatalogs.forEach { catalog ->
            Action(catalog.name + " · " + catalog.addonName, selected = catalog.key in settings.heroCatalogKeys) {
                vm.onEvent(LayoutSettingsEvent.ToggleHeroCatalog(catalog.key))
            }
        }
    }
    TogglePreference("Show poster titles", settings.posterLabelsEnabled) { vm.onEvent(LayoutSettingsEvent.SetPosterLabelsEnabled(it)) }
    TogglePreference("Show catalogue source", settings.catalogAddonNameEnabled) { vm.onEvent(LayoutSettingsEvent.SetCatalogAddonNameEnabled(it)) }
    TogglePreference("Hide unreleased titles", settings.hideUnreleasedContent) { vm.onEvent(LayoutSettingsEvent.SetHideUnreleasedContent(it)) }
    SectionTitle("Posters and ratings")
    HomeChoice("Poster size", settings.posterCardWidthDp, listOf(96 to "Compact", 126 to "Standard", 156 to "Large")) {
        vm.onEvent(LayoutSettingsEvent.SetPosterCardWidth(it))
    }
    HomeChoice("Poster corners", settings.posterCardCornerRadiusDp, listOf(0 to "Square", 12 to "Soft", 24 to "Rounded")) {
        vm.onEvent(LayoutSettingsEvent.SetPosterCardCornerRadius(it))
    }
    var editingPosters by rememberSaveable { mutableStateOf(false) }
    Action("Custom poster artwork") { editingPosters = true }
    if (editingPosters) TouchSheet({ editingPosters = false }) {
        var pattern by rememberSaveable { mutableStateOf(settings.customPosterUrlPattern) }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { BrowseHeading("Custom poster artwork") { editingPosters = false } }
            item { Label(stringResource(R.string.layout_custom_poster_description), small = true) }
            item { OutlinedTextField(pattern, { pattern = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Label("Poster URL pattern") }, placeholder = { Label(stringResource(R.string.web_custom_poster_placeholder), small = true) }) }
            item { Action("Save pattern") { vm.onEvent(LayoutSettingsEvent.SetCustomPosterUrlPattern(pattern.trim())); editingPosters = false } }
            items(CustomPosterScreen.entries.filter { it != CustomPosterScreen.TV_RECOMMENDATIONS }) { screen ->
                TogglePreference(screen.key.replace('_', ' ').replaceFirstChar { it.uppercase() }, screen in settings.customPosterEnabledScreens) {
                    vm.onEvent(LayoutSettingsEvent.SetCustomPosterScreenEnabled(screen, it))
                }
            }
        }
    }
    TogglePreference("Show standard title ratings", settings.homeImdbRatingsVisibility.showRatings) {
        vm.onEvent(LayoutSettingsEvent.SetHomeImdbRatingsVisibility(if (it) HomeImdbRatingsVisibility.SHOW_ALL else HomeImdbRatingsVisibility.HIDE_ALL))
    }
    HomeChoice("Episode ratings", settings.detailImdbRatingsVisibility.asEpisodeVisibility(), listOf(
        DetailImdbRatingsVisibility.SHOW_ALL to "All episodes", DetailImdbRatingsVisibility.HIDE_UNWATCHED_EPISODES to "Watched only",
        DetailImdbRatingsVisibility.HIDE_EPISODES to "Hidden")) { vm.onEvent(LayoutSettingsEvent.SetDetailImdbRatingsVisibility(it)) }
    SectionTitle("Continue watching")
    TogglePreference("Show Continue watching", settings.continueWatchingEnabled) { vm.onEvent(LayoutSettingsEvent.SetContinueWatchingEnabled(it)) }
    if (settings.continueWatchingEnabled) {
        TogglePreference("Use episode thumbnails", settings.useEpisodeThumbnailsInCw) { vm.onEvent(LayoutSettingsEvent.SetUseEpisodeThumbnailsInCw(it)) }
        TogglePreference("Continue from furthest episode", settings.nextUpFromFurthestEpisode) { vm.onEvent(LayoutSettingsEvent.SetNextUpFromFurthestEpisode(it)) }
        TogglePreference("Show upcoming episodes", settings.showUnairedNextUp) { vm.onEvent(LayoutSettingsEvent.SetShowUnairedNextUp(it)) }
        ContinueWatchingSortMode.entries.forEach { mode ->
            val label = when (mode) {
                ContinueWatchingSortMode.DEFAULT -> "Default order"
                ContinueWatchingSortMode.STREAMING_STYLE -> stringResource(R.string.layout_cw_sort_streaming)
                ContinueWatchingSortMode.SPLIT_UPCOMING -> stringResource(R.string.layout_cw_sort_split_upcoming)
            }
            Action(label, selected = mode == settings.continueWatchingSortMode) { vm.onEvent(LayoutSettingsEvent.SetContinueWatchingSortMode(mode)) }
        }
    }
    SectionTitle("Title details")
    TogglePreference("Hide unwatched episode artwork", settings.blurUnwatchedEpisodes) { vm.onEvent(LayoutSettingsEvent.SetBlurUnwatchedEpisodes(it)) }
    TogglePreference("Show episode shuffle", settings.randomEpisodeEnabled) { vm.onEvent(LayoutSettingsEvent.SetRandomEpisodeEnabled(it)) }
    TogglePreference("Prefer catalogue source metadata", settings.preferExternalMetaAddonDetail) { vm.onEvent(LayoutSettingsEvent.SetPreferExternalMetaAddonDetail(it)) }
    SectionTitle("Trailers")
    TogglePreference("Show trailers", settings.detailPageTrailerButtonEnabled) { vm.onEvent(LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled(it)) }
    val trailerNetwork = LocalTrailerNetwork.current
    val allowMobileData by trailerNetwork.allowMobileData.collectAsStateWithLifecycle()
    TogglePreference("Allow trailers on mobile data", allowMobileData, trailerNetwork::setAllowMobileData)
    TogglePreference("Autoplay on title details", settings.detailPageTrailerAutoplayEnabled) { vm.onEvent(LayoutSettingsEvent.SetDetailPageTrailerAutoplayEnabled(it)) }
    if (settings.detailPageTrailerAutoplayEnabled) {
        HomeChoice("Start after", settings.detailPageTrailerAutoplayDelaySeconds,
            (listOf(3, 5, 7, 10, 15, 30) + settings.detailPageTrailerAutoplayDelaySeconds).distinct().sorted().map { it to "$it s" }) {
            vm.onEvent(LayoutSettingsEvent.SetDetailPageTrailerAutoplayDelaySeconds(it))
        }
        TogglePreference("Play behind title artwork", settings.detailPageTrailerPlayInBackground) { vm.onEvent(LayoutSettingsEvent.SetDetailPageTrailerPlayInBackground(it)) }
        if (settings.detailPageTrailerPlayInBackground) TogglePreference("Pause while scrolling", settings.detailPageTrailerPauseOnScroll) {
            vm.onEvent(LayoutSettingsEvent.SetDetailPageTrailerPauseOnScroll(it))
        }
    }
}

@Composable
private fun <T> HomeChoice(label: String, current: T, choices: List<Pair<T, String>>, select: (T) -> Unit) {
    Label(label)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(choices) { (value, name) -> Action(name, selected = value == current) { select(value) } }
    }
}
