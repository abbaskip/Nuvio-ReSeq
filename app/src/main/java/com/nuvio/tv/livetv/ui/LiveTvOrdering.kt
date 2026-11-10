package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.LiveUserState

/** Guide IDs gain a playlist prefix when a second source is enabled; preferences must not change. */
internal fun groupPreferenceKey(id: String, sourceId: String?): String =
    if (sourceId == null || id.startsWith("__") || id.startsWith(ChannelGroup.CUSTOM_PREFIX) ||
        id.startsWith("$sourceId::")) id else "$sourceId::$id"

internal fun LiveUserState.isFavoriteGroup(group: ChannelGroup): Boolean =
    groupPreferenceKey(group.id, group.sourceId) in favoriteGroups || group.id in favoriteGroups

/** The group editor uses this same unpinned order. Stable sorts keep unranked groups in
 * first-channel order and leave saved group moves ahead of the provider defaults. */
internal fun orderLiveGroups(
    groups: List<ChannelGroup>,
    savedOrder: List<String>,
    mode: String,
    playlistHeadings: Boolean,
    providerRanks: Map<String, Int>
): List<ChannelGroup> {
    val playlists = linkedMapOf<String, Int>()
    for (group in groups) group.sourceId?.let { playlists.putIfAbsent(it, playlists.size) }
    fun playlist(group: ChannelGroup) = playlists[group.sourceId] ?: -1

    if (mode == "name") return groups.sortedWith(compareBy(
        { if (it.sourceId == null) -1 else if (playlistHeadings) playlist(it) else 0 },
        { it.title.lowercase() }
    ))
    val defaults = if (providerRanks.isEmpty()) groups else groups.sortedWith(
        compareBy(::playlist, { if (it.sourceId == null) 0 else providerRanks[it.id] ?: Int.MAX_VALUE })
    )
    if (mode == "playlist" || savedOrder.isEmpty()) return defaults
    val saved = savedOrder.withIndex().associate { (index, id) -> id to index }
    return defaults.sortedBy { saved[groupPreferenceKey(it.id, it.sourceId)] ?: saved[it.id] ?: Int.MAX_VALUE }
}

/** Read older saved orders without rewriting preferences or guessing between duplicate group names. */
internal fun LiveUserState.channelOrderFor(id: String, sourceId: String?): List<String>? =
    channelOrder[groupPreferenceKey(id, sourceId)] ?: channelOrder[id]

/** Movement is relative to the first/last selected row, never selection click order. */
internal enum class OrderMove { UP, DOWN, TOP, BOTTOM }

internal fun <T> moveOrderedItems(order: List<T>, selected: Set<T>, move: OrderMove): List<T> {
    val block = order.filter { it in selected }
    if (block.isEmpty()) return order
    val rest = order.filterNot { it in selected }
    val first = order.indexOfFirst { it in selected }
    val last = order.indexOfLast { it in selected }
    val insertion = when (move) {
        OrderMove.TOP -> 0
        OrderMove.BOTTOM -> rest.size
        OrderMove.UP -> (first - 1).coerceAtLeast(0)
        OrderMove.DOWN -> (last - block.size + 2).coerceAtMost(rest.size)
    }
    return rest.take(insertion) + block + rest.drop(insertion)
}

/** Replace only this playlist's slots; other playlists keep their positions. */
internal fun <T> replaceOrderScope(order: List<T>, scoped: List<T>): List<T> {
    val members = scoped.toSet()
    val replacement = scoped.iterator()
    return order.map { if (it in members) replacement.next() else it }
}

internal data class OrderEntry(val id: String, val title: String)
internal data class OrderRequest(
    val title: String,
    val entries: List<OrderEntry>,
    val initialId: String,
    val save: (List<String>) -> Unit
)

internal data class OrderSortWarning(val scope: String, val proceed: () -> Unit)
