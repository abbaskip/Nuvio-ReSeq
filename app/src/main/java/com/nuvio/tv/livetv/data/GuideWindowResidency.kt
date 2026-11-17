package com.nuvio.tv.livetv.data

/** Only the requested channel windows live in memory; SQLite owns the complete schedule. */
internal class GuideWindowResidency(private val limit: Int = 600) {
    init { require(limit>0) }
    private val recent = LinkedHashSet<String>()
    @Synchronized fun warm(keys: Iterable<String>) {
        for(key in keys) if(recent.size < limit) recent.add(key) else break
    }
    @Synchronized fun request(keys: Iterable<String>) {
        keys.distinct().take(limit).forEach { recent.remove(it);recent.add(it) }
        while(recent.size>limit) recent.remove(recent.first())
    }
    @Synchronized fun restrict(available: Set<String>) { recent.retainAll(available) }
    @Synchronized fun keys(): List<String> = recent.toList()
    @Synchronized fun <T> retain(values: Map<String,T>): Map<String,T> {
        warm(values.keys)
        return values.filterKeys { it in recent }
    }
}
