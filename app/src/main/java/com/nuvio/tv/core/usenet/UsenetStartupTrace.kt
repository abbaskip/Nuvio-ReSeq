package com.nuvio.tv.core.usenet

internal data class UsenetMemoryProfile(val cacheBytes: Int = 8 * 1024 * 1024, val window: Int = 0, val aheadBytes: Int = 0) {
    companion object { val LOW = UsenetMemoryProfile() }
}

/** Only fixed stage labels and numeric counters reach logcat. Never include exception text. */
internal class UsenetStartupTrace(private val log: (String) -> Unit = { android.util.Log.i("UsenetStartup", it); Unit }) {
    private val start = System.nanoTime()
    private val marks = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Long>()
    private var reported = false
    private var kind = "direct"
    @Synchronized fun archive(kind: String, volumes: Int) {
        if (this.kind != "obfuscated") this.kind = kind
        counts["volumes"] = volumes.toLong()
    }
    @Synchronized fun mark(stage: String) { marks.putIfAbsent(stage, (System.nanoTime() - start) / 1_000_000) }
    @Synchronized fun add(counter: String, value: Long = 1) { counts[counter] = (counts[counter] ?: 0) + value }
    @Synchronized fun finish(success: Boolean) {
        if (reported) return
        reported = true
        log("total=${(System.nanoTime() - start) / 1_000_000}ms success=$success kind=$kind " +
            marks.entries.joinToString(" ") { "${it.key}=${it.value}ms" } + " " +
            counts.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }
    fun firstGet(waitNanos: Long) { log("first_get_wait=${waitNanos / 1_000_000}ms") }
}
