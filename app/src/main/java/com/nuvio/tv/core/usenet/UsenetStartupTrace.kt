package com.nuvio.tv.core.usenet

internal data class UsenetMemoryProfile(val cacheBytes: Int, val window: Int, val aheadBytes: Int) {
    companion object {
        val LOW = UsenetMemoryProfile(16 * 1024 * 1024, 6, 8 * 1024 * 1024)
        val NORMAL = UsenetMemoryProfile(32 * 1024 * 1024, 12, 16 * 1024 * 1024)
        @Volatile var current = LOW
        fun init(context: android.content.Context) {
            val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            current = if (manager.isLowRamDevice || manager.memoryClass < 192) LOW else NORMAL
        }
    }
}

/** Only fixed stage labels and numeric counters reach logcat. Never include exception text. */
internal class UsenetStartupTrace(private val log: (String) -> Unit = { android.util.Log.i("UsenetStartup", it); Unit }) {
    private val start = System.nanoTime()
    private val marks = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Long>()
    private var reported = false
    private var kind = "direct"
    private var connectionAllowance: String? = null
    @Synchronized fun connections(limit: Int, fromAddon: Boolean) {
        connectionAllowance = "conns=$limit (${if (fromAddon) "addon" else "default"})"
    }
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
            (connectionAllowance?.let { "$it " } ?: "") +
            marks.entries.joinToString(" ") { "${it.key}=${it.value}ms" } + " " +
            counts.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }
    fun firstGet(waitNanos: Long) { log("first_get_wait=${waitNanos / 1_000_000}ms") }
}
