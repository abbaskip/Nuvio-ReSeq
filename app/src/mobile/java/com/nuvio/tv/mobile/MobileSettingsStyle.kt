package com.nuvio.tv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val LocalSettingsMenu = staticCompositionLocalOf { false }

@Composable
internal fun SettingsHeading(title: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontFamily = condensed, fontWeight = FontWeight.SemiBold, color = colors.onSurface,
            fontSize = 17.sp, lineHeight = 21.sp, letterSpacing = 0.sp)
        HorizontalDivider(Modifier.weight(1f), color = colors.onSurfaceVariant.copy(alpha = .32f))
    }
}

@Composable
internal fun SettingsLink(title: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.surface).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(title, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun <T> TrackerSourceGroup(title: String, options: List<T>, choice: MobileTrackerChoice<T>, name: (T) -> String) {
    val requested by choice.selected.collectAsStateWithLifecycle()
    val failed by choice.hasError.collectAsStateWithLifecycle()
    val selected = if (requested in options) requested else options.first()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surface).border(1.dp, colors.onSurfaceVariant.copy(alpha = .25f))
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(title, Modifier.semantics { heading() }, fontFamily = condensed, fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.sp, color = colors.onSurface)
        HorizontalDivider(Modifier.padding(top = 6.dp), color = colors.onSurfaceVariant.copy(alpha = .2f))
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { option ->
                val active = option == selected
                Row(Modifier.selectable(active, role = Role.RadioButton) { choice.select(option) }
                    .heightIn(min = 48.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioButton(selected = active, onClick = null, modifier = Modifier.size(18.dp))
                    Label(name(option), strong = active)
                }
            }
        }
        if (failed) Label("Couldn’t finish switching this source. Try again.", small = true)
    }
}
