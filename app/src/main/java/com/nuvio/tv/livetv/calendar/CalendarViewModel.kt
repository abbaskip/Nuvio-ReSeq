package com.nuvio.tv.livetv.calendar

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.livetv.data.BackgroundWork
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

internal enum class CalendarStatus { OPENING, READY, UPDATING, PLAYBACK, RETRY }

internal data class CalendarDisplay(
    val items: List<CalendarItem> = emptyList(),
    val status: CalendarStatus = CalendarStatus.OPENING
)

@HiltViewModel
internal class CalendarViewModel @Inject constructor(
    private val repository: CalendarRepository, private val labels: dagger.Lazy<CalendarEpisodeLabels>
) : ViewModel() {
    val profile = repository.profile
    private val refresh = MutableSharedFlow<Boolean>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun refresh() { refresh.tryEmit(true) }

    suspend fun labelDay(profile: Int, rows: List<CalendarItem>) =
        if (rows.none { it.kind == CalendarKind.EPISODE }) rows
        else withContext(BackgroundWork.quiet) { labels.get().apply(profile, rows) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun display(profile: Int, region: String, zone: ZoneId, week: Long): Flow<CalendarDisplay> {
        val playing = combine(PlayerSessionTracker.playbackActive, PlayerSessionTracker.livePlayback) { video, live -> video || live }
        val pages = repository.pages(profile, region, zone, week, playing, refresh)
        val sport = playing.distinctUntilChanged().flatMapLatest { busy ->
            if (busy) emptyFlow() else refresh.onStart { emit(false) }.flatMapLatest { repository.sports(profile, zone, week) }
        }.onStart { emit(emptyList()) }
        return channelFlow {
            val agenda = combine(pages, sport) { state, fixtures ->
                if (fixtures.isEmpty()) state else {
                    val today = LocalDate.now(zone)
                    state.copy(items = CalendarPolicy.displayed(state.items + fixtures, CalendarWindow(week, week + 6), today.toEpochDay()))
                }
            }.shareIn(this, SharingStarted.Lazily, replay = 1)
            val names = agenda.map { state -> state.items.mapNotNullTo(mutableSetOf()) { it.channelKey } }
                .distinctUntilChanged().flatMapLatest { keys ->
                    if (keys.isEmpty()) flowOf(emptyMap()) else repository.allowedNames(keys)
                }.onStart { emit(emptyMap()) }
            combine(agenda, names) { state, allowed ->
                state.copy(items = state.items.mapNotNull { item ->
                    if (item.channelKey == null) item else allowed[item.channelKey]?.let { item.copy(detail = it) }
                })
            }.distinctUntilChanged().collect { send(it) }
        }.flowOn(BackgroundWork.quiet)
    }
}
