package com.nuvio.tv.livetv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.player.LiveTvPlayerHooks
import com.nuvio.tv.livetv.ui.LiveTvSession
import com.nuvio.tv.livetv.ui.buildUi
import com.nuvio.tv.livetv.ui.sameForLists
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class LiveTvHomeRowViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    prefs: LiveTvPreferences,
    channelStore: LiveTvChannelStore,
    private val session: LiveTvSession,
    private val playback: LiveTvPlaybackController,
    private val hooks: dagger.Lazy<LiveTvPlayerHooks>
) : ViewModel() {
    data class HomeRowEntry(val channel: LiveChannel, val now: EpgProgram?, val next: EpgProgram?)
    val playbackState = playback.state
    val now = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), System.currentTimeMillis())

    private fun group(s: LiveTvSettings) = when (s.homeRowSource) {
        "favorites", "" -> ChannelGroup.FAVORITES
        "recent" -> ChannelGroup.RECENT
        else -> s.homeRowSource
    }

    private val homeRowChannels = combine(channelStore.displayChannels,
        prefs.userState.distinctUntilChanged(::sameForLists), prefs.settings, ParentalControls.unlocked
    ) { channels, user, s, _ ->
        val rows = if (s.homeRowEnabled && channels.isNotEmpty())
            repository.buildUi(channels, user, s, group(s), "", true, exactGroup = true).channels
            else emptyList()
        rows to s
    }.onEach { (rows, s) ->
        val limit = if (s.homeRowLimit > 0) s.homeRowLimit * 3 else 200
        val keys = rows.take(limit).map { it.key }
        session.homePriorityKeys.value = keys
        repository.loadChannelsNow(keys)
    }.flowOn(Dispatchers.Default).shareIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

    /** The collapsed row only needs to know whether its Up hint can lead to a card. */
    val available = combine(homeRowChannels, repository.programs, now) { (channels, settings), programs, time ->
        if (!settings.homeRowHidePlaceholders) channels.isNotEmpty() else {
            val guideTime = time - settings.epgOffsetMinutes * 60_000L
            channels.any { channel ->
                val current = programs[channel.key]?.firstOrNull { guideTime >= it.startMs && guideTime < it.stopMs }
                current != null && !LiveTvRepository.isPlaceholderTitle(current.title)
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val homeRow: StateFlow<List<HomeRowEntry>> = combine(
        homeRowChannels,
        repository.programs,
        now
    ) { (list, s), progs, nowMs ->
        if (list.isEmpty()) return@combine emptyList()
        val offset = s.epgOffsetMinutes * 60_000L
        val guideNow = nowMs - offset
        fun shifted(p: EpgProgram?) = p?.let { if (offset == 0L) it else it.copy(startMs = it.startMs + offset, stopMs = it.stopMs + offset) }
        var entries = list.map { ch ->
            val l = progs[ch.key].orEmpty()
            val cur = l.firstOrNull { guideNow >= it.startMs && guideNow < it.stopMs }
            val nxt = l.firstOrNull { it.startMs >= (cur?.stopMs ?: guideNow) }
            HomeRowEntry(ch, shifted(cur), shifted(nxt))
        }
        if (s.homeRowHidePlaceholders) {
            entries = entries.filter { e ->
                e.now != null && !com.nuvio.tv.livetv.data.LiveTvRepository.isPlaceholderTitle(e.now.title)
            }
        }
        entries = when (s.homeRowSort) {
            "number" -> entries.sortedBy { it.channel.number }
            "ending" -> entries.sortedBy { it.now?.stopMs ?: Long.MAX_VALUE }
            else -> entries
        }
        if (s.homeRowLimit > 0) entries.take(s.homeRowLimit) else entries
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), emptyList())


    init {
        repository.ensureLoaded()
        repository.refreshIfStale()
    }

    suspend fun keepRowCurrent() {
        now.collect { time -> repository.ensureRange(time - 3 * 3_600_000L, time + 12 * 3_600_000L) }
    }

    fun playFromHome(channel: LiveChannel, settings: LiveTvSettings) {
        val group = group(settings)
        session.currentGroupId.value = group
        hooks.get().preview(playback, channel, group)
    }

    fun markFullscreenOpened() { session.returningFromFullscreen = true }
}
