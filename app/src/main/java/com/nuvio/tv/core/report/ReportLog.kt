package com.nuvio.tv.core.report

import java.io.File
import java.util.concurrent.ArrayBlockingQueue

/** Two bounded segments, already cleaned before persistence. One writer, no timer/polling. */
class ReportRing(private val directory: File, private val segmentBytes: Int = 250 * 1024) {
    private val current get() = File(directory, "recent.txt")
    private val previous get() = File(directory, "previous.txt")
    @Synchronized fun append(cleaned: String) {
        directory.mkdirs()
        val bytes = (cleaned.take(4096) + "\n").toByteArray(Charsets.UTF_8).take(segmentBytes).toByteArray()
        if (current.length() + bytes.size > segmentBytes) {
            previous.delete()
            if (!current.renameTo(previous)) current.delete()
        }
        current.appendBytes(bytes)
    }
    @Synchronized fun read(): String = listOf(previous, current).joinToString("") {
        if (it.isFile) it.inputStream().use { stream ->
            val data = ByteArray(segmentBytes)
            var count = 0
            while (count < data.size) {
                val read = stream.read(data, count, data.size - count)
                if (read < 0) break
                count += read
            }
            String(data, 0, count, Charsets.UTF_8)
        } else ""
    }
}

object ReportLog {
    @Volatile private var ring: ReportRing? = null
    private val queue = ArrayBlockingQueue<String>(128)
    @Synchronized fun start(directory: File, pid: Int) {
        if (ring != null) return
        val sink = ReportRing(directory).also { ring = it }
        Thread({ while (true) {
            val line = try { queue.take() } catch (_: InterruptedException) { return@Thread }
            runCatching { sink.append(line) }
        } }, "report-log-writer").apply { isDaemon = true; start() }
        Thread({
            runCatching {
                val process = ProcessBuilder("logcat", "--pid=$pid", "-v", "threadtime", "*:W").redirectErrorStream(true).start()
                try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    ReportRedactor.logLine(line)?.let { queue.offer(it) }
                } } } finally { process.destroy() }
            }
        }, "report-own-warnings").apply { isDaemon = true; start() }
        event("app started")
    }
    /** Call only with technical fields, never titles, account/profile names or media IDs. */
    fun event(technical: String) { queue.offer(ReportRedactor.clean(technical).take(4096)) }
    fun read(): String = ring?.read().orEmpty()
}
