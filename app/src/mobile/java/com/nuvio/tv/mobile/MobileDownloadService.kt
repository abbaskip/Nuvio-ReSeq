package com.nuvio.tv.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.nuvio.tv.core.usenet.UsenetPlayback
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import com.nuvio.tv.core.network.NetworkResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
internal class MobileDownloadService : Service() {
    @Inject lateinit var media: MobileOfflineMedia
    @Inject lateinit var metadata: com.nuvio.tv.domain.repository.MetaRepository
    @Inject lateinit var profiles: com.nuvio.tv.core.profile.ProfileManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<String, String>()
    private val client = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).followRedirects(false).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getStringExtra("video")?.let { Gson().fromJson(it, MobileVideo::class.java) }
        val url = intent?.getStringExtra("url")
        if (video == null || url == null) {
            if (running.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        val key = video.key
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Video downloads", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTICE, notification(video.title, "Downloading"))
        if (key in running) {
            if (running[key] != url) UsenetPlayback.release(url)
            return START_NOT_STICKY
        }
        running[key] = url
        val headers = JSONObject(intent.getStringExtra("headers") ?: "{}")
        scope.launch {
            fun status(message: String, active: Boolean) {
                media.transfers.update { it + (key to DownloadStatus(video, message, active)) }
            }
            status("Downloading", true)
            try {
                withContext(Dispatchers.IO) {
                    if (intent.getBooleanExtra("replace", false) || media.find(video) == null) saveFile(video, url, headers) { message ->
                        status(message, true)
                        getSystemService(NotificationManager::class.java).notify(NOTICE, notification(video.title, message))
                    }
                }
                status("Downloaded", false)
            } catch (cancel: CancellationException) {
                status("Interrupted. Download again to retry.", false)
                throw cancel
            } catch (error: Exception) {
                status(if (error is DownloadFailure) error.message.orEmpty() else "Download failed. Check storage or try another source.", false)
            } finally {
                UsenetPlayback.release(url)
                running.remove(key)
                if (running.isEmpty()) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun saveFile(video: MobileVideo, url: String, headers: JSONObject, progress: (String) -> Unit) {
        val dir = media.folder(video)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot open download folder")
        val partial = File(dir, "media.part")
        val job = currentCoroutineContext()[Job]!!
        var target = url.toHttpUrlOrNull() ?: throw DownloadFailure("This source does not provide a downloadable file.")
        var request = Request.Builder().url(target).apply {
            for (name in headers.keys()) if (!name.equals("Range", true)) header(name, headers.getString(name))
            header("Accept-Encoding", "identity")
        }.build()
        try {
            repeat(10) {
                job.ensureActive()
                val call = client.newCall(request)
                val abort = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
                try {
                    call.execute().use { response ->
                        if (response.isRedirect) {
                            val next = response.header("Location")?.let(target::resolve) ?: throw DownloadFailure("Invalid source redirect.")
                            val sameOrigin = target.scheme == next.scheme && target.host == next.host && target.port == next.port
                            request = (if (sameOrigin) request.newBuilder() else Request.Builder()).url(next).build()
                            target = next
                            return@repeat
                        }
                        if (response.code != 200) throw DownloadFailure("Source returned HTTP ${response.code}. Try another file.")
                        val body = response.body ?: throw DownloadFailure("Empty response from source.")
                        val extension = videoContainer(response.peekBody(1024).bytes())
                            ?: throw DownloadFailure("Choose a video file. Adaptive playlists and web pages cannot be saved as a single video.")
                        val total = body.contentLength()
                        if (total > 0 && total > dir.usableSpace) throw DownloadFailure("Not enough free space for this file.")
                        var written = 0L
                        var lastUpdate = 0L
                        body.byteStream().use { input -> partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                job.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                written += n
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastUpdate >= 1000) {
                                    progress(if (total > 0) "Downloading ${written * 100 / total}%" else "Downloading ${written / 1048576} MB")
                                    lastUpdate = now
                                }
                            }
                        } }
                        if (written == 0L || total >= 0 && total != written) throw DownloadFailure("Incomplete file. Download again to retry.")
                        job.ensureActive()
                        val meta = media.viewedTitle(video) ?: downloadMetadata(video)
                        val cover = media.fetchCover(meta?.poster ?: video.poster)
                        job.ensureActive()
                        media.publish(video, partial, extension, meta?.offlineDetails(video), cover, meta, media.cachedOrders(video.profile, meta))
                        return
                    }
                } finally { abort.dispose() }
            }
            throw DownloadFailure("Too many source redirects.")
        } finally { partial.delete() }
    }

    private suspend fun downloadMetadata(video: MobileVideo): com.nuvio.tv.domain.model.Meta? {
        if (profiles.activeProfileId.value != video.profile) return null
        return try {
            withTimeoutOrNull(3_000) {
                val order = com.nuvio.tv.domain.model.TvdbPlaybackContext.decode(video.tvdb)
                val meta = order?.let { metadata.getPlaybackMeta(video.profile, video.type, video.contentId, it) }
                    ?: metadata.getCachedMeta(video.type, video.contentId)
                    ?: (metadata.getMetaFromAllAddons(video.type, video.contentId).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
                meta?.takeIf { profiles.activeProfileId.value == video.profile }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private fun notification(title: String, message: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(message)
        .setOngoing(true).setOnlyAlertOnce(true).build()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { scope.cancel(); stopSelf() }

    companion object {
        private const val CHANNEL = "offline-videos"
        private const val NOTICE = 7302
        fun enqueue(context: Context, video: MobileVideo, info: StreamPlaybackInfo, replace: Boolean) {
            require(info.url?.toHttpUrlOrNull() != null && !info.isExternal) { "Choose a downloadable file source." }
            val intent = Intent(context, MobileDownloadService::class.java)
                .putExtra("video", Gson().toJson(video)).putExtra("url", info.url)
                .putExtra("headers", JSONObject(info.headers.orEmpty()).toString()).putExtra("replace", replace)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private class DownloadFailure(message: String) : IOException(message)

internal fun videoContainer(head: ByteArray): String? {
    fun at(index: Int, vararg bytes: Int) = bytes.indices.all { head.getOrNull(index + it)?.toInt()?.and(255) == bytes[it] }
    fun ascii(index: Int, text: String) = at(index, *text.map { it.code }.toIntArray())
    return when {
        ascii(4, "ftyp") || ascii(4, "moov") || ascii(4, "mdat") -> "mp4"
        at(0, 0x1a, 0x45, 0xdf, 0xa3) -> if (head.toString(Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
        ascii(0, "RIFF") && ascii(8, "AVI ") -> "avi"
        at(0, 0x47) && at(188, 0x47) || at(4, 0x47) && at(196, 0x47) -> "ts"
        at(0, 0, 0, 1, 0xba) || at(0, 0, 0, 1, 0xb3) -> "mpg"
        ascii(0, "OggS") -> "ogv"
        ascii(0, "FLV") -> "flv"
        at(0, 0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11) -> "wmv"
        else -> null
    }
}
