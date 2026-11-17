package com.nuvio.tv.livetv.sports

/** Retention bounds, not scan limits: later, better-ranked candidates can replace earlier ones. */
internal data class SportsMatchLimits(
    val perFixture: Int = 1_024,
    val queryBatch: Int = 4_000,
    val guideEvents: Int = 4_096,
    val guideCandidates: Int = 65_536,
    val totalMatches: Int = 65_536
) {
    init { require(listOf(perFixture, queryBatch, guideEvents, guideCandidates, totalMatches).all { it > 0 }) }
    companion object {
        val DEFAULT = SportsMatchLimits()
        val UNCAPPED = SportsMatchLimits(Int.MAX_VALUE, 4_000, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
    }
}

/** A bounded indexed set. Updates remove their old tree node; there are no stale heap entries. */
internal class RankedCandidates<K, V>(private val limit: Int, comparator: Comparator<V>,
    private val removed: (K, V) -> Unit = { _, _ -> }) {
    private class Entry<K,V>(val key: K, val value: V, val ordinal: Long)
    private var sequence = 0L
    private val order = Comparator<Entry<K,V>> { a, b ->
        comparator.compare(a.value,b.value).takeIf { it != 0 } ?: a.ordinal.compareTo(b.ordinal)
    }
    private val entries = HashMap<K,Entry<K,V>>()
    private val sorted = java.util.TreeSet(order)
    val size get() = entries.size
    val values get() = entries.values.map { it.value }
    val encounterValues get() = entries.values.sortedBy { it.ordinal }.map { it.value }
    operator fun get(key: K): V? = entries[key]?.value
    fun worst(): V? = sorted.lastOrNull()?.value
    fun wouldAccept(key: K, value: V): Boolean = key in entries || size < limit ||
        order.compare(Entry(key,value,sequence),sorted.last()) < 0
    fun offer(key: K, value: V): Boolean {
        if (!wouldAccept(key,value)) return false
        val prior = entries[key]
        if (prior != null) sorted.remove(prior)
        else if (size == limit) remove(sorted.last().key)
        val entry = Entry(key,value,prior?.ordinal ?: sequence++)
        entries[key]=entry; sorted.add(entry)
        return true
    }
    fun remove(key: K, notify: Boolean = true): V? {
        val entry=entries.remove(key) ?: return null
        sorted.remove(entry)
        if (notify) removed(key,entry.value)
        return entry.value
    }
    fun removeWorst() { sorted.lastOrNull()?.let { remove(it.key) } }
}
