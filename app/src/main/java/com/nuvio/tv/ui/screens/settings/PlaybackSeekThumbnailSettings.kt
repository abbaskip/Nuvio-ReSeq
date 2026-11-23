package com.nuvio.tv.ui.screens.settings

import android.text.format.DateFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
import com.nuvio.tv.livetv.thumbs.SeekThumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** Settings > Playback > Seek Thumbnails. */
@Composable
internal fun PlaybackSeekThumbnailSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SeekThumbSettings.load(context) } }
    val s by SeekThumbSettings.values.collectAsState()
    val scope = rememberCoroutineScope()
    val lowMemoryBox = remember { SeekThumbSettings.isLowMemoryDevice(context) }
    var savedBytes by remember { mutableStateOf<Long?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, s.enabled) {
        savedBytes = withContext(Dispatchers.IO) { runCatching { SeekThumbs.savedBytes(context) }.getOrNull() }
    }
    fun update(change: (SeekThumbSettings.Values) -> SeekThumbSettings.Values) = SeekThumbSettings.update(context, change)

    SettingsToggleRow(
        title = stringResource(R.string.iptv_thumbs_title),
        subtitle = stringResource(R.string.iptv_thumbs_subtitle),
        checked = s.enabled,
        onToggle = { update { it.copy(enabled = !it.enabled) } }
    )
    var saveError by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }
    SettingsToggleRow(
        title = stringResource(R.string.seekr_only),
        subtitle = stringResource(R.string.seekr_only_description),
        checked = s.seekrOnly,
        enabled = s.enabled,
        onToggle = { update { it.copy(seekrOnly = !it.seekrOnly) } }
    )
    SettingsActionRow(
        title = stringResource(R.string.seekr_api_key),
        subtitle = stringResource(R.string.seekr_key_description),
        value = if (s.apiKey.isBlank()) null else "••••••••",
        onClick = { editingKey = true },
        enabled = s.enabled
    )
    if (editingKey) {
        com.nuvio.tv.livetv.ui.TextInputDialog(
            title = stringResource(R.string.seekr_api_key), initial = s.apiKey,
            hint = "sk_live_…", confirmLabel = stringResource(R.string.action_save),
            password = true, onDismiss = { editingKey = false },
            onConfirm = { key ->
                editingKey = false
                scope.launch {
                    saveError = withContext(Dispatchers.IO) {
                        runCatching { update { it.copy(apiKey = key.trim()) } }.isFailure
                    }
                }
            }
        )
    }
    if (saveError) SettingsNote(stringResource(R.string.seekr_key_save_error), tone = SettingsNoteTone.Warning)
    SettingsNote(stringResource(R.string.iptv_thumbs_works_on))
    if (lowMemoryBox && !s.seekrOnly) {
        SettingsNote(
            stringResource(R.string.iptv_thumbs_low_memory_note),
            tone = SettingsNoteTone.Warning
        )
    }
    SettingsToggleRow(
        title = stringResource(R.string.iptv_thumbs_include_4k),
        subtitle = stringResource(R.string.iptv_thumbs_include_4k_desc),
        checked = s.include4k,
        enabled = s.enabled && !s.seekrOnly,
        onToggle = { update { it.copy(include4k = !it.include4k) } }
    )
    SettingsActionRow(
        title = stringResource(R.string.iptv_thumbs_when_to_make),
        subtitle = stringResource(whenDetailRes(s.whenToMake)),
        value = stringResource(whenLabelRes(s.whenToMake)),
        enabled = s.enabled && !s.seekrOnly,
        leadingIcon = Icons.Default.Schedule,
        onClick = {
            val all = SeekThumbSettings.When.entries
            update { it.copy(whenToMake = all[(all.indexOf(it.whenToMake) + 1) % all.size]) }
        }
    )
    SettingsActionRow(
        title = stringResource(R.string.iptv_thumbs_storage_limit),
        subtitle = stringResource(R.string.iptv_thumbs_storage_limit_desc),
        value = stringResource(R.string.iptv_thumbs_size_mb, s.storageMb.toString()),
        enabled = s.enabled && !s.seekrOnly,
        leadingIcon = Icons.Default.SdStorage,
        onClick = {
            val all = SeekThumbSettings.STORAGE_CHOICES
            update { it.copy(storageMb = all[(all.indexOf(it.storageMb) + 1) % all.size]) }
        }
    )
    SettingsActionRow(
        title = stringResource(R.string.iptv_thumbs_clear),
        subtitle = stringResource(R.string.iptv_thumbs_clear_desc),
        value = savedBytes?.let { formatSize(it) },
        leadingIcon = Icons.Default.DeleteSweep,
        onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { SeekThumbs.clearAll(context) } }
                refresh++
            }
        }
    )
    if (s.lastLowMemoryAt > 0L) {
        val date = Date(s.lastLowMemoryAt)
        SettingsNote(
            stringResource(
                R.string.iptv_thumbs_last_paused,
                DateFormat.getMediumDateFormat(context).format(date),
                DateFormat.getTimeFormat(context).format(date)
            )
        )
    }
}

@Composable
private fun formatSize(bytes: Long): String = when {
    bytes < 1024L * 1024L -> stringResource(R.string.iptv_thumbs_size_kb, (bytes / 1024L).toString())
    else -> stringResource(R.string.iptv_thumbs_size_mb, "%.1f".format(bytes / (1024.0 * 1024.0)))
}

private fun whenLabelRes(w: SeekThumbSettings.When): Int = when (w) {
    SeekThumbSettings.When.SEEKING -> R.string.iptv_thumbs_when_seeking
    SeekThumbSettings.When.WATCHING -> R.string.iptv_thumbs_when_watching
    SeekThumbSettings.When.RIGHT_AWAY -> R.string.iptv_thumbs_when_right_away
}

private fun whenDetailRes(w: SeekThumbSettings.When): Int = when (w) {
    SeekThumbSettings.When.SEEKING -> R.string.iptv_thumbs_when_seeking_desc
    SeekThumbSettings.When.WATCHING -> R.string.iptv_thumbs_when_watching_desc
    SeekThumbSettings.When.RIGHT_AWAY -> R.string.iptv_thumbs_when_right_away_desc
}
