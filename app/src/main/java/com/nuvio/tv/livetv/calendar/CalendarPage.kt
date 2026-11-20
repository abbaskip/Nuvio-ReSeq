package com.nuvio.tv.livetv.calendar

import com.squareup.moshi.JsonClass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

// Schedule kinds and the week view are adapted from homelessbrian/NuvioPlusIPTV, f85adc128.
@JsonClass(generateAdapter = false)
internal enum class CalendarKind { EPISODE, CINEMA, DIGITAL, DISC, SPORT, GUIDE }

@JsonClass(generateAdapter = true)
internal data class CalendarItem(
    val key: String,
    val source: String,
    val title: String,
    val day: Long,
    val kind: CalendarKind,
    val detail: String = "",
    val time: Long? = null,
    val contentId: String? = null,
    val channelKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val airedDate: String? = null
)

internal data class CalendarTitle(
    val id: String, val title: String, val movie: Boolean, val imdb: String?, val tmdb: Int?,
    val lastActivity: Long
) {
    val key get() = (if (movie) "movie/" else "series/") + id
}

@JsonClass(generateAdapter = true)
internal data class CalendarPage(
    val version: Int = 1,
    val region: String,
    val zone: String,
    val checkedAt: Long = 0,
    val checked: Map<String, Long> = emptyMap(),
    val items: List<CalendarItem> = emptyList(),
    val remaining: Int = 0,
    val retryAt: Long = 0,
    val failedTitles: List<String> = emptyList(),
    val guideRevision: String? = null,
    val guideTitles: List<String> = emptyList(),
    val guideDay: Long = 0,
    @com.squareup.moshi.Json(ignore = true) val generation: Long = 0
)

internal data class CalendarWindow(val first: Long, val last: Long) {
    operator fun contains(day: Long): Boolean = day in first..last
    companion object {
        fun around(today: LocalDate, locale: Locale = Locale.getDefault()): CalendarWindow {
            val first = today.with(WeekFields.of(locale).dayOfWeek(), 1).toEpochDay()
            return CalendarWindow(first - 7, first + 34)
        }
    }
}

internal object CalendarPolicy {
    const val FRESH_MS = 6 * 60 * 60_000L
    const val RETRY_MS = 15 * 60_000L
    const val BATCH = 12
    const val MAX_ITEMS = 1200
    const val MAX_CHECKS = 3000
    fun fresh(at: Long, now: Long) = at > 0 && now - at in 0 until FRESH_MS
    fun due(page: CalendarPage, now: Long) =
        !fresh(page.checkedAt, now) || page.retryAt > 0 && now >= page.retryAt

    fun work(titles: List<CalendarTitle>, checks: Map<String, Long>, now: Long): List<CalendarTitle> =
        titles.filterNot { fresh(checks[it.key] ?: 0L, now) }
            .sortedWith(compareBy<CalendarTitle> { checks[it.key] ?: 0L }.thenByDescending { it.lastActivity })

    fun displayed(items: Collection<CalendarItem>, window: CalendarWindow, today: Long): List<CalendarItem> =
        items.asSequence().filter { it.day in window }.distinctBy { it.key }
            .sortedWith(compareBy<CalendarItem> { kotlin.math.abs(it.day - today) }
                .thenBy { it.day }.thenBy { it.time ?: Long.MAX_VALUE }.thenBy { it.key })
            .take(MAX_ITEMS).sortedWith(compareBy<CalendarItem> { it.day }.thenBy { it.time ?: Long.MAX_VALUE }
                .thenBy { it.title }.thenBy { it.key }).toList()
}

internal fun Long.calendarDay(zone: ZoneId): Long = Instant.ofEpochMilli(this).atZone(zone).toLocalDate().toEpochDay()
