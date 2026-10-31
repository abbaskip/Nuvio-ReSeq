package com.nuvio.tv.livetv.ondemand

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.theme.NuvioTheme

/** What each sort does, under its name in the list. */
internal fun VodSort.hint(): String = when (this) {
    VodSort.DEFAULT -> "The order your provider sends"
    VodSort.NAME_AZ -> "Numbers and symbols first, then A to Z"
    VodSort.NAME_ZA -> "Z to A, then numbers and symbols"
    VodSort.NEWEST -> "By release year, newest first"
    VodSort.OLDEST -> "By release year, oldest first"
    VodSort.RECENTLY_ADDED -> "What your provider added most recently"
    VodSort.RATING -> "By your provider's rating, best first"
    VodSort.SHUFFLE -> "A new random order each time you pick it"
}

/** The label for a saved sort ("SHUFFLE:123" -> "Shuffle"), or null when none is saved. */
internal fun sortLabel(saved: String?): String? = VodSort.parse(saved)?.first?.label

/**
 * The sort list (See all, Manage VOD Groups, Settings). [useDefaultLabel]: an extra first choice
 * that goes back to the default sort ("Default (A–Z)"); picking it gives null.
 */
@Composable
internal fun VodSortPicker(
    title: String,
    current: VodSort?,
    useDefaultLabel: String? = null,
    onPick: (VodSort?) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { first.requestFocus() }
    }
    val choices: List<VodSort?> = (if (useDefaultLabel != null) listOf<VodSort?>(null) else emptyList()) + VodSort.entries
    // The current choice gets the focus, so OK alone keeps it.
    val focusIndex = choices.indexOf(current).coerceAtLeast(0)
    NuvioDialog(onDismiss = onDismiss, title = title, width = 480.dp) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            itemsIndexed(choices) { i, sort ->
                val selected = sort == current
                SettingsActionRow(
                    title = sort?.label ?: useDefaultLabel.orEmpty(),
                    subtitle = sort?.hint() ?: "Follows Default Sort Order in Settings",
                    onClick = { onPick(sort) },
                    trailingIcon = if (selected) Icons.Default.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    modifier = if (i == focusIndex) Modifier.focusRequester(first) else Modifier
                )
            }
        }
    }
}
