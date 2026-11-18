package com.nuvio.tv.core.report

data class PlaybackReport(val engine: String, val fields: Map<String, String>, val events: List<String>,
    internal val sourceId: String? = null)

/** Technical playback only. No title, channel/account name, media ID, position or watch history. */
object PlaybackReports {
    private data class Session(val engine: String, val sourceId: String?, val fields: MutableMap<String, String> = linkedMapOf(),
        val events: ArrayDeque<String> = ArrayDeque())
    private val sessions = linkedMapOf<Long, Session>()
    // An active pane may outlive five changes in other panes. Keep its current diagnostics
    // independently of recent history; weak keys never keep released players alive.
    private val owners = java.util.WeakHashMap<Any, Session>()
    private var serial = 0L
    val allowedFields = setOf("context", "provider type", "provider host", "stream type", "container", "video codec",
        "codec profile/level", "coded size", "display size", "crop window", "pixel aspect ratio", "rotation",
        "frame rate", "decoder", "decoder mode", "surface size", "view size", "aspect/zoom", "picture shape",
        "letterbox mask", "display switching", "audio codec", "audio channels", "audio passthrough", "audio decoder",
        "state", "rebuffers", "last error")

    @Synchronized fun begin(owner: Any, engine: String, context: String, sourceId: String? = null): Long {
        val id = ++serial
        val session = Session(engine, sourceId, linkedMapOf("context" to context))
        owners[owner] = session
        sessions[id] = session
        while (sessions.size > 5) sessions.remove(sessions.keys.first())
        event(owner, "session started ($engine / $context)")
        return id
    }
    @Synchronized fun update(owner: Any, values: Map<String, String>) {
        val session = owners[owner] ?: return
        val safe = values.filterKeys { it in allowedFields }.mapValues { ReportRedactor.clean(it.value).take(256) }
        val changed = safe.filter { session.fields[it.key] != it.value }
        session.fields.putAll(changed)
        if (changed.isNotEmpty()) event(owner, changed.entries.joinToString { "${it.key}=${it.value}" })
    }
    @Synchronized fun event(owner: Any, technical: String) {
        val session = owners[owner] ?: return
        val cleaned = ReportRedactor.clean(technical).take(1024)
        session.events.addLast(cleaned)
        while (session.events.size > 24) session.events.removeFirst()
        ReportLog.event("${session.engine}: $cleaned")
    }
    @Synchronized fun current(owner: Any?): PlaybackReport? = owners[owner]?.let {
        PlaybackReport(it.engine, it.fields.toMap(), it.events.toList(), it.sourceId)
    }
    @Synchronized fun recent(): List<PlaybackReport> = sessions.values.map {
        PlaybackReport(it.engine, it.fields.toMap(), it.events.toList(), it.sourceId)
    }
}

data class ProblemReportInput(
    val version: String, val device: String, val environment: Map<String, String>,
    val features: List<String>, val services: List<String>, val storage: String,
    val crash: String?, val logs: String, val sessions: List<PlaybackReport>, val current: PlaybackReport?
)

object ProblemReportBuilder {
    val environmentFields = setOf("versionCode", "hardware", "SoC", "Android", "memoryClass", "largeMemoryClass",
        "maxMemory", "free storage", "display mode", "HDR types")
    fun build(input: ProblemReportInput): String = ReportRedactor.clean(buildString {
        appendLine("Nuvio ReSeq report ${input.version} ${input.device}")
        input.environment.filterKeys { it in environmentFields }.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("Enabled features: ${input.features.joinToString()}")
        appendLine("Connected services (names only): ${input.services.joinToString().ifBlank { "None" }}")
        appendLine("\nStorage\n${input.storage}")
        appendLine("\nSaved crash / freeze / exit\n${input.crash?.let(ReportRedactor::crash) ?: "None saved"}")
        fun playback(label: String, session: PlaybackReport) {
            appendLine("\n$label: ${session.engine}")
            for (key in PlaybackReports.allowedFields) appendLine("$key: ${session.fields[key] ?: "not reported by this engine"}")
            session.events.takeLast(24).forEach { appendLine("event: $it") }
        }
        input.current?.let { playback("Current video (captured when Report was selected)", it) }
        input.sessions.takeLast(5).forEachIndexed { i, s -> playback("Recent technical session ${i + 1}", s) }
        appendLine("\nRecent application warnings and technical events\n${input.logs.takeLast(512_000)}")
        appendLine("\nNo account names, media titles, watch history, credentials or full URLs are included.")
    })
}
