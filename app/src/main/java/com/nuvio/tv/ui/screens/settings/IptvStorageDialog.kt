package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.nuvio.tv.livetv.data.IptvStorageSnapshot
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
internal fun IptvStorageDialog(
    snapshot: IptvStorageSnapshot?, busy: Boolean, message: String?,
    onMeasure: () -> Unit, onClean: () -> Unit, onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(busy) { if (!busy) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } } }
    NuvioDialog(title = "IPTV storage", onDismiss = onDismiss, width = 680.dp) {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(if (busy) "Measuring or cleaning storage…" else message ?: "Measure the catalogue, guide and downloaded files separately.", color = NuvioTheme.colors.TextPrimary) }
            item { SettingsActionRow("Refresh sizes", subtitle = "Reports sizes without exporting file contents", enabled = !busy,
                modifier = Modifier.focusRequester(first), onClick = onMeasure) }
            item { SettingsActionRow("Clean up unused data", subtitle = "Removes abandoned imports and expired files, then reclaims free database space when possible",
                enabled = !busy, onClick = onClean) }
            items(snapshot?.categories.orEmpty(), key = { it.label }) { category ->
                Text("${category.label}: ${IptvStorageSnapshot.size(category.bytes)}", color = NuvioTheme.colors.TextPrimary)
            }
            item { Text("Artwork and update installers are shared app storage. Cleanup keeps recordings, provider logins, selections and valid catalogue entries.", color = NuvioTheme.colors.TextPrimary) }
            item { SettingsActionRow("Close", subtitle = "", onClick = onDismiss) }
        }
    }
}
