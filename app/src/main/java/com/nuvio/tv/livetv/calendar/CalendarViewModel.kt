package com.nuvio.tv.livetv.calendar

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.livetv.data.BackgroundWork
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

internal enum class CalendarStatus { OPENING, READY, UPDATING, PLAYBACK, RETRY }
internal data class CalendarDisplay(val items: List<CalendarItem> = emptyList(), val status: CalendarStatus = CalendarStatus.OPENING)

@HiltViewModel
internal class CalendarViewModel @Inject constructor(
    private val repository: CalendarRepository, private val labels: dagger.Lazy<CalendarEpisodeLabels>,
    val preferences: CalendarPreferences
) : ViewModel() {
    val profile = repository.profile
    private val refresh = MutableSharedFlow<Boolean>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val playing = combine(PlayerSessionTracker.playbackActive, PlayerSessionTracker.livePlayback) { video, live -> video || live }
    fun followedSports(profile: Int) = flow { emitAll(repository.followedSports(profile)) }.flowOn(BackgroundWork.quiet)
    fun refresh() { refresh.tryEmit(true) }
    fun selectFixture(item: CalendarItem) = repository.selectFixture(item)

    suspend fun labelDay(profile: Int, rows: List<CalendarItem>) =
        if (rows.none { it.kind == CalendarKind.EPISODE }) rows
        else withContext(BackgroundWork.quiet) { labels.get().apply(profile, rows) }

    fun releases(profile: Int, region: String, zone: ZoneId, window: CalendarWindow) =
        repository.pages(profile, region, zone, window.first, playing, refresh, window.days).flowOn(BackgroundWork.quiet)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun sports(profile: Int, zone: ZoneId, window: CalendarWindow, selected: StateFlow<Long>, overview: Boolean, allSports: Boolean): Flow<CalendarDisplay> =
        playing.distinctUntilChanged().flatMapLatest { busy ->
            if (busy) flowOf(CalendarDisplay(status = CalendarStatus.PLAYBACK))
            else refresh.onStart { emit(false) }.flatMapLatest { force ->
                repository.sports(profile, zone, window, selected, force, overview, allSports).catch { e ->
                    if (e is CancellationException) throw e
                    emit(CalendarDisplay(status = CalendarStatus.RETRY))
                }
            }
        }.flowOn(BackgroundWork.quiet)
}
