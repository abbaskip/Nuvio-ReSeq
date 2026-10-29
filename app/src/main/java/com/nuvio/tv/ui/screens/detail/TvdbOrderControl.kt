package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.tvdb.*
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.ui.components.NuvioDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvdbSelectorState(val open: Boolean = false, val busy: Boolean = false,
    val seriesId: Long? = null, val orders: List<TvdbOrder> = emptyList(), val error: String? = null)

@HiltViewModel
class TvdbOrderControlViewModel @Inject constructor(
    private val resolver: dagger.Lazy<TvdbEpisodeResolver>,
    private val repository: dagger.Lazy<TvdbOrderRepository>,
    private val store: TvdbOrderStore,
    private val profiles: ProfileManager,
    private val continueWatchingCache: com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val state = MutableStateFlow(TvdbSelectorState())
    private val routeId: String? = savedStateHandle["itemId"]
    private var job: Job? = null
    private var generation = 0L
    init {
        viewModelScope.launch { profiles.activeProfileId.drop(1).collect { close() } }
    }
    private fun aliases(meta: Meta) = listOfNotNull(routeId, meta.id, meta.imdbId)

    fun open(meta: Meta) {
        job?.cancel()
        val request = ++generation
        val profile = profiles.activeProfileId.value
        state.value = TvdbSelectorState(open = true, busy = true, seriesId = meta.episodeOrder?.seriesId)
        job = viewModelScope.launch {
            try {
                val id = resolver.get().seriesId(profile, meta, aliases(meta))
                    ?: error("This series could not be identified on TVDB.")
                val orders = repository.get().available(profile, id).filter { it.type != "default" }
                if (request == generation && profile == profiles.activeProfileId.value) {
                    state.value = TvdbSelectorState(open = true, seriesId = id,
                        orders = (listOf(TvdbOrder("official", "TVDB Standard / Aired")) + orders).distinctBy { it.type })
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request == generation && profile == profiles.activeProfileId.value) {
                    state.value = state.value.copy(busy = false, error = when ((e as? TvdbException)?.reason) {
                        TvdbException.Reason.NO_KEY -> "Configure your TVDB API key in Settings → Integrations → TheTVDB."
                        TvdbException.Reason.AUTHENTICATION -> "TVDB rejected your key. Test the connection in Settings."
                        else -> "Unable to load TVDB orders. Check the connection and try again."
                    })
                }
            }
        }
    }

    fun close() { generation++; job?.cancel(); state.value = TvdbSelectorState() }

    fun select(meta: Meta, type: String?, onChanged: () -> Unit) {
        if (state.value.busy && type != null) return
        job?.cancel()
        val id = state.value.seriesId ?: meta.episodeOrder?.seriesId ?: return
        val profile = profiles.activeProfileId.value
        val request = ++generation
        state.value = state.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                store.save(profile, id, type, aliases(meta))
                if (request == generation && profile == profiles.activeProfileId.value) {
                    continueWatchingCache.invalidateContent(profile, aliases(meta).toSet())
                    close()
                    onChanged()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (request == generation) state.value = state.value.copy(busy = false, error = "Unable to save the episode order.")
            }
        }
    }
}

@Composable
internal fun TvdbOrderControl(meta: Meta, onChanged: () -> Unit,
    viewModel: TvdbOrderControlViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val status = meta.episodeOrder
    val label = when {
        status == null -> "TVDB"
        status.selectedType != status.effectiveType -> "TVDB: ${status.selectedType} → ${status.effectiveType ?: "Provider"}"
        else -> "TVDB: ${status.selectedType}"
    }
    Button(onClick = { viewModel.open(meta) }) {
        Text(label, maxLines = 2)
    }
    if (state.open) {
        NuvioDialog(onDismiss = viewModel::close, title = "Episode order",
            subtitle = meta.episodeOrder?.message ?: "Choose provider episodes or actual TVDB episode metadata", width = 720.dp) {
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "provider") {
                    Button(onClick = { viewModel.select(meta, null, onChanged) }) {
                        Text("${if (meta.episodeOrder == null) "✓ " else ""}Default / Metadata Provider")
                    }
                }
                if (state.busy) item { Text("Loading…") }
                items(state.orders, key = { it.type }) { order ->
                    Button(enabled = !state.busy, onClick = { viewModel.select(meta, order.type, onChanged) }) {
                        Text("${if (meta.episodeOrder?.selectedType == order.type) "✓ " else ""}${order.name}")
                    }
                }
                state.error?.let { error -> item { Text(error) } }
                if (state.error != null) item {
                    Button(onClick = { viewModel.open(meta) }) { Text("Retry") }
                }
                item {
                    Button(onClick = { uri.openUri("https://thetvdb.com") }) { Text("Metadata provided by TheTVDB") }
                }
            }
        }
    }
}
