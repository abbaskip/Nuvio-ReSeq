package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import kotlinx.serialization.Serializable

@Serializable
data class DirectUsenetAutoPlay(
    val immediate: Boolean = false,
    val approved: Boolean = false,
    val fallback: Boolean = false,
    val immediateOrder: List<String> = emptyList(),
    val approvedOrder: List<String> = emptyList(),
    val fallbackOrder: List<String> = emptyList()
)

internal fun List<Stream>.inAutoPlaySourceOrder(order: List<String>): List<Stream> {
    if (order.isEmpty() || none { it.isDirectUsenet }) return this
    val priorities = order.withIndex().associate { (index, name) -> name to index }
    return sortedBy { it.pluginSourceName?.let(priorities::get) ?: priorities[it.addonName] ?: Int.MAX_VALUE }
}
