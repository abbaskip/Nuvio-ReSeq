package com.nuvio.tv.core.usenet

/** Passwords and signed request keys stay in process memory. No disk persistence. */
internal class NzbMemoryCache(private val now: () -> Long = System::nanoTime) {
    private data class Key(val url: String, val headers: Map<String, String>)
    private data class Entry(val document: NzbDocument, val bytes: Int, val inserted: Long)
    private val entries = LinkedHashMap<Key, Entry>(4, 0.75f, true)
    private val ttl = java.util.concurrent.TimeUnit.MINUTES.toNanos(30)
    @Synchronized fun get(url: String, headers: Map<String, String>): NzbDocument? {
        entries.entries.removeAll { now() - it.value.inserted >= ttl }
        return entries[Key(url, headers)]?.document
    }
    @Synchronized fun put(url: String, headers: Map<String, String>, document: NzbDocument, bytes: Int) {
        entries[Key(url, headers.toMap())] = Entry(document, bytes, now())
        while (entries.size > 4 || entries.values.sumOf { it.bytes.toLong() } > NzbManifest.MAX_BYTES) {
            entries.remove(entries.keys.first())
        }
    }
}
