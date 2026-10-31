package com.nuvio.tv.livetv.data

import java.util.Collections

/**
 * A thread-safe map that keeps only the [maxSize] most recently used entries, so remembered
 * lookups (posters, matches, details) can't grow without limit over a long session.
 */
internal fun <K, V> boundedCache(maxSize: Int): MutableMap<K, V> =
    Collections.synchronizedMap(object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    })

/** The set version of [boundedCache]. */
internal fun <K> boundedSet(maxSize: Int): MutableSet<K> =
    Collections.newSetFromMap(boundedCache<K, Boolean>(maxSize))
