package com.nuvio.tv.livetv.calendar

import android.database.SQLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal sealed interface CalendarLookup {
    data class Episodes(val rows: List<CalendarItem>?) : CalendarLookup
    data class Release(val title: CalendarTitle, val rows: List<CalendarItem>?) : CalendarLookup
    data object MoviesUnavailable : CalendarLookup
}

private suspend fun <T> calendarResult(load: suspend () -> T): T? =
    try { load() } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IOException) { null } catch (_: JSONException) { null } catch (_: SQLException) { null }

/** Episodes, regional movie dates and cached fixtures can reach the screen independently. */
internal fun calendarLookups(
    episodes: suspend () -> List<CalendarItem>,
    movies: suspend () -> List<CalendarTitle>,
    listings: suspend (CalendarTitle) -> List<CalendarItem>
): Flow<CalendarLookup> = channelFlow {
    launch {
        val rows = calendarResult(episodes)
        send(CalendarLookup.Episodes(rows))
    }
    val titles = calendarResult(movies)
    if (titles == null) {
        send(CalendarLookup.MoviesUnavailable)
    } else {
        val next = AtomicInteger()
        val offline = AtomicBoolean()
        repeat(minOf(2, titles.size)) {
            launch {
                while (!offline.get()) {
                    val title = titles.getOrNull(next.getAndIncrement()) ?: break
                    val rows = calendarResult {
                        try { listings(title) } catch (e: UnknownHostException) { offline.set(true); throw e }
                    }
                    send(CalendarLookup.Release(title, rows))
                }
            }
        }
    }
}
