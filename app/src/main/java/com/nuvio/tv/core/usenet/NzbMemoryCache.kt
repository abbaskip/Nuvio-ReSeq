package com.nuvio.tv.core.usenet

/** Passwords and signed request keys stay in process memory. No disk persistence. */
internal class NzbMemoryCache(
    private val limit: () -> Long = { if (UsenetMemoryProfile.current == UsenetMemoryProfile.NORMAL) 12L * 1024 * 1024 else 4L * 1024 * 1024 },
    private val now: () -> Long = System::nanoTime
) {
    private data class Key(val url: String, val headers: Map<String, String>)
    private data class Entry(val document: NzbDocument, val bytes: Long, val inserted: Long)
    private val entries = LinkedHashMap<Key, Entry>(4, 0.75f, true)
    private val ttl = java.util.concurrent.TimeUnit.MINUTES.toNanos(30)
    private var epoch = 0L
    @Synchronized fun generation() = epoch
    @Synchronized fun clear() { entries.clear(); epoch++ }
    @Synchronized fun retainedBytes() = entries.values.sumOf { it.bytes }
    @Synchronized fun get(url: String, headers: Map<String, String>): NzbDocument? {
        entries.entries.removeAll { now() - it.value.inserted >= ttl }
        trim()
        return entries[Key(url, headers)]?.document
    }
    @Synchronized fun put(url: String, headers: Map<String, String>, document: NzbDocument, generation: Long = epoch) {
        if (generation != epoch) return // Pressure also invalidates in-flight insertions.
        val key = Key(url, headers.toMap())
        entries.remove(key)
        val bytes = document.retainedBytes + 256 + url.length * 2L + headers.entries.sumOf { 128L + (it.key.length + it.value.length) * 2L }
        if (bytes <= limit()) entries[key] = Entry(document, bytes, now())
        trim()
    }
    private fun trim() {
        while (entries.isNotEmpty() && (entries.size > 4 || retainedBytes() > limit())) entries.remove(entries.keys.first())
    }
}
