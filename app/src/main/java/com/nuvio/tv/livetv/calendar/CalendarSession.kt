package com.nuvio.tv.livetv.calendar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/** A cold flow collected only by the resumed Calendar destination. No collector, no work. */
internal fun calendarSession(
    playing: Flow<Boolean>,
    requests: Flow<Boolean>,
    now: () -> Long,
    restore: () -> CalendarPage,
    update: suspend (CalendarPage, Boolean, suspend (CalendarPage) -> Unit) -> Boolean,
    inputsChanged: suspend (CalendarPage) -> Boolean = { false }
): Flow<CalendarDisplay> = channelFlow {
    var page = restore()
    suspend fun publish(status: CalendarStatus) { send(CalendarDisplay(page.items, status)) }
    fun settled() = if (page.retryAt > 0) CalendarStatus.RETRY else CalendarStatus.READY
    publish(if (page.checkedAt == 0L && page.items.isEmpty()) CalendarStatus.OPENING else settled())
    playing.distinctUntilChanged().collectLatest { busy ->
        if (busy) {
            publish(CalendarStatus.PLAYBACK)
        } else requests.onStart { emit(false) }.collect { manual ->
            try {
                if (manual || CalendarPolicy.due(page, now()) || inputsChanged(page)) {
                    publish(CalendarStatus.UPDATING)
                    val partial = update(page, manual) { updated ->
                        page = updated
                        publish(CalendarStatus.UPDATING)
                    }
                    publish(if (partial) CalendarStatus.RETRY else settled())
                } else publish(settled())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(CalendarStatus.RETRY)
            }
        }
    }
}
