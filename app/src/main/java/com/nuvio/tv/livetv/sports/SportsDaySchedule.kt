package com.nuvio.tv.livetv.sports

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Query every provider calendar day touched by the TV's selected local day. */
internal object SportsDaySchedule {
    fun dates(start: Long, end: Long, zone: ZoneId, pattern: String): List<String> {
        require(end > start)
        val first = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate()
        val format = DateTimeFormatter.ofPattern(pattern, java.util.Locale.US)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.map(format::format).toList()
    }

    fun freshest(events: List<SportsEvent>): List<SportsEvent> = events.groupBy { it.id }
        .values.map { versions -> versions.maxBy { it.updatedAt ?: 0L } }
}
