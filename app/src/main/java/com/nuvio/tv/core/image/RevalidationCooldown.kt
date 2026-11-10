package com.nuvio.tv.core.image

/** A small, bounded cooldown shared by image workers. All expiry and writes use one lock. */
internal class RevalidationCooldown(
    private val durationMs: Long,
    private val maxEntries: Int = 4096,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    init {
        require(durationMs > 0 && maxEntries > 0)
    }

    private val completed = LinkedHashMap<String, Long>()

    @Synchronized
    fun contains(url: String): Boolean {
        expire(nowMs())
        return url in completed
    }

    @Synchronized
    fun record(url: String) {
        val now = nowMs()
        expire(now)
        // Keep write order, including when an existing URL completes again.
        completed.remove(url)
        completed[url] = now
        while (completed.size > maxEntries) completed.remove(completed.keys.first())
    }

    private fun expire(now: Long) {
        val entries = completed.entries.iterator()
        while (entries.hasNext()) {
            if (now - entries.next().value < durationMs) break
            entries.remove()
        }
    }
}
