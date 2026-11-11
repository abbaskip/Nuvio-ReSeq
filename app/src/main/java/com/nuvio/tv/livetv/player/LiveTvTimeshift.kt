package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.URL

/**
 * Pause and rewind live TV, for channels without catch-up: when you pause, the channel is
 * recorded to the device (one connection to the provider, the same one playback used), and
 * play continues from the recording, behind live. "Go live" drops it.
 *
 *  - TS streams: cut into short pieces with a local playlist as they arrive ([TsSegmenter]),
 *    so skipping back and forward works the same as on HLS channels.
 *  - HLS (m3u8) streams: each segment is saved as it appears, with a local playlist; older
 *    segments are dropped past the time limit.
 *
 * Everything lives in the app's cache folder and is deleted when you go live, change channel
 * or leave Live TV.
 */
class LiveTvTimeshift(private val context: Context) {

    enum class Kind { TS, HLS }

    /** What's being recorded: where it started (wall clock) and how to play it. */
    data class Session(
        val kind: Kind,
        val startedAtMs: Long,
        val playUri: Uri,
        val dir: File
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val received = java.util.concurrent.atomic.AtomicLong()
    val bytesReceived: Long get() = received.get()
    @Volatile var availableDurationMs: Long = 0L
        private set
    @Volatile var session: Session? = null
        private set
    @Volatile private var recording = false

    /** Time dropped from the start of an HLS recording (past the time limit). */
    @Volatile var trimmedMs: Long = 0L
        private set

    val isRecording: Boolean get() = recording

    /**
     * HLS: how much the first look at the provider's playlist saved (ms). Those parts end at the
     * live edge, which is ahead of where you paused (players play a little behind live): resuming
     * starts this far in, less how far behind live you were, so it carries on from where you paused.
     */
    @Volatile var firstBatchMs: Long = 0L
        private set

    /** Enough room on the device for [minutes] of recording (rough: about 35 MB a minute). */
    /** Where the buffer is kept: the drive set for recordings (this device's cache otherwise). */
    @Volatile var baseDir: File = File(context.cacheDir, "timeshift")

    fun hasRoomFor(minutes: Int): Boolean =
        runCatching { (baseDir.takeIf { it.exists() || it.mkdirs() } ?: context.cacheDir).usableSpace > (minutes * 35L + 300L) * 1024 * 1024 }.getOrDefault(false)

    /**
     * Starts recording [url] (with [headers]). Returns the session to play, or null if this
     * stream can't be recorded (encrypted HLS, a link that won't open…).
     */
    fun start(
        url: String,
        headers: Map<String, String>,
        /** Older parts dropped past this (null: keep it all, for a catch-up replay). */
        keepMinutes: Int?,
        /** Open the stream again when it ends (live); false for a replay, which simply ends. */
        reconnect: Boolean = true,
        /** How it's named in the Start-Up Report. */
        label: String = "Pause buffer",
        connectionReleaseDelayMs: Long = 2_500L
    ): Session? {
        stop()
        val directoryName = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
        val dir = File(baseDir, directoryName).apply { mkdirs() }
            .takeIf { it.exists() } ?: File(context.cacheDir, "timeshift/" + directoryName).apply { mkdirs() }
        val isHls = url.lowercase().substringBefore('?').endsWith(".m3u8") || url.contains("m3u8", ignoreCase = true)
        val now = System.currentTimeMillis()
        trimmedMs = 0L
        firstBatchMs = 0L
        recording = true
        received.set(0L)
        availableDurationMs = 0L
        val local = File(dir, "index.m3u8")
        val s = Session(if (isHls) Kind.HLS else Kind.TS, now, Uri.fromFile(local), dir)
        session = s
        ACTIVE += s.dir
        job = scope.launch {
            if (isHls) recordHls(url, headers, dir, local, keepMinutes ?: 24 * 60)
            else recordTs(url, headers, dir, keepMinutes, reconnect, label, connectionReleaseDelayMs)
        }
        return s
    }

    /** Stops recording and deletes it. */
    fun stop() {
        recording = false
        job?.cancel()
        job = null
        session?.dir?.let { d -> ACTIVE -= d; scope.launch { runCatching { d.deleteRecursively() } } }
        session = null
        trimmedMs = 0L
        // Leftovers from earlier (a crash, the app or the box switched off while paused).
        // Anything not written to for 15 minutes isn't being recorded any more: a buffer in use
        // gets a new piece every few seconds.
        cleanLeftovers(listOf(File(context.cacheDir, "timeshift"), baseDir), olderThanMs = 15 * 60_000L)
    }

    /**
     * Deletes buffers and saved replays left in [dirs] (all of them when [olderThanMs] is 0,
     * except the one being recorded now).
     */
    fun cleanLeftovers(dirs: List<File>, olderThanMs: Long = 0L) {
        scope.launch {
            val keep = ACTIVE.toSet()
            runCatching {
                dirs.distinct().flatMap { it.listFiles()?.toList().orEmpty() }.forEach { f: File ->
                    if (f in keep) return@forEach
                    val newest = if (f.isDirectory) (f.listFiles()?.maxOfOrNull { it.lastModified() } ?: f.lastModified()) else f.lastModified()
                    if (olderThanMs == 0L || newest < System.currentTimeMillis() - olderThanMs) f.deleteRecursively()
                }
            }
        }
    }

    // ------------------------------------------------------------------ TS

    private suspend fun recordTs(
        url: String,
        headers: Map<String, String>,
        dir: File,
        keepMinutes: Int?,
        reconnect: Boolean = true,
        label: String = "Pause buffer",
        connectionReleaseDelayMs: Long = 2_500L
    ) {
        // Pieces past the time limit are dropped as new ones come in (it records on, behind you).
        val segmenter = TsSegmenter(dir, targetSec = if (keepMinutes != null) REWIND_SEGMENT_SECONDS else 6.0,
            keepSec = keepMinutes?.let { it * 60.0 }, fixedTargetDurationSec = if (keepMinutes != null) REWIND_TARGET_SECONDS else null) { _ -> }
        val report = com.nuvio.tv.livetv.model.LiveTvLoadReport
        val began = System.currentTimeMillis()
        // The player has only just let go of its connection: give the provider a moment, or
        // accounts with one connection can refuse (or later cut) this one.
        delay(connectionReleaseDelayMs)
        val workContext = currentCoroutineContext()
        var attempt = 0
        try {
            // Keeps going until you go live or change channel: a dropped connection is opened
            // again, with the gap marked, instead of ending the buffer (which sent you back to
            // live after half a minute or so).
            while (currentCoroutineContext().isActive && session?.dir == dir) {
                if (attempt > 0) {
                    segmenter.reconnected()
                    delay((attempt * 2_000L).coerceAtMost(8_000L))
                }
                attempt++
                val t0 = System.currentTimeMillis()
                var bytes = 0L
                val why = try {
                    LiveHttp.consume(url, headers) { response ->
                        val body = response.body ?: throw java.io.IOException("The provider returned no response body")
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (workContext.isActive && session?.dir == dir) {
                                val n = input.read(buf)
                                if (n < 0) break
                                workContext.ensureActive()
                                bytes += n
                                segmenter.write(buf, 0, n)
                                if (session?.dir == dir) {
                                    received.addAndGet(n.toLong())
                                    trimmedMs = (segmenter.trimmedSec * 1000).toLong()
                                    availableDurationMs = ((segmenter.totalSec - segmenter.trimmedSec) * 1000).toLong()
                                }
                            }
                            workContext.ensureActive()
                            "the provider ended the stream"
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                if (session?.dir != dir) break
                // A replay that came in and ended: it's all saved (no reconnecting, which would
                // start the replay again from its beginning).
                if (!reconnect && (bytes > 0 || attempt >= 2)) {
                    report.add("$label: ${bytes / 1024 / 1024} MB saved ($why)")
                    break
                }
                report.add("$label: connection ended after ${(System.currentTimeMillis() - t0) / 1000}s, ${bytes / 1024} KB ($why); reconnecting")
                // Got nothing at all several times running: give up (the provider won't serve it).
                if (bytes == 0L && attempt >= 9) { report.add("Pause buffer: gave up (no data)"); break }
                if (bytes > 0) attempt = 1
            }
        } finally {
            report.add("$label finished after ${(System.currentTimeMillis() - began) / 1000}s (${segmenter.totalSec.toInt()}s recorded)")
            runCatching { segmenter.finish(ended = true) }
            if (session?.dir == dir) recording = false
        }
    }

    // ------------------------------------------------------------------ HLS

    private data class Seg(val seq: Long, val durationSec: Double, val url: String) {
        val durationText = String.format(java.util.Locale.US, "%.3f", durationSec)
    }

    private suspend fun recordHls(url: String, headers: Map<String, String>, dir: File, local: File, keepMinutes: Int) {
        try {
            var mediaUrl = url
            var text = LiveHttp.fetchCancellable(mediaUrl, headers).let { (t, u) -> mediaUrl = u; t }
            // A master playlist: take the best-quality variant.
            if (text.contains("#EXT-X-STREAM-INF")) {
                val lines = text.lines()
                var best = -1L
                var bestUri: String? = null
                lines.forEachIndexed { i, l ->
                    if (l.startsWith("#EXT-X-STREAM-INF")) {
                        val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                        if (uri != null && bw >= best) { best = bw; bestUri = uri }
                    }
                }
                mediaUrl = resolve(mediaUrl, bestUri ?: return)
                text = LiveHttp.fetchCancellable(mediaUrl, headers).let { (t, u) -> mediaUrl = u; t }
            }
            // Encrypted streams aren't recorded (the keys would have to be saved too).
            if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) {
                Log.w(TAG, "Encrypted HLS: not recorded")
                if (session?.dir == dir) recording = false
                return
            }
            val saved = ArrayList<Seg>()
            var lastSeq = -1L
            var pendingInitUrl: String? = null
            var mapLine: String? = null
            var target = 6
            while (currentCoroutineContext().isActive && session?.dir == dir) {
                val lines = text.lines()
                var seq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                target = Regex("""#EXT-X-TARGETDURATION:(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: target
                // fMP4 streams: the init segment, once.
                if (mapLine == null) {
                    Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(text)?.groupValues?.get(1)?.let { m ->
                        pendingInitUrl = resolve(mediaUrl, m)
                    }
                    val initUrl = pendingInitUrl
                    if (initUrl != null && download(initUrl, headers, File(dir, "init.mp4"))) {
                        pendingInitUrl = null
                        mapLine = "#EXT-X-MAP:URI=\"init.mp4\""
                    }
                }
                var dur = 0.0
                for (l in lines) {
                    when {
                        l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: target.toDouble()
                        l.isNotBlank() && !l.startsWith("#") -> {
                            if (seq > lastSeq) {
                                val segFile = File(dir, "seg$seq.ts")
                                if (download(resolve(mediaUrl, l), headers, segFile)) {
                                    saved += Seg(seq, dur, segFile.name)
                                    lastSeq = seq
                                }
                            }
                            seq++
                        }
                    }
                }
                // Keep only the time limit's worth.
                var total = saved.sumOf { it.durationSec }
                while (saved.size > 3 && total > keepMinutes * 60.0) {
                    val first = saved.removeAt(0)
                    trimmedMs += (first.durationSec * 1000).toLong()
                    total -= first.durationSec
                    runCatching { File(dir, first.url).delete() }
                }
                if (firstBatchMs == 0L && saved.isNotEmpty()) firstBatchMs = (saved.sumOf { it.durationSec } * 1000).toLong()
                currentCoroutineContext().ensureActive()
                if (session?.dir != dir) return
                if (pendingInitUrl == null) {
                    availableDurationMs = (saved.sumOf { it.durationSec } * 1000).toLong()
                    writeLocalPlaylist(local, saved, target, mapLine)
                }
                delay((target * 1000L / 2).coerceIn(1_000L, 6_000L))
                text = runCatching { LiveHttp.fetchCancellable(mediaUrl, headers).let { (t, u) -> mediaUrl = u; t } }.getOrElse { delay(2_000); continue }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "HLS recording stopped: ${e.javaClass.simpleName}")
        } finally {
            if (session?.dir == dir) recording = false
        }
    }

    private fun writeLocalPlaylist(file: File, segs: List<Seg>, target: Int, mapLine: String?) {
        if (segs.isEmpty()) return
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:").append(target + 1).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(segs.first().seq).append('\n')
        mapLine?.let { sb.append(it).append('\n') }
        segs.forEach { s -> sb.append("#EXTINF:").append(s.durationText).append(",\n").append(s.url).append('\n') }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    private suspend fun download(url: String, headers: Map<String, String>, to: File): Boolean {
        val workContext = currentCoroutineContext()
        return try {
            val tmp = File(to.parentFile, to.name + ".part")
            LiveHttp.consume(url, headers) { response ->
                val body = response.body ?: throw java.io.IOException("The provider returned no response body")
                body.byteStream().use { input ->
                    tmp.outputStream().buffered(128 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            workContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            workContext.ensureActive()
                            output.write(buffer, 0, n)
                            received.addAndGet(n.toLong())
                        }
                    }
                }
            }
            workContext.ensureActive()
            tmp.renameTo(to)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            workContext.ensureActive()
            false
        }
    }

    private fun resolve(base: String, ref: String): String = runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)

    /**
     * Plays a file that's still being written (the TS recording): at the end of what's written
     * so far it waits for more, instead of ending, while recording carries on.
     */
    inner class GrowingFileDataSource : BaseDataSource(false) {
        private var file: RandomAccessFile? = null
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val f = RandomAccessFile(File(dataSpec.uri.path ?: ""), "r")
            f.seek(dataSpec.position)
            file = f
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val f = file ?: return C.RESULT_END_OF_INPUT
            var waited = 0
            while (true) {
                val n = f.read(buffer, offset, length)
                if (n > 0) { bytesTransferred(n); return n }
                if (!recording || waited > 30_000) return C.RESULT_END_OF_INPUT
                Thread.sleep(150)
                waited += 150
            }
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            runCatching { file?.close() }
            file = null
            if (uri != null) { uri = null; transferEnded() }
        }
    }

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { GrowingFileDataSource() }

    private companion object {
        const val TAG = "LiveTvTimeshift"
        /** Buffers and saved replays in use right now (by any player): never cleaned up. */
        val ACTIVE: MutableSet<File> = java.util.Collections.synchronizedSet(HashSet())
    }
}
