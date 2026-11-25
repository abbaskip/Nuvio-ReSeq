package com.nuvio.tv.livetv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveText

@Composable
internal fun ChooseSportsDialog(viewModel: SportsViewModel, onTeams: () -> Unit, calendarOptions: (@Composable () -> Unit)? = null, onClose: () -> Unit) {
    var geminiSetup by rememberSaveable { mutableStateOf(false) }
    if (geminiSetup) {
        SportsGeminiKeyDialog(viewModel.gemini, onClose)
        return
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SportsPreferencesDialog(settings.excludedSportsNodes, SportsCatalog.regionOf(settings),
        onRegion = { region -> viewModel.repo.updateSettings { it.copy(region = region) } },
        onToggle = { id -> viewModel.repo.updateSettings { current ->
            val excluded = current.excludedSportsNodes
            current.copy(excludedSportsNodes = if (id in excluded) excluded - id else excluded + id)
        } },
        discoverFixtures = settings.discoverGuideFixtures,
        onDiscoverFixtures = {
            if (!settings.discoverGuideFixtures && !viewModel.gemini.configured()) geminiSetup = true
            else viewModel.repo.updateSettings { it.copy(discoverGuideFixtures = !it.discoverGuideFixtures) }
        },
        geminiMatching = settings.backgroundGeminiMatching,
        onToggleGemini = {
            if (!settings.backgroundGeminiMatching && !viewModel.gemini.configured()) geminiSetup = true
            else viewModel.repo.updateSettings { it.copy(backgroundGeminiMatching = !it.backgroundGeminiMatching) }
        },
        onTeams = onTeams, onGemini = { geminiSetup = true }, calendarOptions = calendarOptions,
        onClose = { viewModel.repo.markSetUp(); onClose() })
}

@Composable
private fun SportsPreferencesDialog(excluded: Set<String>, region: String, onRegion: (String) -> Unit, onToggle: (String) -> Unit,
    discoverFixtures: Boolean, onDiscoverFixtures: () -> Unit,
    geminiMatching: Boolean, onToggleGemini: () -> Unit, onTeams: () -> Unit, onGemini: () -> Unit,
    calendarOptions: (@Composable () -> Unit)?, onClose: () -> Unit) {
    var regionOpen by remember { mutableStateOf(false) }
    var parentId by remember { mutableStateOf<String?>(null) }
    val parent = parentId?.let(SportsTaxonomy.byId::get)
    fun back() { if (parent == null) onClose() else parentId = parent.parentId }
    BackHandler { back() }
    if (regionOpen) {
        SportsDialog(onDismiss = { regionOpen = false }, width = 760.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveText("Sports and TV coverage region", size = 22.sp)
                SportsCatalog.regions.forEach { (code, label) ->
                    SportsPill(label, selected = region == code, onClick = { onRegion(code); regionOpen = false })
                }
                SportsPill("Back", onClick = { regionOpen = false })
            }
        }
        return
    }
    SportsDialog(onDismiss = { back() }, width = 760.dp) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveText(parent?.label ?: "Choose Sports", size = 22.sp)
            LiveText("Everything is included unless switched off. Open a sport or category to exclude individual competitions.", size = 13.sp, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SportsPill(if (parent == null) "Done" else "Back", onClick = { back() })
                if (parent == null) SportsPill("Follow Teams & Leagues", onClick = onTeams)
                if (parent == null) SportsPill("Gemini key", onClick = onGemini)
                if (parent == null) SportsPill("Region", onClick = { regionOpen = true })
            }
            if (parent == null) {
                calendarOptions?.invoke()
                SportsPill("Gemini guide matching: " + if (geminiMatching) "On" else "Off", onClick = onToggleGemini)
                SportsPill("Verify missing guide events: " + if (discoverFixtures) "On" else "Off", onClick = onDiscoverFixtures)
                LiveText("Uses Gemini Search to check unmatched IPTV listings against published schedules for All Events. Up to 3 checks per hour and 8 per day within the shared allowance.", size = 12.sp, maxLines = 3)
                LiveText("Guide checks and Ask Gemini share 12 requests per hour and 40 per 24 hours on this device. Google project limits and charges may also apply.", size = 12.sp, maxLines = 3)
            }
            if (parent != null && !SportsTaxonomy.enabled(parent.id, excluded))
                LiveText("This branch is switched off. Enable its parent to include these events.", size = 13.sp)
            LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(parent?.children ?: SportsTaxonomy.browseRoots, key = { it.id }) { node ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SportsPill(if (node.id in excluded) "Off" else "On", onClick = { onToggle(node.id) })
                        SportsPill(node.label + if (node.children.isNotEmpty()) "  ›" else "",
                            modifier = Modifier.weight(1f),
                            onClick = { if (node.children.isNotEmpty()) parentId = node.id else onToggle(node.id) })
                    }
                }
            }
        }
    }
}
