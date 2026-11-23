package com.nuvio.tv.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.ui.screens.settings.LayoutSettingsEvent
import com.nuvio.tv.ui.screens.settings.LayoutSettingsViewModel

@Composable
internal fun MobileHomeSettings(vm: LayoutSettingsViewModel = hiltViewModel()) {
    val settings by vm.uiState.collectAsStateWithLifecycle()
    SectionTitle("Home")
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
    TogglePreference("Show trailers", settings.detailPageTrailerButtonEnabled) { vm.onEvent(LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled(it)) }
    TogglePreference("Prefer catalogue source metadata", settings.preferExternalMetaAddonDetail) { vm.onEvent(LayoutSettingsEvent.SetPreferExternalMetaAddonDetail(it)) }
}
