package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.data.tvdb.TvdbContentCoverage
import com.nuvio.tv.data.tvdb.TvdbEpisodeOrder
import com.nuvio.tv.data.tvdb.TvdbOrderRepository
import com.nuvio.tv.data.tvdb.TvdbOrderStore
import com.nuvio.tv.domain.model.EpisodeCoordinates
import javax.inject.Inject

/** Keep provider coordinates in the snapshot. A profile's current TVDB order is a display choice. */
internal class CalendarEpisodeLabels @Inject constructor(
    private val selections: TvdbOrderStore, private val orders: TvdbOrderRepository
) {
    suspend fun apply(profile: Int, rows: List<CalendarItem>): List<CalendarItem> {
        val labels = mutableMapOf<String, String>()
        for ((series, episodes) in rows.filter { it.kind == CalendarKind.EPISODE }.groupBy { it.contentId }) {
            if (series == null) continue
            val selection = selections.selected(profile, listOf(series))
            val order = selection?.let { orders.cachedOrder(it.seriesId, it.orderType) }
            val mapper = order?.let(::CalendarOrderLabels)
            for (row in episodes) {
                if (row.season == null || row.episode == null) continue
                val aired = EpisodeCoordinates(row.season, row.episode)
                val display = if (selection == null) listOf(aired) else mapper?.coordinates(row.detail, row.airedDate).orEmpty()
                if (display.isNotEmpty()) labels[row.key] = display.joinToString(" / ") { "S" + it.season + " E" + it.episode }
            }
        }
        return rows.map { row -> labels[row.key]?.let {
            row.copy(detail = listOf(it, row.detail).filter(String::isNotBlank).joinToString(" · "))
        } ?: row }
    }
}

internal class CalendarOrderLabels(order: TvdbEpisodeOrder) {
    private val coverage = TvdbContentCoverage(order)
    private val byPart = order.episodes.flatMap { episode ->
        coverage.byId[episode.id].orEmpty().map { it.id to episode.coordinates }
    }.groupBy({ it.first }, { it.second })

    fun coordinates(title: String, date: String?): List<EpisodeCoordinates> {
        val parts = coverage.match(title, date)
        if (parts.isEmpty() || parts.any { it.id !in byPart }) return emptyList()
        return parts.flatMap { byPart[it.id].orEmpty() }.distinct()
            .sortedWith(compareBy<EpisodeCoordinates> { it.season }.thenBy { it.episode })
    }
}
