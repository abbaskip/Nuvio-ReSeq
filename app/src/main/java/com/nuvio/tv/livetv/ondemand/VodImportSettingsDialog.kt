package com.nuvio.tv.livetv.ondemand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow

/** Device-wide import choices, deliberately separate from a profile's hidden groups. */
@Composable
fun VodImportSettingsDialog(
    source: PlaylistSource,
    repository: OnDemandRepository,
    onSave: (PlaylistSource) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(source) { mutableStateOf(source) }
    var choosing by remember { mutableStateOf<VodKind?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(choosing) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    val kind = choosing
    if (kind != null) {
        var choices by remember(kind) { mutableStateOf<List<Pair<String, String>>?>(null) }
        var failed by remember(kind) { mutableStateOf(false) }
        var selected by remember(kind) { mutableStateOf(draft.vodCategories(kind == VodKind.SERIES)) }
        LaunchedEffect(kind) {
            runCatching { repository.importCategories(source.id, kind) }
                .onSuccess { choices = it }.onFailure { failed = true }
        }
        NuvioDialog(title = if (kind == VodKind.MOVIE) "Movie categories to import" else "Series categories to import",
            onDismiss = { choosing = null }, width = 640.dp) {
            LazyColumn(Modifier.heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Text("Only selected categories are imported or offered as sources. These choices apply to all profiles.", color = NuvioTheme.colors.TextPrimary) }
                item { SettingsToggleRow("All categories", "Includes new categories your provider adds", selected == null,
                    { selected = if (selected == null) emptySet() else null }, modifier = Modifier.focusRequester(first)) }
                if (choices == null) item { Text(if (failed) "Couldn't load categories. Your saved selection is unchanged." else "Loading categories…", color = NuvioTheme.colors.TextPrimary) }
                items(choices.orEmpty(), key = { it.first }) { (id, name) ->
                    SettingsToggleRow(name, "", selected == null || id in selected.orEmpty(), {
                        val previous = selected ?: choices.orEmpty().mapTo(mutableSetOf()) { it.first }
                        selected = if (id in previous) previous - id else previous + id
                    })
                }
                item { SettingsActionRow("Apply categories", subtitle = "", onClick = {
                    draft = if (kind == VodKind.MOVIE) draft.copy(vodMovieCategories = selected)
                        else draft.copy(vodSeriesCategories = selected)
                    choosing = null
                }) }
                item { SettingsActionRow("Cancel", subtitle = "", onClick = { choosing = null }) }
            }
        }
        return
    }
    NuvioDialog(title = "On Demand imports", onDismiss = onDismiss, width = 640.dp) {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("${source.name}: keep every source by default, or choose what this device imports.", color = NuvioTheme.colors.TextPrimary) }
            item { SettingsToggleRow("Import movies", "", draft.vodMovies, { draft = draft.copy(vodMovies = !draft.vodMovies) }, modifier = Modifier.focusRequester(first)) }
            item { SettingsToggleRow("Import series", "", draft.vodSeries, { draft = draft.copy(vodSeries = !draft.vodSeries) }) }
            item { SettingsToggleRow("Source matching only", "Keeps every imported source. Artwork addresses are fetched as you browse and kept in a small cache.",
                draft.vodSourceOnly, { draft = draft.copy(vodSourceOnly = !draft.vodSourceOnly) }) }
            item { SettingsActionRow("Movie categories", subtitle = "Only these categories can match as sources", value = draft.vodMovieCategories?.size?.let { "$it selected" } ?: "All",
                onClick = { choosing = VodKind.MOVIE }) }
            item { SettingsActionRow("Series categories", subtitle = "Only these categories can match as sources", value = draft.vodSeriesCategories?.size?.let { "$it selected" } ?: "All",
                onClick = { choosing = VodKind.SERIES }) }
            item { SettingsActionRow("Save and update imports", subtitle = "Applies to every profile on this device", onClick = { onSave(draft); onDismiss() }) }
            item { SettingsActionRow("Cancel", subtitle = "", onClick = onDismiss) }
        }
    }
}
