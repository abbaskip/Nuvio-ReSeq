package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.sync.DrivePlaybackProfile
import com.nuvio.tv.livetv.sync.LiveTvDriveSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class PlaybackRestoreState(val loading: Boolean = false, val profiles: List<DrivePlaybackProfile> = emptyList(),
    val message: String? = null)

@HiltViewModel
internal class MobilePlaybackRestoreModel @Inject constructor(private val drive: LiveTvDriveSync) : ViewModel() {
    val state = MutableStateFlow(PlaybackRestoreState())
    fun load() = run { PlaybackRestoreState(profiles = drive.previewPlaybackProfiles()) }

    fun restore(source: DrivePlaybackProfile, id: Int, name: String) = run {
        drive.restorePlaybackProfile(source, id, name)
        PlaybackRestoreState(message = "Restored ${source.summary()} to $name.")
    }

    private fun run(operation: suspend () -> PlaybackRestoreState) {
        if (state.value.loading) return
        state.value = PlaybackRestoreState(loading = true)
        viewModelScope.launch {
            state.value = try { operation() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { PlaybackRestoreState(message = failure.localizedMessage ?: "Couldn’t read this backup.") }
        }
    }
}

private fun DrivePlaybackProfile.summary(): String =
    "${mixes.size} " + (if (mixes.size == 1) "mix" else "mixes") + " and ${shuffle.size} " +
        (if (shuffle.size == 1) "shuffle setting" else "shuffle settings")

@Composable
internal fun MobilePlaybackRestore(drive: LiveTvDriveSync, vm: MobilePlaybackRestoreModel = hiltViewModel(),
    catalog: MobileCatalogViewModel = hiltViewModel(), close: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val active by catalog.profiles.activeProfileId.collectAsStateWithLifecycle()
    val profiles by catalog.profiles.profiles.collectAsStateWithLifecycle()
    val email by drive.state.collectAsStateWithLifecycle()
    val destination = profiles.firstOrNull { it.id == active } ?: return
    var chosen by remember(active, email.email) { mutableStateOf<Int?>(null) }
    LaunchedEffect(active, email.email) { vm.load() }
    val selected = state.profiles.firstOrNull { it.id == chosen }
    TouchSheet(close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionTitle("Restore TV Mix and Shuffle")
            when {
                state.loading -> LinearProgressIndicator()
                state.message != null -> Label(state.message.orEmpty())
                state.profiles.isEmpty() -> Label("This backup has no saved TV Mix or Shuffle profiles.")
                selected == null -> {
                    Label("Choose a profile from the backup to copy into ${destination.name}.")
                    state.profiles.forEach { source ->
                        if (source.mixes.isEmpty() && source.shuffle.isEmpty()) Label("${source.name} · ${source.summary()}", small = true)
                        else SettingsLink("${source.name} · ${source.summary()}") { chosen = source.id }
                    }
                }
                else -> {
                    Label("Copy ${selected.summary()} from ${selected.name} into ${destination.name}? Matching saved items will be updated.")
                    if (selected.mixes.isNotEmpty()) Label(selected.mixes.joinToString(" · ") { it.name }, small = true)
                    Action("Restore to ${destination.name}") { vm.restore(selected, active, destination.name) }
                    Action("Choose another profile") { chosen = null }
                }
            }
            Action("Close", onClick = close)
        }
    }
}
