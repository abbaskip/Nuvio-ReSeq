package com.nuvio.tv.core.report

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.ArrayBlockingQueue

/** Two bounded segments, already cleaned before persistence. One writer, no timer/polling. */
class ReportRing(private val directory: File, private val segmentBytes: Int = 250 * 1024) : Closeable {
    private val current get() = File(directory, "recent.txt")
    private val previous get() = File(directory, "previous.txt")
    private var stream: FileOutputStream? = null
    private var size = 0L

    @Synchronized fun append(cleaned: String) {
        val bytes = (cleaned.take(4096) + "\n").toByteArray(Charsets.UTF_8)
        val count = minOf(segmentBytes, bytes.size)
        if (stream == null) {
            directory.mkdirs()
            size = current.length()
        }
        if (size + count > segmentBytes) {
            close()
            previous.delete()
            if (!current.renameTo(previous)) current.delete()
            size = current.length()
        }
        val writer = stream ?: FileOutputStream(current, true).also { stream = it }
        try {
            // A completed write is visible to other readers and survives an app process kill.
            writer.write(bytes, 0, count)
            size += count
        } catch (failure: java.io.IOException) {
            close()
            throw failure
        }
    }
    @Synchronized override fun close() {
        val writer = stream
        stream = null
        writer?.close()
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
    private val queue = ArrayBlockingQueue<Runnable>(128)
    @Synchronized fun start(directory: File, pid: Int) {
        if (ring != null) return
        val sink = ReportRing(directory).also { ring = it }
        Thread({ while (true) {
            val work = try { queue.take() } catch (_: InterruptedException) { return@Thread }
            runCatching { work.run() }
        } }, "report-log-writer").apply { isDaemon = true; start() }
        Thread({
            runCatching {
                val process = ProcessBuilder("logcat", "--pid=$pid", "-v", "threadtime", "*:W").redirectErrorStream(true).start()
                try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    ReportRedactor.logLine(line)?.let { cleaned -> queue.offer { sink.append(cleaned) } }
                } } } finally { process.destroy() }
            }
        }, "report-own-warnings").apply { isDaemon = true; start() }
        event("app started")
    }
    /** Call only with technical fields, never titles, account/profile names or media IDs. */
    fun event(technical: String) {
        val cleaned = ReportRedactor.clean(technical).take(4096)
        queue.offer { ring?.append(cleaned) }
    }

    fun read(): String {
        val sink = ring ?: return ""
        val snapshot = FutureTask { sink.read() }
        queue.put(snapshot)
        return snapshot.get()
    }
}
