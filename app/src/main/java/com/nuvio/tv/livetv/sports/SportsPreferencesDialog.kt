package com.nuvio.tv.livetv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveText

@Composable
internal fun SportsPreferencesDialog(excluded: Set<String>, region: String, onRegion: (String) -> Unit, onToggle: (String) -> Unit,
    geminiMatching: Boolean, onToggleGemini: () -> Unit, onTeams: () -> Unit, onGemini: () -> Unit, onClose: () -> Unit) {
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
                SportsPill("Gemini guide matching: " + if (geminiMatching) "On" else "Off", onClick = onToggleGemini)
                LiveText("Sends uncertain event and programme text to Google using your key. Up to 12 requests/hour and 40 per 24 hours per device. Google project charges may apply.", size = 12.sp, maxLines = 3)
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
