package com.nuvio.tv.livetv.calendar

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.livetv.data.BackgroundWork
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

internal enum class CalendarStatus { OPENING, READY, UPDATING, PLAYBACK, RETRY }

internal data class CalendarDisplay(
    val items: List<CalendarItem> = emptyList(),
    val status: CalendarStatus = CalendarStatus.OPENING,
    val remaining: Int = 0
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
    fun display(profile: Int, region: String, zone: ZoneId): Flow<CalendarDisplay> {
        val playing = combine(PlayerSessionTracker.playbackActive, PlayerSessionTracker.livePlayback) { video, live -> video || live }
        val pages = calendarSession(playing, refresh, System::currentTimeMillis,
            restore = { repository.restore(profile, region, zone) },
            update = { page, manual, publish -> repository.update(profile, page, manual, publish) })
        return pages.transformLatest { state ->
            val keys = state.items.mapNotNullTo(mutableSetOf()) { it.channelKey }
            if (keys.isEmpty()) emit(state) else {
                emit(state.copy(items = state.items.filter { it.channelKey == null }))
                repository.allowedNames(keys).collect { names ->
                    emit(state.copy(items = state.items.mapNotNull { item ->
                        if (item.channelKey == null) item else names[item.channelKey]?.let { item.copy(detail = it) }
                    }))
                }
            }
        }.flowOn(BackgroundWork.quiet)
    }
}
